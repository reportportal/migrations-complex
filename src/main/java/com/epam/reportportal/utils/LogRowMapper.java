package com.epam.reportportal.utils;

import com.epam.reportportal.model.LogMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public class LogRowMapper implements RowMapper<LogMessage> {

  @Override
  public LogMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
    LogMessage logMessage = new LogMessage();
    logMessage.setId(rs.getLong("id"));
    logMessage.setLogTime(rs.getTimestamp("log_time").toLocalDateTime());
    logMessage.setLogMessage(rs.getString("log_message"));
    logMessage.setItemId(rs.getLong("item_id"));
    logMessage.setLaunchId(rs.getLong("launch_id"));
    logMessage.setProjectId(rs.getLong("project_id"));
    return logMessage;
  }
}
