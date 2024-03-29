package com.epam.reportportal.service.impl;

import com.epam.reportportal.logging.LogMigration;
import com.epam.reportportal.model.Attachment;
import com.epam.reportportal.model.Plugin;
import com.epam.reportportal.model.User;
import com.epam.reportportal.service.MigrationService;
import com.epam.reportportal.utils.AttachmentRowMapper;
import com.epam.reportportal.utils.PluginRowMapper;
import com.epam.reportportal.utils.UserRowMapper;
import com.google.common.collect.Iterables;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.Copy;
import software.amazon.awssdk.transfer.s3.model.CopyRequest;

/**
 * Service which responsible for migrating attachments from Multi-bucket to Single-bucket.
 */
@Service
@Order(3)
@ConditionalOnProperty(name = "rp.singlebucket.migration", havingValue = "true")
public class SingleBucketMigrationServiceImpl implements MigrationService {

  public static final String SELECT_ALL_PROJECTS = "SELECT id FROM public.project";
  public static final String PROJECT_PREFIX = "project-data/";
  public static final String USERS_MULTIBUCKET_NAME = "users";
  public static final String USERS_SINGLEBUCKET_PREFIX = "user-data/";
  public static final String PLUGINS_PREFIX = "plugins/";
  public static final String SECRETS_PREFIX = "integration-secrets/";
  public static final String SELECT_ALL_PLUGINS = "SELECT id, details FROM integration_type";
  public static final String SELECT_ALL_USERS =
      "SELECT id, attachment, attachment_thumbnail FROM " + "public.users";
  public static final String UPDATE_ATTACHMENT_FILE_ID =
      "UPDATE public.attachment SET " + "file_id = ? WHERE id = ?";
  public static final String UPDATE_ATTACHMENT_THUMBNAIL_ID =
      "UPDATE public.attachment SET " + "thumbnail_id = ? WHERE id = ?";
  public static final String UPDATE_USER_PHOTO =
      "UPDATE public.users SET attachment = ? " + "WHERE id = ?";
  public static final String UPDATE_USER_PHOTO_THUMBNAIL =
      "UPDATE public.users SET attachment_thumbnail = ? " + "WHERE id = ?";
  public static final String UPDATE_PLUGIN_DETAILS =
      "UPDATE public.integration_type SET details = ?::JSONB WHERE id = ?";
  private static final int BATCH_SIZE = 200000;
  public static final String SELECT_ALL_ATTACHMENTS_BY_PROJECT_ID =
      "SELECT id, file_id, thumbnail_id, project_id FROM "
          + "public.attachment WHERE project_id = ? ORDER BY id LIMIT " + BATCH_SIZE + " OFFSET ?";
  private final Logger logger = LoggerFactory.getLogger(this.getClass());
  private final String bucketPrefix;
  private final String defaultBucketName;
  private final String singleBucketName;

  private final String secretsPath;

  private final Boolean removeAfterMigration;

  private final S3AsyncClient s3Client;

  private final S3TransferManager transferManager;

  private final JdbcTemplate jdbcTemplate;

  public SingleBucketMigrationServiceImpl(S3AsyncClient s3Client, JdbcTemplate jdbcTemplate,
      @Value("${datastore.bucketPrefix}") String bucketPrefix,
      @Value("${datastore.defaultBucketName}") String defaultBucketName,
      @Value("${datastore.singleBucketName}") String singleBucketName,
      @Value("${datastore.secrets.path}") String secretsPath,
      @Value("#{new Boolean('${datastore.remove.after.migration}')}")
      Boolean removeAfterMigration) {
    this.jdbcTemplate = jdbcTemplate;
    this.s3Client = s3Client;
    this.transferManager = S3TransferManager.builder().s3Client(s3Client).build();
    this.bucketPrefix = bucketPrefix;
    this.defaultBucketName = defaultBucketName;
    this.singleBucketName = singleBucketName;
    this.secretsPath = secretsPath;
    this.removeAfterMigration = removeAfterMigration;
  }

  @Transactional
  @Override
  @LogMigration("Migration from multi-bucket to single-bucket")
  public void migrate() {
    if (!StringUtils.isEmpty(singleBucketName)) {

      if (!bucketExists(singleBucketName)) {
        CompletableFuture<CreateBucketResponse> createBucket =
            s3Client.createBucket(CreateBucketRequest.builder().bucket(singleBucketName).build());

        CreateBucketResponse createBucketResponse = createBucket.join();
        if (createBucketResponse.sdkHttpResponse().isSuccessful()) {
          logger.info("Single bucket {} is created", singleBucketName);
        } else {
          logger.info("Single bucket {} isn't created", singleBucketName);
        }
      }
      migrateIntegrationSecrets();
      migratePlugins();
      migrateUserPhotos();
      migrateProjectData();
    }
  }

  private void migrateProjectData() {
    List<Long> projects = jdbcTemplate.queryForList(SELECT_ALL_PROJECTS, Long.class);
    for (Long projectId : projects) {
      if (!bucketExists(bucketPrefix + projectId)) {
        logger.warn("Bucket {} doesn't exist", bucketPrefix + projectId);
        continue;
      }

      int iteration = 0;
      int size;
      do {
        List<Attachment> attachments =
            jdbcTemplate.query(SELECT_ALL_ATTACHMENTS_BY_PROJECT_ID, new AttachmentRowMapper(),
                projectId, iteration * BATCH_SIZE
            );
        if (attachments.isEmpty()) {
          break;
        }
        attachments.forEach(this::migrateAttachment);
        if (removeAfterMigration) {
          deleteAttachments(attachments, bucketPrefix + projectId);
        }
        size = attachments.size();
        iteration++;
      } while (size == BATCH_SIZE);
      if (removeAfterMigration) {
        deleteBucket(bucketPrefix + projectId);
      }
      logger.info("Migration for project {} is completed", projectId);
    }
    logger.info("Migration of project attachments is completed");
  }

  private void migrateUserPhotos() {
    List<User> users = jdbcTemplate.query(SELECT_ALL_USERS, new UserRowMapper());
    for (User user : users) {
      String attachment = user.getAttachment();
      if (StringUtils.isNotBlank(attachment)) {
        migratePhoto(attachment, user.getId(), UPDATE_USER_PHOTO);
        if (removeAfterMigration) {
          deleteFile(cutPath(decode(attachment)), bucketPrefix + USERS_MULTIBUCKET_NAME);
        }
      }
      String thumbnail = user.getAttachmentThumbnail();
      if (StringUtils.isNotBlank(thumbnail)) {
        migratePhoto(thumbnail, user.getId(), UPDATE_USER_PHOTO_THUMBNAIL);
        if (removeAfterMigration) {
          deleteFile(cutPath(decode(thumbnail)), bucketPrefix + USERS_MULTIBUCKET_NAME);
        }
      }
    }
    if (removeAfterMigration) {
      deleteBucket(bucketPrefix + USERS_MULTIBUCKET_NAME);
    }
    logger.info("Migration of user photos is completed");
  }

  private void migratePlugins() {
    List<Plugin> pluginsDetails = jdbcTemplate.query(SELECT_ALL_PLUGINS, new PluginRowMapper());
    for (Plugin plugin : pluginsDetails) {
      String pluginPath = getPluginPath(plugin);
      if (pluginPath == null) {
        continue;
      }

      if (isPluginAlreadyMigrated(pluginPath)) {
        return;
      }

      String sourceBucket = getSourceBucketForMigration();
      if (sourceBucket != null) {
        migratePlugin(plugin, pluginPath, sourceBucket);
      }
    }

    if (removeAfterMigration) {
      deleteBucket(defaultBucketName);
      deleteBucket(bucketPrefix + defaultBucketName);
    }
    logger.info("Migration of plugins is completed");
  }

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
    //For versions newer than 5.7.4
    if (bucketExists(defaultBucketName)) {
      return defaultBucketName;
      //For versions <= 5.7.4
    } else if (bucketExists(bucketPrefix + defaultBucketName)) {
      return bucketPrefix + defaultBucketName;
    }
    return null;
  }

  private void migratePlugin(Plugin plugin, String pluginPath, String sourceBucket) {
    copyObjectToNewBucket(sourceBucket, pluginPath, singleBucketName, PLUGINS_PREFIX + pluginPath);

    JSONObject detailsJson = new JSONObject(plugin.getDetails());
    detailsJson.getJSONObject("details").put("id", PLUGINS_PREFIX + pluginPath);
    jdbcTemplate.update(UPDATE_PLUGIN_DETAILS, detailsJson.toString(), plugin.getId());

    if (removeAfterMigration) {
      deleteFile(pluginPath, sourceBucket);
    }
  }

  private void migrateIntegrationSecrets() {
    String saltPath = "secret-integration-salt";
    String migrationPath = "migration";
    copyObjectToNewBucket(bucketPrefix + secretsPath, saltPath, singleBucketName,
        SECRETS_PREFIX + saltPath
    );
    if (removeAfterMigration) {
      deleteFile(saltPath, bucketPrefix + secretsPath);
    }
    copyObjectToNewBucket(bucketPrefix + secretsPath, migrationPath, singleBucketName,
        SECRETS_PREFIX + migrationPath
    );
    if (removeAfterMigration) {
      deleteFile(migrationPath, bucketPrefix + secretsPath);
      deleteBucket(bucketPrefix + secretsPath);
    }
    logger.info("Migrations of integration secrets is completed");
  }

  private void copyObjectToNewBucket(String bucketName, String key, String destinationBucket,
      String destinationKey) {
    CopyObjectRequest copyObjectRequest =
        CopyObjectRequest.builder().sourceBucket(bucketName).sourceKey(key)
            .destinationBucket(destinationBucket).destinationKey(destinationKey).build();

    CopyRequest copyRequest = CopyRequest.builder().copyObjectRequest(copyObjectRequest).build();

    Copy copy = transferManager.copy(copyRequest);

    copy.completionFuture().handle((file, ex) -> {
      if (ex != null) {
        logger.warn("Exception occurred during copy : {}", ex.getMessage());
      }
      return file;
    }).join();
  }

  private void migrateAttachment(Attachment attachment) {
    String encodedFilePath = attachment.getFileId();
    String encodedThumbnailPath = attachment.getThumbnailId();
    if (encodedFilePath != null) {
      moveAttachmentAndUpdatePath(attachment, encodedFilePath, UPDATE_ATTACHMENT_FILE_ID);
    }
    if (encodedThumbnailPath != null) {
      moveAttachmentAndUpdatePath(attachment, encodedThumbnailPath, UPDATE_ATTACHMENT_THUMBNAIL_ID);
    }
  }

  private void moveAttachmentAndUpdatePath(Attachment attachment, String encodedFilePath,
      String sql) {
    String filePath = decode(encodedFilePath);

    //Check if file is already migrated
    if (PROJECT_PREFIX.equals(getPathFirstPart(filePath) + "/")) {
      return;
    }
    String cutFilePath = cutPath(filePath);
    String singleBucketFilePath = PROJECT_PREFIX + filePath;
    copyObjectToNewBucket(bucketPrefix + attachment.getProjectId(), cutFilePath, singleBucketName,
        singleBucketFilePath
    );
    jdbcTemplate.update(sql, encode(singleBucketFilePath), attachment.getId());
  }

  private void migratePhoto(String encodedPath, Long id, String sql) {
    String decodedPhotoPath = decode(encodedPath);

    //Check if photo is already migrated
    if (USERS_SINGLEBUCKET_PREFIX.equals(getPathFirstPart(decodedPhotoPath) + "/")) {
      return;
    }

    String cutPhotoPath = cutPath(decodedPhotoPath);

    copyObjectToNewBucket(bucketPrefix + USERS_MULTIBUCKET_NAME, cutPhotoPath, singleBucketName,
        USERS_SINGLEBUCKET_PREFIX + cutPhotoPath
    );

    jdbcTemplate.update(sql, encode(USERS_SINGLEBUCKET_PREFIX + cutPhotoPath), id);
  }

  private void deleteAttachments(List<Attachment> files, String bucketName) {
    List<ObjectIdentifier> objectIdentifiers =
        files.stream().map(Attachment::getFileId).filter(Objects::nonNull)
            .map(file -> ObjectIdentifier.builder().key(cutPath(decode(file))).build())
            .collect(Collectors.toList());
    objectIdentifiers.addAll(files.stream().map(Attachment::getThumbnailId).filter(Objects::nonNull)
        .map(thumbnail -> ObjectIdentifier.builder().key(cutPath(decode(thumbnail))).build())
        .collect(Collectors.toList()));
    for (List<ObjectIdentifier> partition : Iterables.partition(objectIdentifiers, 1000)) {
      DeleteObjectsRequest deleteObjectsRequest = DeleteObjectsRequest.builder().bucket(bucketName)
          .delete(Delete.builder().objects(partition).build()).build();

      CompletableFuture<DeleteObjectsResponse> response =
          s3Client.deleteObjects(deleteObjectsRequest);

      DeleteObjectsResponse deleteObjectsResponse = response.handle((file, ex) -> {
        if (ex != null) {
          logger.warn("Exception occurred during deletion : {}", ex.getMessage());
        }
        return file;
      }).join();

      if (deleteObjectsResponse != null) {
        logger.info(
            "Deleted attachments for {} : {}", bucketName, deleteObjectsResponse.hasDeleted());
      }
    }
  }

  private void deleteFile(String filePath, String bucketName) {
    DeleteObjectRequest deleteObjectRequest =
        DeleteObjectRequest.builder().bucket(bucketName).key(filePath).build();

    CompletableFuture<DeleteObjectResponse> response = s3Client.deleteObject(deleteObjectRequest);

    DeleteObjectResponse deleteObjectResponse = response.handle((file, ex) -> {
      if (ex != null) {
        logger.warn("Exception occurred during deletion : {}", ex.getMessage());
      }
      return file;
    }).join();

    if (deleteObjectResponse != null && deleteObjectResponse.sdkHttpResponse() != null) {
      logger.info(
          "Deleted {} : {}", filePath, deleteObjectResponse.sdkHttpResponse().isSuccessful());
    }
  }

  private String getPathFirstPart(String filePath) {
    Path path = Paths.get(filePath);
    return String.valueOf(path.subpath(0, 1));
  }

  private String cutPath(String filePath) {
    Path path = Paths.get(filePath);
    return String.valueOf(path.subpath(1, path.getNameCount()));
  }

  private String decode(String data) {
    return StringUtils.isEmpty(data) ? data :
        new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
  }

  private String encode(String data) {
    return StringUtils.isEmpty(data) ? data :
        new String(Base64.getUrlEncoder().encode(data.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8
        );
  }

  private boolean bucketExists(String bucketName) {
    HeadBucketRequest headBucketRequest = HeadBucketRequest.builder().bucket(bucketName).build();

    try {
      s3Client.headBucket(headBucketRequest).join();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private void deleteBucket(String bucketName) {
    DeleteBucketRequest deleteBucketRequest =
        DeleteBucketRequest.builder().bucket(bucketName).build();

    try {
      s3Client.deleteBucket(deleteBucketRequest);
    } catch (Exception e) {
      logger.warn("Bucket {} isn't delete due to exception {}", bucketName, e.getMessage());
    }
  }

}
