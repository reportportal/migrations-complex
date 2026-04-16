package com.epam.reportportal.config;

import java.net.URI;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * MinIO (multi-bucket source) and AWS S3 (single-bucket destination). Objects are copied with GET + PUT
 * in {@link com.epam.reportportal.service.impl.SingleBucketMigrationServiceImpl}.
 */
@Configuration
@ConditionalOnProperty(name = "rp.storage.migration", havingValue = "true")
public class MinioToS3StorageConfiguration {

  private static final long ONE_MB = 1024L * 1024L;

  @Bean
  public S3AsyncClient sourceS3AsyncClient(
      @Value("${migration.storage.source.accessKey}") String accessKey,
      @Value("${migration.storage.source.secretKey}") String secretKey,
      @Value("${migration.storage.source.endpoint}") String endpoint,
      @Value("${migration.storage.source.region:us-east-1}") String region,
      @Value("${migration.s3.target-throughput-gbps:20.0}") double targetThroughputGbps,
      @Value("${migration.s3.min-part-size-mb:8}") long minPartSizeMb) {
    return S3AsyncClient.crtBuilder()
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .targetThroughputInGbps(targetThroughputGbps)
        .minimumPartSizeInBytes(minPartSizeMb * ONE_MB)
        .forcePathStyle(true)
        .region(Region.of(region))
        .endpointOverride(URI.create(endpoint))
        .build();
  }

  @Bean
  public S3AsyncClient destinationS3AsyncClient(
      @Value("${migration.storage.destination.accessKey}") String accessKey,
      @Value("${migration.storage.destination.secretKey}") String secretKey,
      @Value("${migration.storage.destination.region}") String region,
      @Value("${migration.storage.destination.endpoint:}") String endpoint,
      @Value("${migration.s3.target-throughput-gbps:20.0}") double targetThroughputGbps,
      @Value("${migration.s3.min-part-size-mb:8}") long minPartSizeMb) {
    var builder = S3AsyncClient.crtBuilder()
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .targetThroughputInGbps(targetThroughputGbps)
        .minimumPartSizeInBytes(minPartSizeMb * ONE_MB)
        .region(Region.of(region));
    if (StringUtils.isNotBlank(endpoint)) {
      builder.endpointOverride(URI.create(endpoint));
    }
    return builder.build();
  }

  @Bean
  public S3MigrationClients s3MigrationClients(
      @Qualifier("sourceS3AsyncClient") S3AsyncClient source,
      @Qualifier("destinationS3AsyncClient") S3AsyncClient destination) {
    return new S3MigrationClients(source, destination);
  }
}
