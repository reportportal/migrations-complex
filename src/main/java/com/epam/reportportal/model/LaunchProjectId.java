package com.epam.reportportal.model;

import java.util.Objects;

public class LaunchProjectId {
  private Long launchId;
  private Long projectId;

  public LaunchProjectId() {
  }

  public LaunchProjectId(Long launchId, Long projectId) {
    this.launchId = launchId;
    this.projectId = projectId;
  }

  public Long getLaunchId() {
    return launchId;
  }

  public void setLaunchId(Long launchId) {
    this.launchId = launchId;
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
    LaunchProjectId that = (LaunchProjectId) o;
    return Objects.equals(launchId, that.launchId) && Objects.equals(projectId, that.projectId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(launchId, projectId);
  }
}
