package com.epam.reportportal.model;

/*
 * Copyright 2019 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.Serializable;
import java.util.Objects;

public class User implements Serializable {

  private Long id;

  private String attachment;

  private String attachmentThumbnail;

  public User() {
  }

  public Long getId() {
    return this.id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getAttachment() {
    return attachment;
  }

  public void setAttachment(String attachment) {
    this.attachment = attachment;
  }

  public String getAttachmentThumbnail() {
    return attachmentThumbnail;
  }

  public void setAttachmentThumbnail(String attachmentThumbnail) {
    this.attachmentThumbnail = attachmentThumbnail;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    User user = (User) o;
    return Objects.equals(id, user.id) && Objects.equals(attachment, user.attachment)
        && Objects.equals(attachmentThumbnail, user.attachmentThumbnail);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, attachment, attachmentThumbnail);
  }
}

