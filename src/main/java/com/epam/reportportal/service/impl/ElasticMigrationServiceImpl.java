package com.epam.reportportal.service.impl;

import com.epam.reportportal.elastic.SimpleElasticSearchClient;
import com.epam.reportportal.model.LogMessage;
import com.epam.reportportal.service.ElasticMigrationService;
import com.epam.reportportal.utils.LogRowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ElasticMigrationServiceImpl implements ElasticMigrationService {

	private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
	private final SimpleElasticSearchClient elasticSearchClient;
	private final JdbcTemplate jdbcTemplate;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private static final String SELECT_FIRST_LOG_TIME = "SELECT MIN(log_time) FROM log";
	private static final String SELECT_ALL_LOGS_WITH_LAUNCH_ID = "SELECT id, log_time, log_message, item_id, launch_id, project_id FROM log WHERE launch_id IS NOT NULL ORDER BY log_time ";
	private static final String SELECT_ALL_LOGS_WITHOUT_LAUNCH_ID =
			"SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id "
					+ "UNION SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE retry_of IS NOT NULL AND retry_of IN (SELECT item_id FROM test_item)";
	private static final String SELECT_LOGS_WITH_LAUNCH_ID_BEFORE_DATE =
			"SELECT id, log_time, log_message, item_id, launch_id, project_id FROM log WHERE launch_id IS NOT NULL AND log_time < ?"
					+ " ORDER BY log_time";
	private static final String SELECT_LOGS_WITHOUT_LAUNCH_ID_BEFORE_DATE =
			"SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE l.log_time < :time "
					+ "UNION SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE retry_of IS NOT NULL AND retry_of IN (SELECT item_id FROM test_item "
					+ "WHERE ti.start_time < :time)";

	public ElasticMigrationServiceImpl(JdbcTemplate jdbcTemplate, SimpleElasticSearchClient simpleElasticSearchClient){
		this.jdbcTemplate = jdbcTemplate;
		this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
		this.elasticSearchClient = simpleElasticSearchClient;
	}

	@Override
	public void migrateLogs() {
		Timestamp databaseFirstLogFromTimestamp = jdbcTemplate.queryForObject(SELECT_FIRST_LOG_TIME, Timestamp.class);
		if (databaseFirstLogFromTimestamp == null) {
			return;
		}
		LocalDateTime databaseLastLogTime = databaseFirstLogFromTimestamp.toLocalDateTime();
		Optional<LogMessage> firstLogFromElastic = elasticSearchClient.getFirstLogFromElasticSearch();
		if (firstLogFromElastic.isEmpty()) {
			migrateAllLogs();
			return;
		}
		LocalDateTime elasticFirstLogTime = firstLogFromElastic.get().getLogTime();
		if (elasticFirstLogTime == null) {
			migrateAllLogs();
			return;
		}
		int comparisonResult = databaseLastLogTime.compareTo(elasticFirstLogTime);
		if (comparisonResult == 0) {
			LOGGER.info("Elastic has the same logs as Postgres");
		} else if (comparisonResult < 0){
			migrateLogsBeforeDate(elasticFirstLogTime);
		}
	}

	private void migrateAllLogs() {
		LOGGER.info("Migrating all logs from Postgres");
		List<LogMessage> logMessageWithLaunchIdList = jdbcTemplate.query(SELECT_ALL_LOGS_WITH_LAUNCH_ID, new LogRowMapper());
		List<Long> launchIds = logMessageWithLaunchIdList.stream().map(LogMessage::getLaunchId).distinct().collect(Collectors.toList());
		List<LogMessage> logMessageWithoutLaunchIdList = namedParameterJdbcTemplate.query(SELECT_ALL_LOGS_WITHOUT_LAUNCH_ID,
				Map.of("ids", launchIds),
				new LogRowMapper()
		);
		elasticSearchClient.save(createIndexMap(logMessageWithLaunchIdList, logMessageWithoutLaunchIdList));
		LOGGER.info("Migration completed at {}", LocalDateTime.now());
	}

	private void migrateLogsBeforeDate(LocalDateTime date) {
		LOGGER.info("Migrating logs before {}", date);
		List<LogMessage> logMessageWithLaunchIdList = jdbcTemplate.query(SELECT_LOGS_WITH_LAUNCH_ID_BEFORE_DATE, new LogRowMapper(), date);
		List<LogMessage> logMessageWithoutLaunchIdList = namedParameterJdbcTemplate.query(
				SELECT_LOGS_WITHOUT_LAUNCH_ID_BEFORE_DATE,
				Map.of("time", date),
				new LogRowMapper()
		);
		elasticSearchClient.save(createIndexMap(logMessageWithLaunchIdList, logMessageWithoutLaunchIdList));
		LOGGER.info("Migration completed at {}", LocalDateTime.now());
	}

	private TreeMap<Long, List<LogMessage>> createIndexMap(List<LogMessage> logsWithLaunchId, List<LogMessage> logsWithoutLaunchId) {
		TreeMap<Long, List<LogMessage>> indexMap = new TreeMap<>();
		for (LogMessage logMessage : logsWithLaunchId) {
			List<LogMessage> logMessageList = new ArrayList<>();
			logMessageList.add(logMessage);
			indexMap.put(logMessage.getLaunchId(), logMessageList);
		}
		for (LogMessage logMessage : logsWithoutLaunchId) {
			if (indexMap.containsKey(logMessage.getLaunchId())) {
				indexMap.get(logMessage.getLaunchId()).add(logMessage);
			} else {
				List<LogMessage> logMessageList = new ArrayList<>();
				logMessageList.add(logMessage);
				indexMap.put(logMessage.getLaunchId(), logMessageList);
			}
		}
		return indexMap;
	}
}
