package com.epam.reportportal.utils;

import com.epam.reportportal.model.User;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public class UserRowMapper implements RowMapper<User> {
  @Override
  public User mapRow(ResultSet rs, int rowNum) throws SQLException {
    User user = new User();
    user.setId(rs.getLong("id"));
    user.setAttachment(rs.getString("attachment"));
    user.setAttachmentThumbnail(rs.getString("attachment_thumbnail"));
    return user;
  }
}
