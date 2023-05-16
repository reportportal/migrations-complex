package com.epam.reportportal.config;

import static software.amazon.awssdk.transfer.s3.SizeConstant.MB;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

@Configuration
public class DataStorageConfiguration {

  @Bean
  @ConditionalOnProperty(name = "datastore.type", havingValue = "minio")
  public S3AsyncClient minioClient(@Value("${datastore.accessKey}") String accessKey,
      @Value("${datastore.secretKey}") String secretKey,
      @Value("${datastore.endpoint}") String endpoint,
      @Value("${datastore.region}") String region) {
    return S3AsyncClient.crtBuilder().credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .targetThroughputInGbps(20.0).minimumPartSizeInBytes(8 * MB).forcePathStyle(true)
        .region(Region.of(region))
        .endpointOverride(URI.create(endpoint)).build();
  }

  @Bean
  @ConditionalOnProperty(name = "datastore.type", havingValue = "s3")
  public S3AsyncClient s3Client(@Value("${datastore.accessKey}") String accessKey,
      @Value("${datastore.secretKey}") String secretKey,
      @Value("${datastore.region}") String region) {
    return S3AsyncClient.crtBuilder().credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .targetThroughputInGbps(20.0).minimumPartSizeInBytes(8 * MB).region(Region.of(region))
        .build();

  }
}
