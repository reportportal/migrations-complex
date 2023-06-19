package com.epam.reportportal.utils;

import com.epam.reportportal.model.AccessToken;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public class AccessTokenRowMapper implements RowMapper<AccessToken> {

  @Override
  public AccessToken mapRow(ResultSet rs, int rowNum) throws SQLException {
    AccessToken accessToken = new AccessToken();

    accessToken.setTokenId(rs.getString("token_id"));
    accessToken.setUserId(rs.getLong("user_id"));

    return accessToken;
  }
}
