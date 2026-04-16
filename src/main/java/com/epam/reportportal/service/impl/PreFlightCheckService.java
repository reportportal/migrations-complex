package com.epam.reportportal.service.impl;

import com.epam.reportportal.config.S3MigrationClients;
import com.epam.reportportal.repository.MigrationStateRepository;
import com.epam.reportportal.service.MigrationService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Validates PostgreSQL connectivity and MinIO (source) / S3 (destination) permissions before the
 * storage migration runs.
 */
@Service
@Order(0)
@ConditionalOnProperty(name = "rp.storage.migration", havingValue = "true")
public class PreFlightCheckService implements MigrationService {

  private static final Logger logger = LoggerFactory.getLogger(PreFlightCheckService.class);
  private static final String PROBE_PREFIX = ".migration-probe/";

  private final S3MigrationClients storageClients;
  private final JdbcTemplate jdbcTemplate;
  private final MigrationStateRepository stateRepo;
  private final String singleBucketName;
  private final String bucketPrefix;

  public PreFlightCheckService(
      S3MigrationClients storageClients,
      JdbcTemplate jdbcTemplate,
      MigrationStateRepository stateRepo,
      @Value("${datastore.singleBucketName:}") String singleBucketName,
      @Value("${datastore.bucketPrefix:prj-}") String bucketPrefix) {
    this.storageClients = storageClients;
    this.jdbcTemplate = jdbcTemplate;
    this.stateRepo = stateRepo;
    this.singleBucketName = singleBucketName;
    this.bucketPrefix = bucketPrefix;
  }

  @Override
  public void migrate() {
    logger.info("=== Pre-flight checks (MinIO → S3 migration) ===");

    checkDatabase();
    stateRepo.ensureTable();

    checkSourceBuckets(storageClients.getSource());
    checkDestinationBucket(storageClients.getDestination());

    logger.info("=== Pre-flight checks passed ===");
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

  private void checkSourceBuckets(S3AsyncClient sourceClient) {
    logger.info("[MinIO source] Checking at least one project bucket is accessible...");
    List<Long> projects =
        jdbcTemplate.queryForList("SELECT id FROM public.project LIMIT 5", Long.class);
    boolean anyFound = false;
    for (Long projectId : projects) {
      String bucketName = bucketPrefix + projectId;
      if (bucketExists(bucketName, sourceClient)) {
        checkListPermission(bucketName, sourceClient);
        checkReadPermission(bucketName, sourceClient);
        anyFound = true;
        logger.info("[MinIO source] Bucket '{}' accessible with list+read", bucketName);
        break;
      }
    }
    if (!anyFound) {
      logger.warn("[MinIO source] No project buckets found in first 5 projects — "
          + "this may be expected if buckets were already removed");
    }
  }

  private void checkDestinationBucket(S3AsyncClient destClient) {
    logger.info("[S3 destination] Checking bucket '{}'...", singleBucketName);

    if (!bucketExists(singleBucketName, destClient)) {
      logger.info("[S3 destination] Bucket '{}' does not exist yet — it will be created by the migration job",
          singleBucketName);
      return;
    }

    String probeKey = PROBE_PREFIX + UUID.randomUUID();
    byte[] probeData = "preflight-check".getBytes(StandardCharsets.UTF_8);

    try {
      logger.info("[S3 destination] Testing write permission...");
      destClient.putObject(
          PutObjectRequest.builder().bucket(singleBucketName).key(probeKey).build(),
          AsyncRequestBody.fromBytes(probeData)
      ).join();

      logger.info("[S3 destination] Testing read permission...");
      HeadObjectResponse head = destClient.headObject(
          HeadObjectRequest.builder().bucket(singleBucketName).key(probeKey).build()
      ).join();

      if (head.contentLength() != probeData.length) {
        throw new IllegalStateException(
            "Pre-flight FAILED: probe object size mismatch in destination bucket");
      }

      logger.info("[S3 destination] Testing delete permission...");
      destClient.deleteObject(
          DeleteObjectRequest.builder().bucket(singleBucketName).key(probeKey).build()
      ).join();

      logger.info("[S3 destination] Bucket '{}' — write/read/delete OK", singleBucketName);

    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Pre-flight FAILED: cannot write/read/delete in destination bucket '"
              + singleBucketName + "'", e);
    }
  }

  private void checkListPermission(String bucketName, S3AsyncClient client) {
    try {
      client.listObjectsV2(
          ListObjectsV2Request.builder().bucket(bucketName).maxKeys(1).build()
      ).join();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Pre-flight FAILED: cannot list objects in bucket '" + bucketName + "'", e);
    }
  }

  private void checkReadPermission(String bucketName, S3AsyncClient client) {
    try {
      ListObjectsV2Response listing = client.listObjectsV2(
          ListObjectsV2Request.builder().bucket(bucketName).maxKeys(1).build()
      ).join();
      if (listing.hasContents() && !listing.contents().isEmpty()) {
        String firstKey = listing.contents().get(0).key();
        client.headObject(
            HeadObjectRequest.builder().bucket(bucketName).key(firstKey).build()
        ).join();
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Pre-flight FAILED: cannot read objects in bucket '" + bucketName + "'", e);
    }
  }

  private boolean bucketExists(String bucketName, S3AsyncClient client) {
    try {
      client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build()).join();
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
