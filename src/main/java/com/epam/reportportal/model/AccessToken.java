package com.epam.reportportal.model;

import java.io.Serializable;
import java.util.Objects;

public class AccessToken implements Serializable {

  private String tokenId;

  private Long userId;

  public AccessToken() {
  }

  public AccessToken(String tokenId, Long userId) {
    this.tokenId = tokenId;
    this.userId = userId;
  }

  public String getTokenId() {
    return tokenId;
  }

  public void setTokenId(String tokenId) {
    this.tokenId = tokenId;
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
    AccessToken that = (AccessToken) o;
    return Objects.equals(tokenId, that.tokenId) && Objects.equals(userId, that.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tokenId, userId);
  }
}
