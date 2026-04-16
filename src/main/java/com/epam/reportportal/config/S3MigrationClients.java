package com.epam.reportportal.config;

import software.amazon.awssdk.services.s3.S3AsyncClient;

/** MinIO (source) and AWS S3 (destination) clients for one-step storage migration. */
public final class S3MigrationClients {

  private final S3AsyncClient source;
  private final S3AsyncClient destination;

  public S3MigrationClients(S3AsyncClient source, S3AsyncClient destination) {
    this.source = source;
    this.destination = destination;
  }

  public S3AsyncClient getSource() {
    return source;
  }

  public S3AsyncClient getDestination() {
    return destination;
  }
}
