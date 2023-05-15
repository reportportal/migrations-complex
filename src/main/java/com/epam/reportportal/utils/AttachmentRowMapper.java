package com.epam.reportportal.utils;

import com.epam.reportportal.model.Attachment;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public class AttachmentRowMapper implements RowMapper<Attachment> {

  @Override
  public Attachment mapRow(ResultSet rs, int rowNum) throws SQLException {
    Attachment attachment = new Attachment();
    attachment.setId(rs.getLong("id"));
    attachment.setProjectId(rs.getLong("project_id"));
    attachment.setFileId(rs.getString("file_id"));
    attachment.setThumbnailId(rs.getString("thumbnail_id"));

    return attachment;
  }
}
