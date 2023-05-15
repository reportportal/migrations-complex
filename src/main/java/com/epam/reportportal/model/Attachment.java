package com.epam.reportportal.model;

import java.io.Serializable;
import java.util.Objects;

public class Attachment implements Serializable {
  private Long id;

  private String fileId;

  private String thumbnailId;

  private Long projectId;

  public Attachment() {
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getFileId() {
    return fileId;
  }

  public void setFileId(String fileId) {
    this.fileId = fileId;
  }

  public String getThumbnailId() {
    return thumbnailId;
  }

  public void setThumbnailId(String thumbnailId) {
    this.thumbnailId = thumbnailId;
  }

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Attachment that = (Attachment) o;
    return Objects.equals(fileId, that.fileId) && Objects.equals(thumbnailId, that.thumbnailId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fileId, thumbnailId);
  }
}
