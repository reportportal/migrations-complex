package com.epam.reportportal.service.impl;

import com.epam.reportportal.model.Attachment;
import com.epam.reportportal.model.Plugin;
import com.epam.reportportal.model.User;
import com.epam.reportportal.service.SingleBucketMigrationService;
import com.epam.reportportal.utils.AttachmentRowMapper;
import com.epam.reportportal.utils.PluginRowMapper;
import com.epam.reportportal.utils.UserRowMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.Copy;
import software.amazon.awssdk.transfer.s3.model.CopyRequest;

/**
 * Service which responsible for migrating attachments from Multi-bucket to Single-bucket.
 */
@Service
public class SingleBucketMigrationServiceImpl implements SingleBucketMigrationService {

  private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

  public static final String SELECT_ALL_PROJECTS = "SELECT id FROM public.project";

  public static final String PROJECT_PREFIX = "project-data/";

  public static final String USERS_MULTIBUCKET_NAME = "users";

  public static final String USERS_SINGLEBUCKET_PREFIX = "user-data/";

  public static final String PLUGINS_PREFIX = "plugins/";

  public static final String SECRETS_PREFIX = "integration-secrets/";

  public static final String SELECT_ALL_PLUGINS = "SELECT id, details FROM integration_type";

  public static final String SELECT_ALL_ATTACHMENTS_BY_PROJECT_ID =
      "SELECT id, file_id, thumbnail_id, project_id FROM "
          + "public.attachment WHERE project_id = ?";

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

  private final String bucketPrefix;
  private final String defaultBucketName;
  private final String singleBucketName;

  private final String secretsPath;

  private final S3TransferManager transferManager;

  private final JdbcTemplate jdbcTemplate;

  public SingleBucketMigrationServiceImpl(S3AsyncClient s3Client, JdbcTemplate jdbcTemplate,
      @Value("${datastore.bucketPrefix}") String bucketPrefix,
      @Value("${datastore.defaultBucketName}") String defaultBucketName,
      @Value("${datastore.singleBucketName}") String singleBucketName,
      @Value("${datastore.secrets.path}") String secretsPath) {
    this.jdbcTemplate = jdbcTemplate;
    this.transferManager = S3TransferManager.builder().s3Client(s3Client).build();
    this.bucketPrefix = bucketPrefix;
    this.defaultBucketName = defaultBucketName;
    this.singleBucketName = singleBucketName;
    this.secretsPath = secretsPath;
  }

  @Transactional
  @Override
  public void migrateAttachments() {
    if (!StringUtils.isEmpty(singleBucketName)) {
      migrateProjectData();
      migrateUserPhotos();
      migratePlugins();
      migrateIntegrationSecrets();
      LOGGER.info("Migration to single bucket is completed.");
    }
  }

  private void migrateProjectData() {
    List<Long> projects = jdbcTemplate.queryForList(SELECT_ALL_PROJECTS, Long.class);
    for (Long projectId : projects) {
      List<Attachment> attachments =
          jdbcTemplate.query(SELECT_ALL_ATTACHMENTS_BY_PROJECT_ID, new AttachmentRowMapper(),
              projectId
          );
      if (attachments.isEmpty()) {
        continue;
      }
      attachments.forEach(this::migrateAttachment);
    }
  }

  private void migrateUserPhotos() {
    List<User> users = jdbcTemplate.query(SELECT_ALL_USERS, new UserRowMapper());
    for (User user : users) {
      String attachment = user.getAttachment();
      if (attachment != null) {
        migratePhoto(attachment, user.getId(), UPDATE_USER_PHOTO);
      }
      String thumbnail = user.getAttachmentThumbnail();
      if (thumbnail != null) {
        migratePhoto(thumbnail, user.getId(), UPDATE_USER_PHOTO_THUMBNAIL);
      }
    }
  }

  private void migratePlugins() {
    List<Plugin> pluginsDetails = jdbcTemplate.query(SELECT_ALL_PLUGINS, new PluginRowMapper());
    for (Plugin plugin : pluginsDetails) {
      if (StringUtils.isEmpty(plugin.getDetails())) {
        continue;
      }
      JSONObject detailsJson = new JSONObject(plugin.getDetails());
      String pluginPath = detailsJson.getJSONObject("details").getString("id");

      copyObjectToNewBucket(
          defaultBucketName, pluginPath, singleBucketName, PLUGINS_PREFIX + pluginPath);

      detailsJson.getJSONObject("details").put("id", PLUGINS_PREFIX + pluginPath);

      jdbcTemplate.update(UPDATE_PLUGIN_DETAILS, detailsJson.toString(), plugin.getId());
    }
  }

  private void migrateIntegrationSecrets() {
    copyObjectToNewBucket(bucketPrefix + secretsPath, "secret-integration-salt", singleBucketName,
        SECRETS_PREFIX + "secret-integration-salt"
    );
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
        LOGGER.warn("Exception occurred during copy : {}", ex.getMessage());
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

  private void moveAttachmentAndUpdatePath(Attachment attachment, String encodedFilePath,
      String sql) {
    String filePath = decode(encodedFilePath);
    String cutFilePath = cutPath(filePath);
    String singleBucketFilePath = PROJECT_PREFIX + filePath;
    copyObjectToNewBucket(bucketPrefix + attachment.getProjectId(), cutFilePath, singleBucketName,
        singleBucketFilePath
    );
    jdbcTemplate.update(sql, encode(singleBucketFilePath), attachment.getId());
  }

  private String cutPath(String filePath) {
    Path path = Paths.get(filePath);
    return String.valueOf(path.subpath(1, path.getNameCount()));
  }

  private void migratePhoto(String encodedPath, Long id, String sql) {
    String decodedPhotoPath = decode(encodedPath);

    String cutPhotoPath = cutPath(decodedPhotoPath);

    copyObjectToNewBucket(bucketPrefix + USERS_MULTIBUCKET_NAME, cutPhotoPath, singleBucketName,
        USERS_SINGLEBUCKET_PREFIX + cutPhotoPath
    );

    jdbcTemplate.update(sql, encode(USERS_SINGLEBUCKET_PREFIX + cutPhotoPath), id);
  }
}
