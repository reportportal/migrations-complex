package com.epam.reportportal.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

public class ApiKey implements Serializable {

  private String name;

  private String hash;

  private LocalDateTime createdAt;

  private Long userId;

  public ApiKey(String name, String hash, LocalDateTime createdAt, Long userId) {
    this.name = name;
    this.hash = hash;
    this.createdAt = createdAt;
    this.userId = userId;
  }

  public ApiKey() {
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getHash() {
    return hash;
  }

  public void setHash(String hash) {
    this.hash = hash;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ApiKey apiKey = (ApiKey) o;
    return Objects.equals(name, apiKey.name) && Objects.equals(hash, apiKey.hash) && Objects.equals(
        createdAt, apiKey.createdAt) && Objects.equals(userId, apiKey.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, hash, createdAt, userId);
  }
}
