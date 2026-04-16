package com.epam.reportportal.service.impl;

import com.epam.reportportal.config.S3MigrationClients;
import com.epam.reportportal.logging.LogMigration;
import com.epam.reportportal.model.Attachment;
import com.epam.reportportal.model.CopyResult;
import com.epam.reportportal.model.MigrationState;
import com.epam.reportportal.model.Plugin;
import com.epam.reportportal.model.User;
import com.epam.reportportal.repository.MigrationStateRepository;
import com.epam.reportportal.service.MigrationMetrics;
import com.epam.reportportal.service.MigrationService;
import com.epam.reportportal.utils.AttachmentRowMapper;
import com.epam.reportportal.utils.PluginRowMapper;
import com.epam.reportportal.utils.UserRowMapper;
import com.google.common.collect.Iterables;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * MinIO multi-bucket → AWS S3 single bucket in one run: reads project buckets and the shared buckets on
 * MinIO, writes into {@code datastore.singleBucketName} on S3 using GET + PUT. Updates DB paths after each
 * successful object upload. Optional {@code migration.s3.*} flags tune HEAD behaviour.
 * <p>
 * Parallelism: {@code migration.intra-project.parallelism} and {@code migration.parallelism}.
 */
@Service
@Order(1)
@ConditionalOnProperty(name = "rp.storage.migration", havingValue = "true")
public class SingleBucketMigrationServiceImpl implements MigrationService {

  private static final String MIGRATION_TYPE = "SINGLE_BUCKET";

  private static final String PROJECT_PREFIX = "project-data/";
  private static final String USERS_MULTIBUCKET_NAME = "users";
  private static final String USERS_SINGLEBUCKET_PREFIX = "user-data/";
  private static final String PHOTOS_PREFIX = "photos/";
  private static final String PLUGINS_PREFIX = "plugins/";
  private static final String SECRETS_PREFIX = "integration-secrets/";
  private static final String SELECT_ALL_PLUGINS = "SELECT id, details FROM integration_type";
  private static final String SELECT_ALL_USERS =
      "SELECT id, attachment, attachment_thumbnail FROM public.users";
  private static final String UPDATE_ATTACHMENT_FILE_ID =
      "UPDATE public.attachment SET file_id = ? WHERE id = ?";
  private static final String UPDATE_ATTACHMENT_THUMBNAIL_ID =
      "UPDATE public.attachment SET thumbnail_id = ? WHERE id = ?";
  private static final String UPDATE_USER_PHOTO =
      "UPDATE public.users SET attachment = ? WHERE id = ?";
  private static final String UPDATE_USER_PHOTO_THUMBNAIL =
      "UPDATE public.users SET attachment_thumbnail = ? WHERE id = ?";
  private static final String UPDATE_PLUGIN_DETAILS =
      "UPDATE public.integration_type SET details = ?::JSONB WHERE id = ?";

  private final Logger logger = LoggerFactory.getLogger(this.getClass());

  private final String bucketPrefix;
  private final String defaultBucketName;
  private final String singleBucketName;
  private final String secretsPath;
  private final Boolean removeAfterMigration;
  private final int batchSize;
  private final int parallelism;
  private final int maxRetries;
  private final long retryBaseDelayMs;
  private final int progressLogInterval;
  /** Batched JDBC updates for attachment file_id / thumbnail_id after successful S3 copy; 1 = row-by-row. */
  private final int attachmentUpdateBatchSize;
  /** If true, HEAD destination after each successful copy (extra latency; strongest verify). */
  private final boolean verifyDestinationAfterCopy;
  /** If true, HEAD source before copy when object not known complete (extra latency; avoids failed copy). */
  private final boolean headSourceBeforeCopy;
  /** Objects at or below this size are buffered in memory for GET+PUT; larger use a temp file. */
  private final long maxInMemoryCopyBytes;
  /** AWS region of the destination bucket (used when creating the bucket; {@code us-east-1} omits location constraint). */
  private final String destinationRegion;
  /**
   * Concurrent attachment rows processed per project (separate pool from {@link #parallelism}).
   * Use &gt;1 for many small files; total S3/DB load scales roughly with
   * {@code parallelism × intraProjectParallelism}.
   */
  private final int intraProjectParallelism;
  /** Dedicated pool for {@link #intraProjectParallelism}; null when intraProjectParallelism is 1. */
  private final ExecutorService intraProjectExecutor;

  private final S3MigrationClients clients;
  private final JdbcTemplate jdbcTemplate;
  private final MigrationStateRepository stateRepo;
  private final ExecutorService copyExecutor;

  /** Validated column name on {@code public.attachment} used in cutoff predicates (configurable). */
  private final String attachmentCreationDateColumn;
  private final String discoverProjectsSql;
  private final String selectAttachmentsKeysetSql;
  /**
   * When blank, attachment cutoff is {@link Instant#now()} when project-data migration starts.
   * When set, parsed by {@link #resolveAttachmentCutoffInstant()} (ISO-8601, epoch millis, or UTC local).
   */
  private final String attachmentCutoffRaw;

  public SingleBucketMigrationServiceImpl(
      S3MigrationClients clients,
      JdbcTemplate jdbcTemplate,
      MigrationStateRepository stateRepo,
      @Value("${datastore.bucketPrefix}") String bucketPrefix,
      @Value("${datastore.defaultBucketName}") String defaultBucketName,
      @Value("${datastore.singleBucketName}") String singleBucketName,
      @Value("${datastore.secrets.path}") String secretsPath,
      @Value("#{new Boolean('${datastore.remove.after.migration}')}") Boolean removeAfterMigration,
      @Value("${migration.batch.size:500000}") int batchSize,
      @Value("${migration.parallelism:8}") int parallelism,
      @Value("${migration.retry.max:3}") int maxRetries,
      @Value("${migration.retry.base-delay-ms:1000}") long retryBaseDelayMs,
      @Value("${migration.progress.log-interval:5000}") int progressLogInterval,
      @Value("${migration.db.attachment-update-batch-size:1000}") int attachmentUpdateBatchSize,
      @Value("${migration.db.attachment-creation-date-column:creation_date}") String attachmentCreationDateColumn,
      @Value("${migration.db.attachment-cutoff:}") String attachmentCutoffRaw,
      @Value("${migration.s3.verify-destination-after-copy:true}") boolean verifyDestinationAfterCopy,
      @Value("${migration.s3.head-source-before-copy:true}") boolean headSourceBeforeCopy,
      @Value("${migration.storage.max-in-memory-copy-mb:128}") double maxInMemoryCopyMb,
      @Value("${migration.storage.destination.region}") String destinationRegion,
      @Value("${migration.intra-project.parallelism:1}") int intraProjectParallelism) {
    this.jdbcTemplate = jdbcTemplate;
    this.stateRepo = stateRepo;
    this.clients = clients;
    this.bucketPrefix = bucketPrefix;
    this.defaultBucketName = defaultBucketName;
    this.singleBucketName = singleBucketName;
    this.secretsPath = secretsPath;
    this.removeAfterMigration = removeAfterMigration;
    this.batchSize = batchSize;
    this.parallelism = parallelism;
    this.maxRetries = maxRetries;
    this.retryBaseDelayMs = retryBaseDelayMs;
    this.progressLogInterval = progressLogInterval;
    this.attachmentUpdateBatchSize = Math.max(1, attachmentUpdateBatchSize);
    this.attachmentCreationDateColumn = requireSqlIdentifier(attachmentCreationDateColumn, "migration.db.attachment-creation-date-column");
    this.discoverProjectsSql =
        "SELECT project_id, COUNT(id) AS cnt FROM public.attachment WHERE "
            + this.attachmentCreationDateColumn + " < ? GROUP BY project_id ORDER BY cnt DESC";
    this.selectAttachmentsKeysetSql =
        "SELECT id, file_id, thumbnail_id, project_id FROM public.attachment WHERE project_id = ? AND id > ? AND "
            + this.attachmentCreationDateColumn + " < ? ORDER BY id LIMIT ?";
    this.attachmentCutoffRaw = attachmentCutoffRaw;
    this.verifyDestinationAfterCopy = verifyDestinationAfterCopy;
    this.headSourceBeforeCopy = headSourceBeforeCopy;
    this.maxInMemoryCopyBytes = Math.max(0L, Math.round(maxInMemoryCopyMb * 1024.0 * 1024.0));
    this.destinationRegion = destinationRegion;
    this.intraProjectParallelism = Math.max(1, intraProjectParallelism);
    this.copyExecutor = Executors.newFixedThreadPool(parallelism);
    this.intraProjectExecutor = this.intraProjectParallelism > 1
        ? Executors.newFixedThreadPool(this.intraProjectParallelism)
        : null;
    logger.info("Migration configured: batchSize={}, parallelism={}, intraProjectParallelism={}, maxRetries={}, "
            + "retryBaseDelayMs={}, progressLogInterval={}, attachmentUpdateBatchSize={}, "
            + "attachmentCreationDateColumn={}, attachmentCutoff={}, verifyDestinationAfterCopy={}, "
            + "headSourceBeforeCopy={}, maxInMemoryCopyMb={}, destinationRegion={}",
        batchSize, parallelism, this.intraProjectParallelism, maxRetries, retryBaseDelayMs,
        progressLogInterval, this.attachmentUpdateBatchSize, this.attachmentCreationDateColumn,
        StringUtils.isBlank(attachmentCutoffRaw) ? "<unset → Instant.now() at project-data start>"
            : attachmentCutoffRaw,
        verifyDestinationAfterCopy, headSourceBeforeCopy, maxInMemoryCopyMb, destinationRegion);
  }

  /**
   * Allows only unquoted PostgreSQL identifiers (letters, digits, underscore) to keep dynamic SQL safe.
   */
  private static String requireSqlIdentifier(String raw, String propertyName) {
    if (StringUtils.isBlank(raw)) {
      throw new IllegalArgumentException(propertyName + " must not be blank");
    }
    String trimmed = raw.trim();
    if (!trimmed.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
      throw new IllegalArgumentException(propertyName + " must be a simple SQL identifier, got: " + raw);
    }
    return trimmed;
  }

  /**
   * Empty config → {@link Instant#now()} when project-data migration runs. Otherwise: ISO-8601 instant
   * ({@code 2026-03-26T18:31:59.509Z}), offset datetime, epoch milliseconds, or {@code yyyy-MM-dd HH:mm:ss[.SSS]}
   * interpreted as UTC.
   */
  private Instant resolveAttachmentCutoffInstant() {
    if (StringUtils.isBlank(attachmentCutoffRaw)) {
      return Instant.now();
    }
    return parseAttachmentCutoffToInstant(attachmentCutoffRaw.trim());
  }

  private static Instant parseAttachmentCutoffToInstant(String s) {
    try {
      return Instant.parse(s);
    } catch (DateTimeParseException ignored) {
      // try other formats
    }
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException ignored) {
    }
    if (s.matches("\\d{1,18}")) {
      return Instant.ofEpochMilli(Long.parseLong(s));
    }
    DateTimeFormatter[] utcLocalFormatters = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    };
    for (DateTimeFormatter f : utcLocalFormatters) {
      try {
        return LocalDateTime.parse(s, f).atZone(ZoneOffset.UTC).toInstant();
      } catch (DateTimeParseException ignored) {
      }
    }
    throw new IllegalArgumentException(
        "migration.db.attachment-cutoff must be empty, epoch millis, ISO-8601 instant, offset datetime, "
            + "or yyyy-MM-dd HH:mm:ss[.SSS] (UTC). Got: " + s);
  }

  @Override
  @LogMigration("MinIO multi-bucket to S3 single-bucket storage migration")
  public void migrate() {
    if (StringUtils.isEmpty(singleBucketName)) {
      logger.warn("singleBucketName is empty, skipping migration");
      return;
    }

    try {
      ensureDestinationBucket();
      migrateIntegrationSecrets();
      migratePlugins();
      migrateUserPhotos();
      migrateProjectData();
      retryFailedItems();
      logFinalSummary();
    } finally {
      shutdownExecutor();
    }
  }

  private void shutdownExecutor() {
    copyExecutor.shutdown();
    if (intraProjectExecutor != null) {
      intraProjectExecutor.shutdown();
    }
    try {
      if (!copyExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        logger.warn("Copy executor did not terminate in 30s, forcing shutdown");
        copyExecutor.shutdownNow();
      }
      if (intraProjectExecutor != null
          && !intraProjectExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
        logger.warn("Intra-project executor did not terminate in 60s, forcing shutdown");
        intraProjectExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      copyExecutor.shutdownNow();
      if (intraProjectExecutor != null) {
        intraProjectExecutor.shutdownNow();
      }
      Thread.currentThread().interrupt();
    }
  }

  // ---- destination bucket ----

  private void ensureDestinationBucket() {
    if (bucketExists(singleBucketName, clients.getDestination())) {
      return;
    }
    CreateBucketRequest.Builder req = CreateBucketRequest.builder().bucket(singleBucketName);
    if (destinationRegion != null && !destinationRegion.isEmpty()
        && !"us-east-1".equals(destinationRegion)) {
      req.createBucketConfiguration(CreateBucketConfiguration.builder()
          .locationConstraint(BucketLocationConstraint.fromValue(destinationRegion))
          .build());
    }
    CreateBucketResponse resp = clients.getDestination().createBucket(req.build()).join();
    if (resp.sdkHttpResponse().isSuccessful()) {
      logger.info("Created destination S3 bucket '{}' in region {}", singleBucketName, destinationRegion);
    } else {
      throw new IllegalStateException("Failed to create destination bucket: " + singleBucketName);
    }
  }

  // ---- source metadata ----

  /** Empty if object does not exist or HEAD failed. */
  private Optional<Long> tryHeadSourceContentLength(String bucket, String key) {
    try {
      HeadObjectResponse r = clients.getSource().headObject(
          HeadObjectRequest.builder().bucket(bucket).key(key).build()
      ).join();
      return Optional.of(r.contentLength());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * True when the exception chain indicates the source object is missing (HTTP 404 / NoSuchKey).
   */
  private boolean isSourceNotFoundError(Throwable e) {
    Throwable cur = e;
    while (cur != null) {
      if (cur instanceof S3Exception) {
        S3Exception se = (S3Exception) cur;
        if (se.statusCode() == 404) {
          return true;
        }
        if (se.awsErrorDetails() != null) {
          String code = se.awsErrorDetails().errorCode();
          if ("NoSuchKey".equals(code) || "NotFound".equals(code)) {
            return true;
          }
        }
      }
      cur = cur.getCause();
    }
    return false;
  }

  // ---- MinIO → S3 object copy (GET + PUT) with verify + retry ----

  /**
   * Copies one object from MinIO to S3 (cross-endpoint; no server-side CopyObject).
   */
  private CopyResult copyMinioToS3WithVerify(String srcBucket, String srcKey,
      String destBucket, String destKey, Optional<Long> sourceContentLength) {
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        Optional<Long> len = sourceContentLength;
        if (!len.isPresent()) {
          len = tryHeadSourceContentLength(srcBucket, srcKey);
        }
        if (!len.isPresent()) {
          return CopyResult.missingSource();
        }
        long size = len.get();
        transferObjectCrossStorage(srcBucket, srcKey, destBucket, destKey, size);

        if (verifyDestinationAfterCopy) {
          HeadObjectResponse head = clients.getDestination().headObject(
              HeadObjectRequest.builder().bucket(destBucket).key(destKey).build()
          ).join();
          return CopyResult.success(head.contentLength());
        }
        return CopyResult.success(size);

      } catch (Exception e) {
        if (isSourceNotFoundError(e)) {
          return CopyResult.missingSource();
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (attempt < maxRetries) {
          long delay = retryBaseDelayMs * (1L << (attempt - 1));
          logger.warn("MinIO→S3 copy attempt {}/{} failed for s3://{}/{} : {} — retrying in {}ms",
              attempt, maxRetries, srcBucket, srcKey, msg, delay);
          sleep(delay);
        } else {
          logger.error("MinIO→S3 copy FAILED after {} attempts for s3://{}/{} : {}",
              maxRetries, srcBucket, srcKey, msg);
          return CopyResult.failure(msg);
        }
      }
    }
    return CopyResult.failure("exhausted retries");
  }

  private void transferObjectCrossStorage(String srcBucket, String srcKey,
      String destBucket, String destKey, long size) throws java.io.IOException {
    if (maxInMemoryCopyBytes > 0 && size <= maxInMemoryCopyBytes) {
      var bytes = clients.getSource().getObject(
          GetObjectRequest.builder().bucket(srcBucket).key(srcKey).build(),
          AsyncResponseTransformer.toBytes()).join();
      clients.getDestination().putObject(
          PutObjectRequest.builder().bucket(destBucket).key(destKey).build(),
          AsyncRequestBody.fromBytes(bytes.asByteArray())).join();
      return;
    }
    Path tmp = Files.createTempFile("rp-migr-", ".bin");
    try {
      clients.getSource().getObject(
          GetObjectRequest.builder().bucket(srcBucket).key(srcKey).build(),
          AsyncResponseTransformer.toFile(tmp)).join();
      clients.getDestination().putObject(
          PutObjectRequest.builder().bucket(destBucket).key(destKey).build(),
          AsyncRequestBody.fromFile(tmp)).join();
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private void putStateInCache(Map<String, MigrationState> cache, String srcKey, MigrationState state) {
    if (cache != null) {
      cache.put(srcKey, state);
    }
  }

  private void trackAndCopy(String entityType, Long entityId,
      String srcBucket, String srcKey,
      String destBucket, String destKey,
      MigrationMetrics metrics) {
    Optional<MigrationState> existing =
        stateRepo.findByKey(MIGRATION_TYPE, srcBucket, srcKey);
    if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
      logger.debug("Skipping already-completed {} entityId={} s3://{}/{}",
          entityType, entityId, srcBucket, srcKey);
      metrics.incrementSkipped();
      return;
    }

    Optional<Long> srcLen = Optional.empty();
    if (headSourceBeforeCopy || maxInMemoryCopyBytes > 0) {
      srcLen = tryHeadSourceContentLength(srcBucket, srcKey);
      if (!srcLen.isPresent()) {
        logger.warn("SOURCE_NOT_FOUND: {} entityId={} does not exist at s3://{}/{} — skipping",
            entityType, entityId, srcBucket, srcKey);
        MigrationState state = existing.orElseGet(() ->
            new MigrationState(MIGRATION_TYPE, entityType, entityId,
                srcBucket, srcKey, destBucket, destKey));
        state.setStatus("SKIPPED");
        state.setErrorMessage("Source object not found in storage");
        stateRepo.save(state);
        metrics.incrementSourceNotFound();
        return;
      }
    }

    MigrationState state = existing.orElseGet(() ->
        new MigrationState(MIGRATION_TYPE, entityType, entityId,
            srcBucket, srcKey, destBucket, destKey));
    state.setStatus("IN_PROGRESS");
    stateRepo.save(state);

    CopyResult result = copyMinioToS3WithVerify(srcBucket, srcKey, destBucket, destKey, srcLen);

    if (result.isSuccess()) {
      state.setStatus("COMPLETED");
      state.setAttempts(state.getAttempts() + 1);
      state.setErrorMessage(null);
      stateRepo.save(state);
      metrics.incrementCopied();
    } else if (result.isMissingSource()) {
      logger.warn("SOURCE_NOT_FOUND: {} entityId={} — copy failed (missing source) s3://{}/{}",
          entityType, entityId, srcBucket, srcKey);
      state.setStatus("SKIPPED");
      state.setErrorMessage("Source object not found in storage");
      stateRepo.save(state);
      metrics.incrementSourceNotFound();
    } else {
      logger.error("MIGRATION_FAILED: {} entityId={} — source=s3://{}/{} dest=s3://{}/{} — {}",
          entityType, entityId, srcBucket, srcKey, destBucket, destKey, result.getErrorMessage());
      state.setStatus("FAILED");
      state.setAttempts(state.getAttempts() + maxRetries);
      state.setErrorMessage(result.getErrorMessage());
      stateRepo.save(state);
      metrics.incrementFailed();
    }
  }

  // ---- project data ----

  private void migrateProjectData() {

    Instant cutoffInstant = resolveAttachmentCutoffInstant();
    Timestamp attachmentCutoff = Timestamp.from(cutoffInstant);

    // ── Discovery: query DB for projects that actually have attachments ──

    List<long[]> projectStats = jdbcTemplate.query(discoverProjectsSql, (rs, row) ->
        new long[]{rs.getLong("project_id"), rs.getLong("cnt")}, attachmentCutoff);

    logger.info(">>> PROJECT ATTACHMENTS MIGRATION — DISCOVERY PHASE");
    logger.info("Attachment selection: {} < {} (cutoff instant={}{})",
        attachmentCreationDateColumn, attachmentCutoff, cutoffInstant,
        StringUtils.isBlank(attachmentCutoffRaw) ? ", resolved at project-data start"
            : ", from migration.db.attachment-cutoff");
    logger.info("Found {} projects with attachments in the database:", projectStats.size());

    long totalAttachmentsInDb = 0;
    List<long[]> migratable = new ArrayList<>();

    for (long[] row : projectStats) {
      long projectId = row[0];
      long count = row[1];
      totalAttachmentsInDb += count;
      String srcBucket = bucketPrefix + projectId;
      boolean exists = bucketExists(srcBucket, clients.getSource());
      logger.info("  project_id={} \tattachments={} \tsource_bucket='{}' \texists={}",
          projectId, count, srcBucket, exists);
      if (exists) {
        migratable.add(row);
      }
    }

    int skippedProjects = projectStats.size() - migratable.size();
    long migratableAttachments = migratable.stream().mapToLong(r -> r[1]).sum();
    logger.info("Discovery summary: {} total projects, {} migratable ({} attachments), {} skipped (no source bucket)",
        projectStats.size(), migratable.size(), migratableAttachments, skippedProjects);

    if (migratable.isEmpty()) {
      logger.info("<<< PROJECT ATTACHMENTS MIGRATION FINISHED — nothing to migrate");
      return;
    }

    // ── Migrate: project-level parallelism ──

    logger.info(">>> PROJECT ATTACHMENTS MIGRATION STARTED — {} projects, parallelism={}, batchSize={}",
        migratable.size(), parallelism, batchSize);

    MigrationMetrics globalMetrics = new MigrationMetrics();
    AtomicLong completedProjects = new AtomicLong(0);
    long totalProjects = migratable.size();
    long startTimeMs = System.currentTimeMillis();

    List<CompletableFuture<Void>> futures = new ArrayList<>(migratable.size());
    for (int idx = 0; idx < migratable.size(); idx++) {
      final long projectId = migratable.get(idx)[0];
      final long attachmentCount = migratable.get(idx)[1];
      final int projectIndex = idx + 1;

      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        MigrationMetrics projectMetrics = migrateOneProject(
            projectId, attachmentCount, projectIndex, totalProjects, attachmentCutoff);
        globalMetrics.mergeFrom(projectMetrics);
        long done = completedProjects.incrementAndGet();
        logger.info("  Overall progress: {}/{} projects completed", done, totalProjects);
      }, copyExecutor);
      futures.add(future);
    }

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    long totalElapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000;
    double overallRate = globalMetrics.getProcessed() > 0
        ? (double) globalMetrics.getProcessed() / Math.max(totalElapsedSec, 1) : 0;
    logger.info("<<< PROJECT ATTACHMENTS MIGRATION FINISHED — {} projects in {}s — {} — rate={} attachments/s",
        migratable.size(), totalElapsedSec, globalMetrics.summary(), String.format("%.1f", overallRate));
  }

  /**
   * Migrates all attachments for a single project. Runs in its own thread.
   * Uses keyset pagination (WHERE id > lastId) for stable performance on large tables.
   */
  private MigrationMetrics migrateOneProject(long projectId, long attachmentCount,
      int projectIndex, long totalProjects, Timestamp attachmentCutoff) {
    String srcBucket = bucketPrefix + projectId;
    MigrationMetrics metrics = new MigrationMetrics();
    long startTimeMs = System.currentTimeMillis();

    logger.info(">>> PROJECT {} MIGRATION STARTED [{}/{}] — {} attachments in DB, source bucket '{}'",
        projectId, projectIndex, totalProjects, attachmentCount, srcBucket);

    AttachmentRowUpdateBuffer attachmentRowBuffer =
        attachmentUpdateBatchSize > 1 ? new AttachmentRowUpdateBuffer() : null;

    long lastId = 0;
    int batchNumber = 0;
    AtomicLong processed = new AtomicLong(0);

    while (true) {
      List<Attachment> batch = jdbcTemplate.query(
          selectAttachmentsKeysetSql, new AttachmentRowMapper(),
          projectId, lastId, attachmentCutoff, batchSize);

      if (batch.isEmpty()) {
        break;
      }

      batchNumber++;
      if (intraProjectParallelism <= 1) {
        for (Attachment att : batch) {
          migrateAttachmentSafe(att, srcBucket, projectId, metrics, attachmentRowBuffer, null);
          long p = processed.incrementAndGet();
          if (p % progressLogInterval == 0 || p == 1) {
            logger.info("  Project {} progress: {}/{} attachments processed ({})",
                projectId, p, attachmentCount, metrics.summary());
          }
        }
      } else {
        for (int i = 0; i < batch.size(); i += intraProjectParallelism) {
          int end = Math.min(i + intraProjectParallelism, batch.size());
          List<Attachment> waveAttachments = batch.subList(i, end);
          Map<String, MigrationState> waveCache = buildWaveStateCache(srcBucket, waveAttachments);
          List<CompletableFuture<Void>> wave = new ArrayList<>(end - i);
          for (int j = i; j < end; j++) {
            final Attachment att = batch.get(j);
            final Map<String, MigrationState> cacheForWave = waveCache;
            wave.add(CompletableFuture.runAsync(() -> {
              migrateAttachmentSafe(att, srcBucket, projectId, metrics, attachmentRowBuffer,
                  cacheForWave);
              long p = processed.incrementAndGet();
              if (p % progressLogInterval == 0 || p == 1) {
                logger.info("  Project {} progress: {}/{} attachments processed ({})",
                    projectId, p, attachmentCount, metrics.summary());
              }
            }, intraProjectExecutor));
          }
          CompletableFuture.allOf(wave.toArray(new CompletableFuture[0])).join();
        }
      }

      if (attachmentRowBuffer != null) {
        attachmentRowBuffer.flushAll();
      }

      lastId = batch.get(batch.size() - 1).getId();
      logger.info("  Project {} batch {} complete ({} items, lastId={}) — {}",
          projectId, batchNumber, batch.size(), lastId, metrics.summary());

      if (removeAfterMigration) {
        deleteAttachments(batch, srcBucket);
      }

      if (batch.size() < batchSize) {
        break;
      }
    }

    if (attachmentRowBuffer != null) {
      attachmentRowBuffer.flushAll();
    }

    if (removeAfterMigration) {
      deleteBucket(srcBucket);
    }

    long processedTotal = processed.get();
    long elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000;
    double rate = processedTotal > 0 ? (double) processedTotal / Math.max(elapsedSec, 1) : 0;
    logger.info("<<< PROJECT {} MIGRATION FINISHED [{}/{}] in {}s — {} — rate={} att/s",
        projectId, projectIndex, totalProjects, elapsedSec, metrics.summary(),
        String.format("%.1f", rate));

    return metrics;
  }

  private void migrateAttachmentSafe(Attachment attachment, String srcBucket,
      Long projectId, MigrationMetrics metrics, AttachmentRowUpdateBuffer attachmentRowBuffer,
      Map<String, MigrationState> stateCache) {
    boolean parallelFileAndThumb = intraProjectParallelism > 1
        && attachment.getFileId() != null
        && attachment.getThumbnailId() != null;
    if (parallelFileAndThumb) {
      Executor pool = intraProjectExecutor != null ? intraProjectExecutor : ForkJoinPool.commonPool();
      CompletableFuture<Void> fileFuture = CompletableFuture.runAsync(() ->
          migrateOneFile(attachment, attachment.getFileId(),
              UPDATE_ATTACHMENT_FILE_ID, srcBucket, projectId, "file", metrics, attachmentRowBuffer,
              stateCache), pool);
      CompletableFuture<Void> thumbFuture = CompletableFuture.runAsync(() ->
          migrateOneFile(attachment, attachment.getThumbnailId(),
              UPDATE_ATTACHMENT_THUMBNAIL_ID, srcBucket, projectId, "thumbnail", metrics,
              attachmentRowBuffer, stateCache), pool);
      CompletableFuture.allOf(fileFuture, thumbFuture).join();
    } else {
      if (attachment.getFileId() != null) {
        migrateOneFile(attachment, attachment.getFileId(),
            UPDATE_ATTACHMENT_FILE_ID, srcBucket, projectId, "file", metrics, attachmentRowBuffer,
            stateCache);
      }
      if (attachment.getThumbnailId() != null) {
        migrateOneFile(attachment, attachment.getThumbnailId(),
            UPDATE_ATTACHMENT_THUMBNAIL_ID, srcBucket, projectId, "thumbnail", metrics,
            attachmentRowBuffer, stateCache);
      }
    }
  }

  private void migrateOneFile(Attachment attachment, String encodedFilePath,
      String updateSql, String srcBucket, Long projectId,
      String fileType, MigrationMetrics metrics, AttachmentRowUpdateBuffer attachmentRowBuffer,
      Map<String, MigrationState> stateCache) {
    String filePath = decode(encodedFilePath);

    if (PROJECT_PREFIX.equals(getPathFirstPart(filePath) + "/")) {
      logger.debug("SKIP_ALREADY_MIGRATED: attachment_id={} project={} type={} — path already has '{}' prefix",
          attachment.getId(), projectId, fileType, PROJECT_PREFIX);
      metrics.incrementSkipped();
      return;
    }

    String srcKey = cutPath(filePath);
    String destKey = PROJECT_PREFIX + filePath;

    Optional<MigrationState> existing = lookupExistingState(srcBucket, srcKey, stateCache);
    if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
      logger.debug("SKIP_STATE_COMPLETED: attachment_id={} project={} type={} s3://{}/{}",
          attachment.getId(), projectId, fileType, srcBucket, srcKey);
      metrics.incrementSkipped();
      return;
    }

    Optional<Long> srcLen = Optional.empty();
    if (headSourceBeforeCopy || maxInMemoryCopyBytes > 0) {
      srcLen = tryHeadSourceContentLength(srcBucket, srcKey);
      if (!srcLen.isPresent()) {
        logger.warn("SOURCE_NOT_FOUND: attachment_id={} project={} type={} — "
                + "object does not exist at s3://{}/{} — skipping (DB record exists but storage object is missing)",
            attachment.getId(), projectId, fileType, srcBucket, srcKey);
        MigrationState state = existing.orElseGet(() ->
            new MigrationState(MIGRATION_TYPE, fileType, attachment.getId(),
                srcBucket, srcKey, singleBucketName, destKey));
        state.setStatus("SKIPPED");
        state.setErrorMessage("Source object not found in storage");
        stateRepo.save(state);
        putStateInCache(stateCache, srcKey, state);
        metrics.incrementSourceNotFound();
        return;
      }
    }

    MigrationState state = existing.orElseGet(() ->
        new MigrationState(MIGRATION_TYPE, fileType, attachment.getId(),
            srcBucket, srcKey, singleBucketName, destKey));
    state.setStatus("IN_PROGRESS");
    stateRepo.save(state);
    putStateInCache(stateCache, srcKey, state);

    CopyResult result = copyMinioToS3WithVerify(srcBucket, srcKey, singleBucketName, destKey, srcLen);

    if (result.isSuccess()) {
      metrics.incrementCopied();
      logger.debug("COPIED: attachment_id={} project={} type={} — s3://{}/{} → s3://{}/{} ({}B)",
          attachment.getId(), projectId, fileType,
          srcBucket, srcKey, singleBucketName, destKey, result.getDestSize());
      if (attachmentRowBuffer == null) {
        jdbcTemplate.update(updateSql, encode(destKey), attachment.getId());
        state.setStatus("COMPLETED");
        state.setErrorMessage(null);
        state.setAttempts(state.getAttempts() + 1);
        stateRepo.save(state);
        putStateInCache(stateCache, srcKey, state);
      } else {
        attachmentRowBuffer.enqueueSuccess(updateSql, attachment.getId(), encode(destKey), state);
      }
    } else if (result.isMissingSource()) {
      logger.warn("SOURCE_NOT_FOUND: attachment_id={} project={} type={} — "
              + "object missing at s3://{}/{} after copy attempt",
          attachment.getId(), projectId, fileType, srcBucket, srcKey);
      state.setStatus("SKIPPED");
      state.setErrorMessage("Source object not found in storage");
      stateRepo.save(state);
      putStateInCache(stateCache, srcKey, state);
      metrics.incrementSourceNotFound();
    } else {
      logger.error("MIGRATION_FAILED: attachment_id={} project={} type={} — "
              + "source=s3://{}/{} dest=s3://{}/{} — error: {}",
          attachment.getId(), projectId, fileType,
          srcBucket, srcKey, singleBucketName, destKey, result.getErrorMessage());
      state.setStatus("FAILED");
      state.setAttempts(state.getAttempts() + maxRetries);
      state.setErrorMessage(result.getErrorMessage());
      stateRepo.save(state);
      putStateInCache(stateCache, srcKey, state);
      metrics.incrementFailed();
    }
  }

  // ---- user photos ----

  private void migrateUserPhotos() {
    MigrationMetrics metrics = new MigrationMetrics();
    List<User> users = jdbcTemplate.query(SELECT_ALL_USERS, new UserRowMapper());
    String srcBucket = bucketPrefix + USERS_MULTIBUCKET_NAME;
    logger.info(">>> USER PHOTOS MIGRATION STARTED — {} users in DB, source bucket '{}', parallelism={}",
        users.size(), srcBucket, parallelism);

    AtomicLong processedUsers = new AtomicLong(0);
    long totalUsers = users.size();

    List<CompletableFuture<Void>> futures = new ArrayList<>(users.size());
    for (User user : users) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        if (StringUtils.isNotBlank(user.getAttachment())) {
          migratePhotoSafe(user.getAttachment(), user.getId(),
              UPDATE_USER_PHOTO, srcBucket, "photo", metrics);
        }
        if (StringUtils.isNotBlank(user.getAttachmentThumbnail())) {
          migratePhotoSafe(user.getAttachmentThumbnail(), user.getId(),
              UPDATE_USER_PHOTO_THUMBNAIL, srcBucket, "photo_thumbnail", metrics);
        }
        long pos = processedUsers.incrementAndGet();
        if (pos % 500 == 0 || pos == 1) {
          logger.info("  User photos progress: {}/{} users processed ({})",
              pos, totalUsers, metrics.summary());
        }
      }, copyExecutor);
      futures.add(future);
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    if (removeAfterMigration) {
      deleteBucket(srcBucket);
    }
    logger.info("<<< USER PHOTOS MIGRATION FINISHED — {}", metrics.summary());
  }

  private void migratePhotoSafe(String encodedPath, Long userId, String sql,
      String srcBucket, String fileType, MigrationMetrics metrics) {
    String decodedPath = decode(encodedPath);
    if (USERS_SINGLEBUCKET_PREFIX.equals(getPathFirstPart(decodedPath) + "/")) {
      logger.debug("SKIP_ALREADY_MIGRATED: user_id={} type={} — path already has '{}' prefix",
          userId, fileType, USERS_SINGLEBUCKET_PREFIX);
      metrics.incrementSkipped();
      return;
    }

    String srcKey = cutPath(decodedPath);
    String destKey = USERS_SINGLEBUCKET_PREFIX + PHOTOS_PREFIX + srcKey;

    Optional<MigrationState> existing =
        stateRepo.findByKey(MIGRATION_TYPE, srcBucket, srcKey);
    if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
      logger.debug("SKIP_STATE_COMPLETED: user_id={} type={} s3://{}/{}",
          userId, fileType, srcBucket, srcKey);
      metrics.incrementSkipped();
      return;
    }

    Optional<Long> srcLen = Optional.empty();
    if (headSourceBeforeCopy || maxInMemoryCopyBytes > 0) {
      srcLen = tryHeadSourceContentLength(srcBucket, srcKey);
      if (!srcLen.isPresent()) {
        logger.warn("SOURCE_NOT_FOUND: user_id={} type={} — "
                + "object does not exist at s3://{}/{} — skipping",
            userId, fileType, srcBucket, srcKey);
        MigrationState state = existing.orElseGet(() ->
            new MigrationState(MIGRATION_TYPE, fileType, userId,
                srcBucket, srcKey, singleBucketName, destKey));
        state.setStatus("SKIPPED");
        state.setErrorMessage("Source object not found in storage");
        stateRepo.save(state);
        metrics.incrementSourceNotFound();
        return;
      }
    }

    MigrationState state = existing.orElseGet(() ->
        new MigrationState(MIGRATION_TYPE, fileType, userId,
            srcBucket, srcKey, singleBucketName, destKey));
    state.setStatus("IN_PROGRESS");
    stateRepo.save(state);

    CopyResult result = copyMinioToS3WithVerify(srcBucket, srcKey, singleBucketName, destKey, srcLen);

    if (result.isSuccess()) {
      jdbcTemplate.update(sql, encode(destKey), userId);
      state.setStatus("COMPLETED");
      state.setErrorMessage(null);
      state.setAttempts(state.getAttempts() + 1);
      stateRepo.save(state);
      metrics.incrementCopied();
      logger.debug("COPIED: user_id={} type={} — s3://{}/{} → s3://{}/{} ({}B)",
          userId, fileType, srcBucket, srcKey, singleBucketName, destKey, result.getDestSize());
    } else if (result.isMissingSource()) {
      logger.warn("SOURCE_NOT_FOUND: user_id={} type={} — object missing at s3://{}/{} after copy attempt",
          userId, fileType, srcBucket, srcKey);
      state.setStatus("SKIPPED");
      state.setErrorMessage("Source object not found in storage");
      stateRepo.save(state);
      metrics.incrementSourceNotFound();
    } else {
      logger.error("MIGRATION_FAILED: user_id={} type={} — "
              + "source=s3://{}/{} dest=s3://{}/{} — error: {}",
          userId, fileType, srcBucket, srcKey, singleBucketName, destKey, result.getErrorMessage());
      state.setStatus("FAILED");
      state.setAttempts(state.getAttempts() + maxRetries);
      state.setErrorMessage(result.getErrorMessage());
      stateRepo.save(state);
      metrics.incrementFailed();
    }
  }

  // ---- plugins ----

  private void migratePlugins() {
    MigrationMetrics metrics = new MigrationMetrics();
    List<Plugin> plugins = jdbcTemplate.query(SELECT_ALL_PLUGINS, new PluginRowMapper());
    logger.info(">>> PLUGIN MIGRATION STARTED — {} plugins in DB", plugins.size());

    for (Plugin plugin : plugins) {
      String pluginPath = getPluginPath(plugin);
      if (pluginPath == null || isPluginAlreadyMigrated(pluginPath)) {
        metrics.incrementSkipped();
        continue;
      }
      String sourceBucket = getSourceBucketForMigration();
      if (sourceBucket == null) {
        logger.warn("No source bucket found for plugin id={}", plugin.getId());
        continue;
      }
      String destKey = PLUGINS_PREFIX + pluginPath;

      trackAndCopy("plugin", plugin.getId(), sourceBucket, pluginPath,
          singleBucketName, destKey, metrics);

      JSONObject detailsJson = new JSONObject(plugin.getDetails());
      detailsJson.getJSONObject("details").put("id", destKey);
      jdbcTemplate.update(UPDATE_PLUGIN_DETAILS, detailsJson.toString(), plugin.getId());

      if (removeAfterMigration) {
        deleteFile(pluginPath, sourceBucket);
      }
    }
    if (removeAfterMigration) {
      deleteBucket(defaultBucketName);
      deleteBucket(bucketPrefix + defaultBucketName);
    }
    logger.info("<<< PLUGIN MIGRATION FINISHED — {}", metrics.summary());
  }

  // ---- integration secrets ----

  private void migrateIntegrationSecrets() {
    MigrationMetrics metrics = new MigrationMetrics();
    String srcBucket = bucketPrefix + secretsPath;
    logger.info(">>> INTEGRATION SECRETS MIGRATION STARTED — source bucket '{}'", srcBucket);

    trackAndCopy("secret", null, srcBucket, "secret-integration-salt",
        singleBucketName, SECRETS_PREFIX + "secret-integration-salt", metrics);

    trackAndCopy("secret", null, srcBucket, "migration",
        singleBucketName, SECRETS_PREFIX + "migration", metrics);

    if (removeAfterMigration) {
      deleteFile("secret-integration-salt", srcBucket);
      deleteFile("migration", srcBucket);
      deleteBucket(srcBucket);
    }
    logger.info("<<< INTEGRATION SECRETS MIGRATION FINISHED — {}", metrics.summary());
  }

  // ---- retry failed items from previous runs ----

  private void retryFailedItems() {
    List<MigrationState> failed = stateRepo.findByStatus(MIGRATION_TYPE, "FAILED");
    if (failed.isEmpty()) {
      logger.info("No failed items to retry");
      return;
    }
    logger.info("Retrying {} previously failed items, parallelism={}", failed.size(), parallelism);
    AtomicLong recovered = new AtomicLong(0);
    AtomicLong stillFailed = new AtomicLong(0);
    AtomicLong processed = new AtomicLong(0);
    long total = failed.size();

    List<CompletableFuture<Void>> futures = new ArrayList<>(failed.size());
    for (MigrationState item : failed) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        Optional<Long> srcLen = Optional.empty();
        if (headSourceBeforeCopy || maxInMemoryCopyBytes > 0) {
          srcLen = tryHeadSourceContentLength(item.getSourceBucket(), item.getSourceKey());
          if (!srcLen.isPresent()) {
            logger.warn("RETRY_SOURCE_NOT_FOUND: entityType={} entityId={} — "
                    + "s3://{}/{} still does not exist — marking as SKIPPED",
                item.getEntityType(), item.getEntityId(),
                item.getSourceBucket(), item.getSourceKey());
            item.setStatus("SKIPPED");
            item.setErrorMessage("Source object not found on retry");
            stateRepo.save(item);
            stillFailed.incrementAndGet();
            long pos = processed.incrementAndGet();
            if (pos % 100 == 0 || pos == 1) {
              logger.info("  Retry progress: {}/{} (recovered={}, still_failed={})",
                  pos, total, recovered.get(), stillFailed.get());
            }
            return;
          }
        }
        CopyResult result = copyMinioToS3WithVerify(
            item.getSourceBucket(), item.getSourceKey(),
            item.getDestBucket(), item.getDestKey(), srcLen);
        if (result.isSuccess()) {
          item.setStatus("COMPLETED");
          item.setErrorMessage(null);
          stateRepo.save(item);
          recovered.incrementAndGet();
          logger.info("RETRY_RECOVERED: entityType={} entityId={} — s3://{}/{}",
              item.getEntityType(), item.getEntityId(),
              item.getSourceBucket(), item.getSourceKey());
        } else if (result.isMissingSource()) {
          logger.warn("RETRY_SOURCE_NOT_FOUND: entityType={} entityId={} — "
                  + "s3://{}/{} missing on copy — marking as SKIPPED",
              item.getEntityType(), item.getEntityId(),
              item.getSourceBucket(), item.getSourceKey());
          item.setStatus("SKIPPED");
          item.setErrorMessage("Source object not found on retry");
          stateRepo.save(item);
          stillFailed.incrementAndGet();
        } else {
          item.setAttempts(item.getAttempts() + maxRetries);
          item.setErrorMessage(result.getErrorMessage());
          stateRepo.save(item);
          stillFailed.incrementAndGet();
          logger.error("RETRY_STILL_FAILED: entityType={} entityId={} — s3://{}/{} — {}",
              item.getEntityType(), item.getEntityId(),
              item.getSourceBucket(), item.getSourceKey(), result.getErrorMessage());
        }
        long pos = processed.incrementAndGet();
        if (pos % 100 == 0 || pos == 1) {
          logger.info("  Retry progress: {}/{} (recovered={}, still_failed={})",
              pos, total, recovered.get(), stillFailed.get());
        }
      }, copyExecutor);
      futures.add(future);
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    logger.info("Retry complete: {}/{} recovered, {}/{} still failed",
        recovered.get(), total, stillFailed.get(), total);
  }

  // ---- final summary ----

  private void logFinalSummary() {
    logger.info("=== Single-bucket migration summary ===");
    stateRepo.logSummary(MIGRATION_TYPE, logger);

    List<MigrationState> failures = stateRepo.findByStatus(MIGRATION_TYPE, "FAILED");
    if (!failures.isEmpty()) {
      logger.warn("=== {} items still in FAILED state ===", failures.size());
      for (MigrationState f : failures) {
        logger.warn("  FAILED: entityType={} entityId={} source=s3://{}/{} error={}",
            f.getEntityType(), f.getEntityId(),
            f.getSourceBucket(), f.getSourceKey(), f.getErrorMessage());
      }
    }

    List<MigrationState> skipped = stateRepo.findByStatus(MIGRATION_TYPE, "SKIPPED");
    if (!skipped.isEmpty()) {
      logger.info("=== {} items SKIPPED (source not found in storage) ===", skipped.size());
      int shown = 0;
      for (MigrationState s : skipped) {
        if (shown++ < 50) {
          logger.info("  SKIPPED: entityType={} entityId={} source=s3://{}/{}",
              s.getEntityType(), s.getEntityId(), s.getSourceBucket(), s.getSourceKey());
        }
      }
      if (skipped.size() > 50) {
        logger.info("  ... and {} more (query migration_state table for full list)",
            skipped.size() - 50);
      }
    }
  }

  /**
   * Prefetches {@link MigrationState} rows for all file/thumbnail keys in a parallel wave to avoid
   * per-row SELECT contention on {@code migration_state}.
   */
  private Map<String, MigrationState> buildWaveStateCache(String srcBucket, List<Attachment> wave) {
    Set<String> keys = new LinkedHashSet<>();
    for (Attachment att : wave) {
      collectPrefetchKeysForAttachment(att, keys);
    }
    if (keys.isEmpty()) {
      return new HashMap<>();
    }
    return new HashMap<>(stateRepo.findByBucketAndKeys(MIGRATION_TYPE, srcBucket, keys));
  }

  private void collectPrefetchKeysForAttachment(Attachment att, Set<String> keys) {
    extractSrcKeyIfNeedsMigration(att.getFileId()).ifPresent(keys::add);
    extractSrcKeyIfNeedsMigration(att.getThumbnailId()).ifPresent(keys::add);
  }

  private Optional<String> extractSrcKeyIfNeedsMigration(String encodedFilePath) {
    if (encodedFilePath == null) {
      return Optional.empty();
    }
    String filePath = decode(encodedFilePath);
    if (PROJECT_PREFIX.equals(getPathFirstPart(filePath) + "/")) {
      return Optional.empty();
    }
    return Optional.of(cutPath(filePath));
  }

  private Optional<MigrationState> lookupExistingState(String srcBucket, String srcKey,
      Map<String, MigrationState> stateCache) {
    if (stateCache != null) {
      MigrationState cached = stateCache.get(srcKey);
      if (cached != null) {
        return Optional.of(cached);
      }
    }
    Optional<MigrationState> fromDb = stateRepo.findByKey(MIGRATION_TYPE, srcBucket, srcKey);
    if (stateCache != null && fromDb.isPresent()) {
      stateCache.put(srcKey, fromDb.get());
    }
    return fromDb;
  }

  // ---- helpers ----

  private String getPluginPath(Plugin plugin) {
    if (StringUtils.isEmpty(plugin.getDetails())) {
      return null;
    }
    JSONObject detailsJson = new JSONObject(plugin.getDetails());
    if (!detailsJson.getJSONObject("details").has("id")) {
      return null;
    }
    return detailsJson.getJSONObject("details").getString("id");
  }

  private boolean isPluginAlreadyMigrated(String pluginPath) {
    return PLUGINS_PREFIX.equals(getPathFirstPart(pluginPath) + "/");
  }

  private String getSourceBucketForMigration() {
    if (bucketExists(defaultBucketName, clients.getSource())) {
      return defaultBucketName;
    }
    if (bucketExists(bucketPrefix + defaultBucketName, clients.getSource())) {
      return bucketPrefix + defaultBucketName;
    }
    return null;
  }

  private String getPathFirstPart(String filePath) {
    return String.valueOf(Paths.get(filePath).subpath(0, 1));
  }

  private String cutPath(String filePath) {
    Path path = Paths.get(filePath);
    return String.valueOf(path.subpath(1, path.getNameCount()));
  }

  private String decode(String data) {
    return StringUtils.isEmpty(data) ? data
        : new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
  }

  private String encode(String data) {
    return StringUtils.isEmpty(data) ? data
        : new String(Base64.getUrlEncoder().encode(data.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8);
  }

  private boolean bucketExists(String bucketName, S3AsyncClient client) {
    try {
      client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build()).join();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private void deleteFile(String filePath, String bucketName) {
    try {
      clients.getSource().deleteObject(
          DeleteObjectRequest.builder().bucket(bucketName).key(filePath).build()).join();
    } catch (Exception e) {
      logger.warn("Failed to delete s3://{}/{}: {}", bucketName, filePath, e.getMessage());
    }
  }

  private void deleteBucket(String bucketName) {
    try {
      clients.getSource().deleteBucket(
          DeleteBucketRequest.builder().bucket(bucketName).build()).join();
    } catch (Exception e) {
      logger.warn("Bucket '{}' not deleted: {}", bucketName, e.getMessage());
    }
  }

  private void deleteAttachments(List<Attachment> files, String bucketName) {
    List<ObjectIdentifier> ids = files.stream()
        .map(Attachment::getFileId).filter(Objects::nonNull)
        .map(f -> ObjectIdentifier.builder().key(cutPath(decode(f))).build())
        .collect(Collectors.toList());
    ids.addAll(files.stream()
        .map(Attachment::getThumbnailId).filter(Objects::nonNull)
        .map(t -> ObjectIdentifier.builder().key(cutPath(decode(t))).build())
        .collect(Collectors.toList()));

    for (List<ObjectIdentifier> partition : Iterables.partition(ids, 1000)) {
      try {
        clients.getSource().deleteObjects(DeleteObjectsRequest.builder().bucket(bucketName)
            .delete(Delete.builder().objects(partition).build()).build()).join();
      } catch (Exception e) {
        logger.warn("Batch delete failed for '{}': {}", bucketName, e.getMessage());
      }
    }
  }

  /**
   * Batches {@code UPDATE public.attachment SET file_id / thumbnail_id} after successful S3 copies.
   * {@link MigrationState} stays IN_PROGRESS until the JDBC batch is applied; then rows are COMPLETED.
   * Queues flush when they reach the configured batch size and via {@link #flushAll()} before source S3 deletes.
   */
  private final class AttachmentRowUpdateBuffer {

    private final class QueuedAttachmentRow {
      final long attachmentId;
      final String encodedDest;
      final MigrationState state;

      QueuedAttachmentRow(long attachmentId, String encodedDest, MigrationState state) {
        this.attachmentId = attachmentId;
        this.encodedDest = encodedDest;
        this.state = state;
      }
    }

    private final List<QueuedAttachmentRow> fileRows = new ArrayList<>();
    private final List<QueuedAttachmentRow> thumbRows = new ArrayList<>();

    synchronized void enqueueSuccess(String updateSql, long attachmentId, String encodedDest,
        MigrationState state) {
      if (UPDATE_ATTACHMENT_FILE_ID.equals(updateSql)) {
        fileRows.add(new QueuedAttachmentRow(attachmentId, encodedDest, state));
        if (fileRows.size() >= attachmentUpdateBatchSize) {
          flushFiles();
        }
      } else if (UPDATE_ATTACHMENT_THUMBNAIL_ID.equals(updateSql)) {
        thumbRows.add(new QueuedAttachmentRow(attachmentId, encodedDest, state));
        if (thumbRows.size() >= attachmentUpdateBatchSize) {
          flushThumbs();
        }
      } else {
        throw new IllegalStateException("Unexpected attachment UPDATE SQL: " + updateSql);
      }
    }

    synchronized void flushAll() {
      flushFiles();
      flushThumbs();
    }

    private synchronized void flushFiles() {
      if (fileRows.isEmpty()) {
        return;
      }
      List<QueuedAttachmentRow> batch = new ArrayList<>(fileRows);
      fileRows.clear();
      jdbcTemplate.batchUpdate(UPDATE_ATTACHMENT_FILE_ID, new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
          QueuedAttachmentRow q = batch.get(i);
          ps.setString(1, q.encodedDest);
          ps.setLong(2, q.attachmentId);
        }

        @Override
        public int getBatchSize() {
          return batch.size();
        }
      });
      for (QueuedAttachmentRow q : batch) {
        q.state.setStatus("COMPLETED");
        q.state.setErrorMessage(null);
        q.state.setAttempts(q.state.getAttempts() + 1);
        stateRepo.save(q.state);
      }
    }

    private synchronized void flushThumbs() {
      if (thumbRows.isEmpty()) {
        return;
      }
      List<QueuedAttachmentRow> batch = new ArrayList<>(thumbRows);
      thumbRows.clear();
      jdbcTemplate.batchUpdate(UPDATE_ATTACHMENT_THUMBNAIL_ID, new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
          QueuedAttachmentRow q = batch.get(i);
          ps.setString(1, q.encodedDest);
          ps.setLong(2, q.attachmentId);
        }

        @Override
        public int getBatchSize() {
          return batch.size();
        }
      });
      for (QueuedAttachmentRow q : batch) {
        q.state.setStatus("COMPLETED");
        q.state.setErrorMessage(null);
        q.state.setAttempts(q.state.getAttempts() + 1);
        stateRepo.save(q.state);
      }
    }
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
