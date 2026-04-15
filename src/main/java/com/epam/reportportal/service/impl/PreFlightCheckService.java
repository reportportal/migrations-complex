package com.epam.reportportal.service.impl;

import com.epam.reportportal.repository.MigrationStateRepository;
import com.epam.reportportal.service.MigrationService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Runs before all other migrations to validate connectivity and permissions.
 * Fails fast with a clear error if any check does not pass.
 */
@Service
@Order(0)
public class PreFlightCheckService implements MigrationService {

  private static final Logger logger = LoggerFactory.getLogger(PreFlightCheckService.class);
  private static final String PROBE_PREFIX = ".migration-probe/";

  private final S3AsyncClient s3Client;
  private final JdbcTemplate jdbcTemplate;
  private final MigrationStateRepository stateRepo;
  private final String singleBucketName;
  private final String bucketPrefix;
  private final boolean singleBucketMigrationEnabled;
  private final boolean minioToS3MigrationEnabled;

  public PreFlightCheckService(
      S3AsyncClient s3Client,
      JdbcTemplate jdbcTemplate,
      MigrationStateRepository stateRepo,
      @Value("${datastore.singleBucketName:}") String singleBucketName,
      @Value("${datastore.bucketPrefix:prj-}") String bucketPrefix,
      @Value("${rp.singlebucket.migration:false}") boolean singleBucketMigrationEnabled,
      @Value("${rp.minio.s3.migration:false}") boolean minioToS3MigrationEnabled) {
    this.s3Client = s3Client;
    this.jdbcTemplate = jdbcTemplate;
    this.stateRepo = stateRepo;
    this.singleBucketName = singleBucketName;
    this.bucketPrefix = bucketPrefix;
    this.singleBucketMigrationEnabled = singleBucketMigrationEnabled;
    this.minioToS3MigrationEnabled = minioToS3MigrationEnabled;
  }

  @Override
  public void migrate() {
    logger.info("=== Pre-flight checks starting ===");

    checkDatabase();
    stateRepo.ensureTable();

    if (singleBucketMigrationEnabled) {
      if (minioToS3MigrationEnabled) {
        checkSourceBuckets();
        checkDestinationBucket();
      } else {
        logger.info("[S3] Skipping source/destination permission checks because "
            + "'rp.minio.s3.migration' is disabled");
      }
    }

    if (minioToS3MigrationEnabled) {
      checkMinioToS3Env();
    }

    logger.info("=== All pre-flight checks passed ===");
  }

  private void checkDatabase() {
    logger.info("[DB] Checking connectivity...");
    try {
      jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      logger.info("[DB] Connection OK");
    } catch (Exception e) {
      throw new IllegalStateException("Pre-flight FAILED: cannot connect to database", e);
    }

    logger.info("[DB] Checking project table...");
    try {
      Integer count =
          jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.project", Integer.class);
      logger.info("[DB] Found {} projects", count);
    } catch (Exception e) {
      throw new IllegalStateException("Pre-flight FAILED: cannot read project table", e);
    }
  }

  private void checkSourceBuckets() {
    logger.info("[S3-Source] Checking at least one source bucket is accessible...");
    List<Long> projects =
        jdbcTemplate.queryForList("SELECT id FROM public.project LIMIT 5", Long.class);
    boolean anyFound = false;
    for (Long projectId : projects) {
      String bucketName = bucketPrefix + projectId;
      if (bucketExists(bucketName)) {
        checkListPermission(bucketName);
        checkReadPermission(bucketName);
        anyFound = true;
        logger.info("[S3-Source] Bucket '{}' accessible with list+read", bucketName);
        break;
      }
    }
    if (!anyFound) {
      logger.warn("[S3-Source] No source project buckets found in first 5 projects — "
          + "this may be expected if buckets were already removed");
    }
  }

  private void checkDestinationBucket() {
    logger.info("[S3-Dest] Checking destination bucket '{}'...", singleBucketName);

    if (!bucketExists(singleBucketName)) {
      logger.info("[S3-Dest] Bucket '{}' does not exist yet — will be created during migration",
          singleBucketName);
      return;
    }

    String probeKey = PROBE_PREFIX + UUID.randomUUID();
    byte[] probeData = "preflight-check".getBytes(StandardCharsets.UTF_8);

    try {
      logger.info("[S3-Dest] Testing write permission...");
      s3Client.putObject(
          PutObjectRequest.builder().bucket(singleBucketName).key(probeKey).build(),
          AsyncRequestBody.fromBytes(probeData)
      ).join();

      logger.info("[S3-Dest] Testing read permission...");
      HeadObjectResponse head = s3Client.headObject(
          HeadObjectRequest.builder().bucket(singleBucketName).key(probeKey).build()
      ).join();

      if (head.contentLength() != probeData.length) {
        throw new IllegalStateException(
            "Pre-flight FAILED: probe object size mismatch in destination bucket");
      }

      logger.info("[S3-Dest] Testing delete permission...");
      s3Client.deleteObject(
          DeleteObjectRequest.builder().bucket(singleBucketName).key(probeKey).build()
      ).join();

      logger.info("[S3-Dest] Destination bucket '{}' — write/read/delete OK", singleBucketName);

    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Pre-flight FAILED: cannot write/read/delete in destination bucket '"
              + singleBucketName + "'", e);
    }
  }

  private void checkMinioToS3Env() {
    logger.info("[MinIO→S3] Checking required environment variables...");
    String[] required = {
        "MINIO_ENDPOINT", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY",
        "S3_ENDPOINT", "S3_ACCESS_KEY", "S3_SECRET_KEY",
        "MINIO_SINGLE_BUCKET", "S3_SINGLE_BUCKET"
    };
    for (String var : required) {
      String val = System.getenv(var);
      if (val == null || val.isBlank()) {
        throw new IllegalStateException(
            "Pre-flight FAILED: environment variable '" + var + "' is not set");
      }
    }
    logger.info("[MinIO→S3] All required env vars present");
  }

  private void checkListPermission(String bucketName) {
    try {
      s3Client.listObjectsV2(
          ListObjectsV2Request.builder().bucket(bucketName).maxKeys(1).build()
      ).join();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Pre-flight FAILED: cannot list objects in bucket '" + bucketName + "'", e);
    }
  }

  private void checkReadPermission(String bucketName) {
    try {
      ListObjectsV2Response listing = s3Client.listObjectsV2(
          ListObjectsV2Request.builder().bucket(bucketName).maxKeys(1).build()
      ).join();
      if (listing.hasContents() && !listing.contents().isEmpty()) {
        String firstKey = listing.contents().get(0).key();
        s3Client.headObject(
            HeadObjectRequest.builder().bucket(bucketName).key(firstKey).build()
        ).join();
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Pre-flight FAILED: cannot read objects in bucket '" + bucketName + "'", e);
    }
  }

  private boolean bucketExists(String bucketName) {
    try {
      s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build()).join();
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
