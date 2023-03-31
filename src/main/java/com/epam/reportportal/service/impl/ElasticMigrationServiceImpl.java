package com.epam.reportportal.service.impl;

import com.epam.reportportal.elastic.SimpleElasticSearchClient;
import com.epam.reportportal.model.LaunchProjectId;
import com.epam.reportportal.model.LogMessage;
import com.epam.reportportal.service.ElasticMigrationService;
import com.epam.reportportal.utils.LogRowMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ElasticMigrationServiceImpl implements ElasticMigrationService {

  private final int maxLogNumber;
  private final LocalDateTime startDateTime;
  private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
  private final SimpleElasticSearchClient elasticSearchClient;
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

  private static final int DEFAULT_MAX_LOGS_NUMBER = 1000;
  private static final String SELECT_FIRST_ID = "SELECT MIN(id) FROM log";
  private static final String SELECT_ALL_LAUNCH_ID_AFTER_LAUNCH_ID =
      "SELECT id AS launch_id, project_id FROM launch WHERE id > ?";

  private static final String SELECT_ALL_TEST_ITEMS_FROM_LAUNCH =
      "SELECT test_item.item_id FROM test_item WHERE launch_id = ?";
  private static final String SELECT_LOG_ID_CLOSEST_TO_TIME =
      "SELECT id FROM log WHERE log_time >= :time ORDER BY id LIMIT 1";
  private static final String SELECT_LAUNCH_ID_BY_LOG_ID =
      "SELECT test_item.launch_id FROM test_item JOIN log ON test_item.item_id = log.item_id "
          + "WHERE log.id = ?";

  private static final String SELECT_LOGS =
      "SELECT id, log_time, log_message, item_id, launch_id, project_id\n" + "FROM log\n"
          + "WHERE launch_id IS NOT NULL\n" + "UNION\n"
          + "SELECT id, log_time, log_message, item_id, launch_id, project_id\n"
          + "FROM (SELECT l.id,\n" + "             log_time,\n" + "             log_message,\n"
          + "             l.item_id    AS item_id,\n" + "             ti.launch_id AS launch_id,\n"
          + "             project_id\n" + "      FROM log l\n"
          + "               JOIN test_item ti ON l.item_id = ti.item_id\n"
          + "      WHERE l.launch_id IS NULL\n" + "        AND retry_of IS NULL\n" + "      UNION\n"
          + "      SELECT l.id,\n" + "             log_time,\n" + "             log_message,\n"
          + "             l.item_id    AS item_id,\n" + "             ti.launch_id AS launch_id,\n"
          + "             project_id\n" + "      FROM log l\n"
          + "               JOIN test_item AS retry ON l.item_id = retry.item_id\n"
          + "               JOIN test_item ti ON retry.retry_of = ti.item_id\n"
          + "      WHERE retry.retry_of IS NOT NULL) AS t2\n" + "ORDER BY id DESC\n";

  private static final String SELECT_LOGS_BEFORE_ID =
      "SELECT id, log_time, log_message, item_id, launch_id, project_id\n" + "FROM log\n"
          + "WHERE launch_id IS NOT NULL AND id < :id\n" + "UNION\n"
          + "SELECT id, log_time, log_message, item_id, launch_id, project_id\n"
          + "FROM (SELECT l.id,\n" + "             log_time,\n" + "             log_message,\n"
          + "             l.item_id    AS item_id,\n" + "             ti.launch_id AS launch_id,\n"
          + "             project_id\n" + "      FROM log l\n"
          + "               JOIN test_item ti ON l.item_id = ti.item_id\n"
          + "      WHERE l.launch_id IS NULL\n" + "        AND retry_of IS NULL AND l.id < :id\n"
          + "      UNION\n" + "      SELECT l.id,\n" + "             log_time,\n"
          + "             log_message,\n" + "             l.item_id    AS item_id,\n"
          + "             ti.launch_id AS launch_id,\n" + "             project_id\n"
          + "      FROM log l\n"
          + "               JOIN test_item AS retry ON l.item_id = retry.item_id\n"
          + "               JOIN test_item ti ON retry.retry_of = ti.item_id\n"
          + "      WHERE retry.retry_of IS NOT NULL AND l.id < :id) AS t2\n"
          + "ORDER BY id DESC LIMIT :maxLogNumber\n";

  public ElasticMigrationServiceImpl(JdbcTemplate jdbcTemplate,
      SimpleElasticSearchClient simpleElasticSearchClient,
      @Value("${rp.migration.elastic.startDate}") String startDate,
      @Value("${rp.migration.elastic.logsNumber}") String maxLogsNumberString) {
    this.jdbcTemplate = jdbcTemplate;
    int maxLogsNumberValue =
        StringUtils.hasText(maxLogsNumberString) ? Integer.parseInt(maxLogsNumberString) : 0;
    this.maxLogNumber = maxLogsNumberValue != 0 ? maxLogsNumberValue : DEFAULT_MAX_LOGS_NUMBER;
    this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    this.elasticSearchClient = simpleElasticSearchClient;
    if (startDate != null && !startDate.isEmpty()) {
      startDateTime = LocalDateTime.parse(startDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    } else {
      startDateTime = null;
    }
  }

  @Override
  public void migrateLogs() {

    Long databaseFirstLogId = jdbcTemplate.queryForObject(SELECT_FIRST_ID, Long.class);
    LOGGER.info("Database first log id : {}", databaseFirstLogId);
    if (databaseFirstLogId == null) {
      return;
    }
    if (startDateTime != null) {
      Long closestLogId;
      try {
        closestLogId = namedParameterJdbcTemplate.queryForObject(SELECT_LOG_ID_CLOSEST_TO_TIME,
            Map.of("time", startDateTime), Long.class
        );
      } catch (EmptyResultDataAccessException e) {
        closestLogId = (long) Integer.MIN_VALUE;
      }
      LOGGER.info("Closest to start time log id : {}", closestLogId);
      compareIdsAndMigrate(databaseFirstLogId, closestLogId);
    } else {
      Optional<LogMessage> firstLogFromElastic = elasticSearchClient.getFirstLogFromElasticSearch();
      if (firstLogFromElastic.isEmpty()) {
        migrateAllLogs();
        return;
      }
      Long firstLogFromElasticId = firstLogFromElastic.get().getId();
      LOGGER.info("First log id from elastic : {}", firstLogFromElasticId);
      compareIdsAndMigrate(databaseFirstLogId, firstLogFromElasticId);
    }
  }

  private void compareIdsAndMigrate(Long databaseFirstLogId, Long startLogId) {
    int comparisonResult = databaseFirstLogId.compareTo(startLogId);
    if (comparisonResult == 0) {
      LOGGER.info("Elastic has the same logs as Postgres");

      migrateMergedLaunches(startLogId);
    } else if (comparisonResult < 0) {
      Long lastMigratedLogId = null;

      int i = 0;
      do {
        lastMigratedLogId =
            migrateLogsBeforeId(Objects.requireNonNullElse(lastMigratedLogId, startLogId));
        i++;
        if (lastMigratedLogId.equals(Long.MIN_VALUE)) {
          LOGGER.info("Iteration {}, no migrated logs", i);
        } else {
          LOGGER.info("Iteration {}, last migrated log id : {}", i, lastMigratedLogId);
        }
      } while (!lastMigratedLogId.equals(Long.MIN_VALUE));

      migrateMergedLaunches(startLogId);

      LOGGER.info("Migration completed at {}", LocalDateTime.now());
    }
  }

  private void migrateAllLogs() {
    LOGGER.info("Migrating all logs from Postgres");

    Long lastMigratedLogId = null;
    int iteration = 0;
    do {
      if (lastMigratedLogId != null) {
        do {
          lastMigratedLogId = migrateLogsBeforeId(lastMigratedLogId);
          iteration++;
          if (lastMigratedLogId.equals(Long.MIN_VALUE)) {
            LOGGER.info("Iteration {}, no migrated logs", iteration);
          } else {
            LOGGER.info("Iteration {}, last migrated log id : {}", iteration, lastMigratedLogId);
          }
        } while (!lastMigratedLogId.equals(Long.MIN_VALUE));
      } else {
        List<LogMessage> logMessages =
            namedParameterJdbcTemplate.query(SELECT_LOGS, Map.of("maxLogNumber", maxLogNumber),
                new LogRowMapper()
            );

        iteration++;
        LOGGER.info("Log messages ids : {}",
            logMessages.stream().map(LogMessage::getId).collect(Collectors.toList())
        );

        LOGGER.info("Iteration {}, last migrated log id : {}", iteration, lastMigratedLogId);

        elasticSearchClient.save(groupLogsByProject(logMessages));

        lastMigratedLogId = getLastMigratedLogId(logMessages);
      }

    } while (!lastMigratedLogId.equals(Long.MIN_VALUE));
    LOGGER.info("Migration completed at {}", LocalDateTime.now());
  }

  private Long migrateLogsBeforeId(Long id) {
    List<LogMessage> logMessages = namedParameterJdbcTemplate.query(SELECT_LOGS_BEFORE_ID,
        Map.of("id", id, "maxLogNumber", maxLogNumber), new LogRowMapper()
    );
    LOGGER.info("Log messages ids : {}",
        logMessages.stream().map(LogMessage::getId).collect(Collectors.toList())
    );
    elasticSearchClient.save(groupLogsByProject(logMessages));

    return getLastMigratedLogId(logMessages);
  }

  private TreeMap<Long, List<LogMessage>> groupLogsByProject(List<LogMessage> logMessages) {
    TreeMap<Long, List<LogMessage>> projectMap = new TreeMap<>();
    for (LogMessage logMessage : logMessages) {
      if (projectMap.containsKey(logMessage.getProjectId())) {
        projectMap.get(logMessage.getProjectId()).add(logMessage);
      } else {
        List<LogMessage> logMessageList = new ArrayList<>();
        logMessageList.add(logMessage);
        projectMap.put(logMessage.getProjectId(), logMessageList);
      }
    }
    return projectMap;
  }

  private Long getLastMigratedLogId(List<LogMessage> logMessages) {

    if (logMessages.isEmpty()) {
      return Long.MIN_VALUE;
    } else {
      return logMessages.get(logMessages.size() - 1).getId();
    }
  }

  private void migrateMergedLaunches(Long startLogId) {
    Long startLaunchId =
        jdbcTemplate.queryForObject(SELECT_LAUNCH_ID_BY_LOG_ID, Long.class, startLogId);
    LOGGER.info("Migrate merged launches starting from id : {}", startLaunchId);

    List<LaunchProjectId> launchProjectIds =
        jdbcTemplate.query(SELECT_ALL_LAUNCH_ID_AFTER_LAUNCH_ID, (rs, rowNum) -> {
          LaunchProjectId launchProjectId = new LaunchProjectId();
          launchProjectId.setLaunchId(rs.getLong("launch_id"));
          launchProjectId.setProjectId(rs.getLong("project_id"));
          return launchProjectId;
        }, startLaunchId);

    for (LaunchProjectId launchProjectId : launchProjectIds) {
      Long launch = launchProjectId.getLaunchId();
      Long projectId = launchProjectId.getProjectId();
      Optional<LogMessage> logMessage = elasticSearchClient.getLogFromLaunch(launch, projectId);
      if (logMessage.isEmpty()) {
        List<Long> testItemIds =
            jdbcTemplate.queryForList(SELECT_ALL_TEST_ITEMS_FROM_LAUNCH, Long.class, launch);
        elasticSearchClient.updateLogsLaunchIdByTestItemId(testItemIds, launch, projectId);
      }
    }
  }
}
