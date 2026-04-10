package com.epam.reportportal.service.impl;

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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.Copy;
import software.amazon.awssdk.transfer.s3.model.CopyRequest;

/**
 * Migrates attachments from per-project buckets (multi-bucket) into a single destination bucket.
 * Only copies objects that exist in both the database and the source storage.
 * Uses atomic copy-verify-then-update pattern with retry and persistent state tracking.
 */
@Service
@Order(3)
@ConditionalOnProperty(name = "rp.singlebucket.migration", havingValue = "true")
public class SingleBucketMigrationServiceImpl implements MigrationService {

  private static final String MIGRATION_TYPE = "SINGLE_BUCKET";

  private static final String DISCOVER_PROJECTS =
      "SELECT project_id, COUNT(id) AS cnt FROM public.attachment"
          + " GROUP BY project_id ORDER BY cnt DESC";

  private static final String SELECT_ATTACHMENTS_KEYSET =
      "SELECT id, file_id, thumbnail_id, project_id FROM public.attachment"
          + " WHERE project_id = ? AND id > ? ORDER BY id LIMIT ?";

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

  private final S3AsyncClient s3Client;
  private final S3TransferManager transferManager;
  private final JdbcTemplate jdbcTemplate;
  private final MigrationStateRepository stateRepo;
  private final ExecutorService copyExecutor;

  public SingleBucketMigrationServiceImpl(
      S3AsyncClient s3Client,
      JdbcTemplate jdbcTemplate,
      MigrationStateRepository stateRepo,
      @Value("${datastore.bucketPrefix}") String bucketPrefix,
      @Value("${datastore.defaultBucketName}") String defaultBucketName,
      @Value("${datastore.singleBucketName}") String singleBucketName,
      @Value("${datastore.secrets.path}") String secretsPath,
      @Value("#{new Boolean('${datastore.remove.after.migration}')}") Boolean removeAfterMigration,
      @Value("${migration.batch.size:200000}") int batchSize,
      @Value("${migration.parallelism:8}") int parallelism,
      @Value("${migration.retry.max:3}") int maxRetries,
      @Value("${migration.retry.base-delay-ms:1000}") long retryBaseDelayMs,
      @Value("${migration.progress.log-interval:1000}") int progressLogInterval) {
    this.jdbcTemplate = jdbcTemplate;
    this.stateRepo = stateRepo;
    this.s3Client = s3Client;
    this.transferManager = S3TransferManager.builder().s3Client(s3Client).build();
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
    this.copyExecutor = Executors.newFixedThreadPool(parallelism);
    logger.info("Migration configured: batchSize={}, parallelism={}, maxRetries={}, retryBaseDelayMs={}, progressLogInterval={}",
        batchSize, parallelism, maxRetries, retryBaseDelayMs, progressLogInterval);
  }

  @Override
  @LogMigration("Migration from multi-bucket to single-bucket")
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
    try {
      if (!copyExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        logger.warn("Copy executor did not terminate in 30s, forcing shutdown");
        copyExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      copyExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  // ---- destination bucket ----

  private void ensureDestinationBucket() {
    if (!bucketExists(singleBucketName)) {
      CreateBucketResponse resp = s3Client.createBucket(
          CreateBucketRequest.builder().bucket(singleBucketName).build()
      ).join();
      if (resp.sdkHttpResponse().isSuccessful()) {
        logger.info("Created destination bucket '{}'", singleBucketName);
      } else {
        throw new IllegalStateException(
            "Failed to create destination bucket: " + singleBucketName);
      }
    }
  }

  // ---- source existence check ----

  private boolean sourceObjectExists(String bucket, String key) {
    try {
      s3Client.headObject(
          HeadObjectRequest.builder().bucket(bucket).key(key).build()
      ).join();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // ---- core copy with verify + retry ----

  private CopyResult copyWithVerify(String srcBucket, String srcKey,
      String destBucket, String destKey) {
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        CopyObjectRequest copyReq = CopyObjectRequest.builder()
            .sourceBucket(srcBucket).sourceKey(srcKey)
            .destinationBucket(destBucket).destinationKey(destKey)
            .build();

        Copy copy = transferManager.copy(
            CopyRequest.builder().copyObjectRequest(copyReq).build());
        copy.completionFuture().join();

        HeadObjectResponse head = s3Client.headObject(
            HeadObjectRequest.builder().bucket(destBucket).key(destKey).build()
        ).join();

        return CopyResult.success(head.contentLength());

      } catch (Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (attempt < maxRetries) {
          long delay = retryBaseDelayMs * (1L << (attempt - 1));
          logger.warn("Copy attempt {}/{} failed for s3://{}/{} : {} — retrying in {}ms",
              attempt, maxRetries, srcBucket, srcKey, msg, delay);
          sleep(delay);
        } else {
          logger.error("Copy FAILED after {} attempts for s3://{}/{} : {}",
              maxRetries, srcBucket, srcKey, msg);
          return CopyResult.failure(msg);
        }
      }
    }
    return CopyResult.failure("exhausted retries");
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

    if (!sourceObjectExists(srcBucket, srcKey)) {
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

    MigrationState state = existing.orElseGet(() ->
        new MigrationState(MIGRATION_TYPE, entityType, entityId,
            srcBucket, srcKey, destBucket, destKey));
    state.setStatus("IN_PROGRESS");
    stateRepo.save(state);

    CopyResult result = copyWithVerify(srcBucket, srcKey, destBucket, destKey);

    if (result.isSuccess()) {
      state.setStatus("COMPLETED");
      state.setAttempts(state.getAttempts() + 1);
      state.setErrorMessage(null);
      stateRepo.save(state);
      metrics.incrementCopied();
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

    // ── Discovery: query DB for projects that actually have attachments ──

    List<long[]> projectStats = jdbcTemplate.query(DISCOVER_PROJECTS, (rs, row) ->
        new long[]{rs.getLong("project_id"), rs.getLong("cnt")});

    logger.info(">>> PROJECT ATTACHMENTS MIGRATION — DISCOVERY PHASE");
    logger.info("Found {} projects with attachments in the database:", projectStats.size());

    long totalAttachmentsInDb = 0;
    List<long[]> migratable = new ArrayList<>();

    for (long[] row : projectStats) {
      long projectId = row[0];
      long count = row[1];
      totalAttachmentsInDb += count;
      String srcBucket = bucketPrefix + projectId;
      boolean exists = bucketExists(srcBucket);
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
            projectId, attachmentCount, projectIndex, totalProjects);
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
      int projectIndex, long totalProjects) {
    String srcBucket = bucketPrefix + projectId;
    MigrationMetrics metrics = new MigrationMetrics();
    long startTimeMs = System.currentTimeMillis();

    logger.info(">>> PROJECT {} MIGRATION STARTED [{}/{}] — {} attachments in DB, source bucket '{}'",
        projectId, projectIndex, totalProjects, attachmentCount, srcBucket);

    long lastId = 0;
    int batchNumber = 0;
    long processed = 0;

    while (true) {
      List<Attachment> batch = jdbcTemplate.query(
          SELECT_ATTACHMENTS_KEYSET, new AttachmentRowMapper(),
          projectId, lastId, batchSize);

      if (batch.isEmpty()) {
        break;
      }

      batchNumber++;
      for (Attachment att : batch) {
        migrateAttachmentSafe(att, srcBucket, projectId, metrics);
        processed++;
        if (processed % progressLogInterval == 0 || processed == 1) {
          logger.info("  Project {} progress: {}/{} attachments processed ({})",
              projectId, processed, attachmentCount, metrics.summary());
        }
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

    if (removeAfterMigration) {
      deleteBucket(srcBucket);
    }

    long elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000;
    double rate = processed > 0 ? (double) processed / Math.max(elapsedSec, 1) : 0;
    logger.info("<<< PROJECT {} MIGRATION FINISHED [{}/{}] in {}s — {} — rate={} att/s",
        projectId, projectIndex, totalProjects, elapsedSec, metrics.summary(),
        String.format("%.1f", rate));

    return metrics;
  }

  private void migrateAttachmentSafe(Attachment attachment, String srcBucket,
      Long projectId, MigrationMetrics metrics) {
    if (attachment.getFileId() != null) {
      migrateOneFile(attachment, attachment.getFileId(),
          UPDATE_ATTACHMENT_FILE_ID, srcBucket, projectId, "file", metrics);
    }
    if (attachment.getThumbnailId() != null) {
      migrateOneFile(attachment, attachment.getThumbnailId(),
          UPDATE_ATTACHMENT_THUMBNAIL_ID, srcBucket, projectId, "thumbnail", metrics);
    }
  }

  private void migrateOneFile(Attachment attachment, String encodedFilePath,
      String updateSql, String srcBucket, Long projectId,
      String fileType, MigrationMetrics metrics) {
    String filePath = decode(encodedFilePath);

    if (PROJECT_PREFIX.equals(getPathFirstPart(filePath) + "/")) {
      logger.debug("SKIP_ALREADY_MIGRATED: attachment_id={} project={} type={} — path already has '{}' prefix",
          attachment.getId(), projectId, fileType, PROJECT_PREFIX);
      metrics.incrementSkipped();
      return;
    }

    String srcKey = cutPath(filePath);
    String destKey = PROJECT_PREFIX + filePath;

    Optional<MigrationState> existing =
        stateRepo.findByKey(MIGRATION_TYPE, srcBucket, srcKey);
    if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
      logger.debug("SKIP_STATE_COMPLETED: attachment_id={} project={} type={} s3://{}/{}",
          attachment.getId(), projectId, fileType, srcBucket, srcKey);
      metrics.incrementSkipped();
      return;
    }

    if (!sourceObjectExists(srcBucket, srcKey)) {
      logger.warn("SOURCE_NOT_FOUND: attachment_id={} project={} type={} — "
              + "object does not exist at s3://{}/{} — skipping (DB record exists but storage object is missing)",
          attachment.getId(), projectId, fileType, srcBucket, srcKey);
      MigrationState state = existing.orElseGet(() ->
          new MigrationState(MIGRATION_TYPE, fileType, attachment.getId(),
              srcBucket, srcKey, singleBucketName, destKey));
      state.setStatus("SKIPPED");
      state.setErrorMessage("Source object not found in storage");
      stateRepo.save(state);
      metrics.incrementSourceNotFound();
      return;
    }

    MigrationState state = existing.orElseGet(() ->
        new MigrationState(MIGRATION_TYPE, fileType, attachment.getId(),
            srcBucket, srcKey, singleBucketName, destKey));
    state.setStatus("IN_PROGRESS");
    stateRepo.save(state);

    CopyResult result = copyWithVerify(srcBucket, srcKey, singleBucketName, destKey);

    if (result.isSuccess()) {
      jdbcTemplate.update(updateSql, encode(destKey), attachment.getId());
      state.setStatus("COMPLETED");
      state.setErrorMessage(null);
      state.setAttempts(state.getAttempts() + 1);
      stateRepo.save(state);
      metrics.incrementCopied();
      logger.debug("COPIED: attachment_id={} project={} type={} — s3://{}/{} → s3://{}/{} ({}B)",
          attachment.getId(), projectId, fileType,
          srcBucket, srcKey, singleBucketName, destKey, result.getDestSize());
    } else {
      logger.error("MIGRATION_FAILED: attachment_id={} project={} type={} — "
              + "source=s3://{}/{} dest=s3://{}/{} — error: {}",
          attachment.getId(), projectId, fileType,
          srcBucket, srcKey, singleBucketName, destKey, result.getErrorMessage());
      state.setStatus("FAILED");
      state.setAttempts(state.getAttempts() + maxRetries);
      state.setErrorMessage(result.getErrorMessage());
      stateRepo.save(state);
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

    if (!sourceObjectExists(srcBucket, srcKey)) {
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

    MigrationState state = existing.orElseGet(() ->
        new MigrationState(MIGRATION_TYPE, fileType, userId,
            srcBucket, srcKey, singleBucketName, destKey));
    state.setStatus("IN_PROGRESS");
    stateRepo.save(state);

    CopyResult result = copyWithVerify(srcBucket, srcKey, singleBucketName, destKey);

    if (result.isSuccess()) {
      jdbcTemplate.update(sql, encode(destKey), userId);
      state.setStatus("COMPLETED");
      state.setErrorMessage(null);
      state.setAttempts(state.getAttempts() + 1);
      stateRepo.save(state);
      metrics.incrementCopied();
      logger.debug("COPIED: user_id={} type={} — s3://{}/{} → s3://{}/{} ({}B)",
          userId, fileType, srcBucket, srcKey, singleBucketName, destKey, result.getDestSize());
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
        if (!sourceObjectExists(item.getSourceBucket(), item.getSourceKey())) {
          logger.warn("RETRY_SOURCE_NOT_FOUND: entityType={} entityId={} — "
                  + "s3://{}/{} still does not exist — marking as SKIPPED",
              item.getEntityType(), item.getEntityId(),
              item.getSourceBucket(), item.getSourceKey());
          item.setStatus("SKIPPED");
          item.setErrorMessage("Source object not found on retry");
          stateRepo.save(item);
          stillFailed.incrementAndGet();
        } else {
          CopyResult result = copyWithVerify(
              item.getSourceBucket(), item.getSourceKey(),
              item.getDestBucket(), item.getDestKey());
          if (result.isSuccess()) {
            item.setStatus("COMPLETED");
            item.setErrorMessage(null);
            stateRepo.save(item);
            recovered.incrementAndGet();
            logger.info("RETRY_RECOVERED: entityType={} entityId={} — s3://{}/{}",
                item.getEntityType(), item.getEntityId(),
                item.getSourceBucket(), item.getSourceKey());
          } else {
            item.setAttempts(item.getAttempts() + maxRetries);
            item.setErrorMessage(result.getErrorMessage());
            stateRepo.save(item);
            stillFailed.incrementAndGet();
            logger.error("RETRY_STILL_FAILED: entityType={} entityId={} — s3://{}/{} — {}",
                item.getEntityType(), item.getEntityId(),
                item.getSourceBucket(), item.getSourceKey(), result.getErrorMessage());
          }
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
    if (bucketExists(defaultBucketName)) {
      return defaultBucketName;
    }
    if (bucketExists(bucketPrefix + defaultBucketName)) {
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

  private boolean bucketExists(String bucketName) {
    try {
      s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build()).join();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private void deleteFile(String filePath, String bucketName) {
    try {
      s3Client.deleteObject(
          DeleteObjectRequest.builder().bucket(bucketName).key(filePath).build()).join();
    } catch (Exception e) {
      logger.warn("Failed to delete s3://{}/{}: {}", bucketName, filePath, e.getMessage());
    }
  }

  private void deleteBucket(String bucketName) {
    try {
      s3Client.deleteBucket(
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
        s3Client.deleteObjects(DeleteObjectsRequest.builder().bucket(bucketName)
            .delete(Delete.builder().objects(partition).build()).build()).join();
      } catch (Exception e) {
        logger.warn("Batch delete failed for '{}': {}", bucketName, e.getMessage());
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
