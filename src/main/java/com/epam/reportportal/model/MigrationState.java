package com.epam.reportportal.model;

import java.time.LocalDateTime;

public class MigrationState {

  private Long id;
  private String migrationType;
  private String entityType;
  private Long entityId;
  private String sourceBucket;
  private String sourceKey;
  private String destBucket;
  private String destKey;
  private String status;
  private int attempts;
  private String errorMessage;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public MigrationState() {
  }

  public MigrationState(String migrationType, String entityType, Long entityId,
      String sourceBucket, String sourceKey, String destBucket, String destKey) {
    this.migrationType = migrationType;
    this.entityType = entityType;
    this.entityId = entityId;
    this.sourceBucket = sourceBucket;
    this.sourceKey = sourceKey;
    this.destBucket = destBucket;
    this.destKey = destKey;
    this.status = "PENDING";
    this.attempts = 0;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getMigrationType() {
    return migrationType;
  }

  public void setMigrationType(String migrationType) {
    this.migrationType = migrationType;
  }

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  public Long getEntityId() {
    return entityId;
  }

  public void setEntityId(Long entityId) {
    this.entityId = entityId;
  }

  public String getSourceBucket() {
    return sourceBucket;
  }

  public void setSourceBucket(String sourceBucket) {
    this.sourceBucket = sourceBucket;
  }

  public String getSourceKey() {
    return sourceKey;
  }

  public void setSourceKey(String sourceKey) {
    this.sourceKey = sourceKey;
  }

  public String getDestBucket() {
    return destBucket;
  }

  public void setDestBucket(String destBucket) {
    this.destBucket = destBucket;
  }

  public String getDestKey() {
    return destKey;
  }

  public void setDestKey(String destKey) {
    this.destKey = destKey;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int attempts) {
    this.attempts = attempts;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
