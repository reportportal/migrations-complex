package com.epam.reportportal.utils;

import com.epam.reportportal.model.Plugin;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public class PluginRowMapper implements RowMapper<Plugin> {
  @Override
  public Plugin mapRow(ResultSet rs, int rowNum) throws SQLException {
    Plugin plugin = new Plugin();
    plugin.setId(rs.getLong("id"));
    plugin.setDetails(rs.getString("details"));

    return plugin;
  }
}
