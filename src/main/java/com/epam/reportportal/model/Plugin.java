package com.epam.reportportal.model;

import java.util.Objects;

public class Plugin {

  private Long id;

  private String details;

  public Plugin() {
  }

  public Plugin(Long id, String details) {
    this.id = id;
    this.details = details;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Plugin plugin = (Plugin) o;
    return Objects.equals(id, plugin.id) && Objects.equals(details, plugin.details);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, details);
  }
}
