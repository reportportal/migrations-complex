package com.epam.reportportal.repository;

import com.epam.reportportal.model.MigrationState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MigrationStateRepository {

  private static final String UPSERT =
      "INSERT INTO public.migration_state"
          + " (migration_type, entity_type, entity_id, source_bucket, source_key,"
          + "  dest_bucket, dest_key, status, attempts, error_message, updated_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())"
          + " ON CONFLICT (migration_type, source_bucket, source_key)"
          + " DO UPDATE SET status = EXCLUDED.status, attempts = EXCLUDED.attempts,"
          + "   error_message = EXCLUDED.error_message, updated_at = NOW()";

  private static final String SELECT_BY_STATUS =
      "SELECT * FROM public.migration_state"
          + " WHERE migration_type = ? AND status = ? ORDER BY id";

  private static final String SELECT_BY_KEY =
      "SELECT * FROM public.migration_state"
          + " WHERE migration_type = ? AND source_bucket = ? AND source_key = ?";

  private static final String COUNT_BY_STATUS =
      "SELECT status, COUNT(*) FROM public.migration_state"
          + " WHERE migration_type = ? GROUP BY status";

  private static final String CREATE_TABLE =
      "CREATE TABLE IF NOT EXISTS public.migration_state ("
          + " id BIGSERIAL PRIMARY KEY,"
          + " migration_type VARCHAR(64) NOT NULL,"
          + " entity_type VARCHAR(32) NOT NULL,"
          + " entity_id BIGINT,"
          + " source_bucket VARCHAR(256),"
          + " source_key VARCHAR(1024),"
          + " dest_bucket VARCHAR(256),"
          + " dest_key VARCHAR(1024),"
          + " status VARCHAR(16) NOT NULL DEFAULT 'PENDING',"
          + " attempts INT NOT NULL DEFAULT 0,"
          + " error_message TEXT,"
          + " created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
          + " updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
          + ")";

  private static final String CREATE_INDEXES =
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_migration_state_unique_key"
          + " ON public.migration_state(migration_type, source_bucket, source_key);"
          + " CREATE INDEX IF NOT EXISTS idx_migration_state_status"
          + " ON public.migration_state(status)";

  private static final RowMapper<MigrationState> ROW_MAPPER = (rs, rowNum) -> {
    MigrationState s = new MigrationState();
    s.setId(rs.getLong("id"));
    s.setMigrationType(rs.getString("migration_type"));
    s.setEntityType(rs.getString("entity_type"));
    long entityId = rs.getLong("entity_id");
    s.setEntityId(rs.wasNull() ? null : entityId);
    s.setSourceBucket(rs.getString("source_bucket"));
    s.setSourceKey(rs.getString("source_key"));
    s.setDestBucket(rs.getString("dest_bucket"));
    s.setDestKey(rs.getString("dest_key"));
    s.setStatus(rs.getString("status"));
    s.setAttempts(rs.getInt("attempts"));
    s.setErrorMessage(rs.getString("error_message"));
    s.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
    s.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
    return s;
  };

  private final JdbcTemplate jdbcTemplate;

  public MigrationStateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void ensureTable() {
    jdbcTemplate.execute(CREATE_TABLE);
    jdbcTemplate.execute(CREATE_INDEXES);
  }

  public void save(MigrationState state) {
    jdbcTemplate.update(UPSERT,
        state.getMigrationType(), state.getEntityType(), state.getEntityId(),
        state.getSourceBucket(), state.getSourceKey(),
        state.getDestBucket(), state.getDestKey(),
        state.getStatus(), state.getAttempts(), state.getErrorMessage());
  }

  public Optional<MigrationState> findByKey(String migrationType, String sourceBucket,
      String sourceKey) {
    List<MigrationState> results =
        jdbcTemplate.query(SELECT_BY_KEY, ROW_MAPPER, migrationType, sourceBucket, sourceKey);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * Batch-load migration rows for many source keys (same migration type and bucket).
   * Returns map {@code source_key -> state}; keys with no row are omitted.
   */
  public Map<String, MigrationState> findByBucketAndKeys(String migrationType, String sourceBucket,
      Collection<String> sourceKeys) {
    if (sourceKeys == null || sourceKeys.isEmpty()) {
      return Collections.emptyMap();
    }
    List<String> distinctKeys = new ArrayList<>(new LinkedHashSet<>(sourceKeys));
    String inClause = distinctKeys.stream().map(k -> "?").collect(Collectors.joining(","));
    String sql = "SELECT * FROM public.migration_state WHERE migration_type = ? AND source_bucket = ?"
        + " AND source_key IN (" + inClause + ")";
    List<Object> argList = new ArrayList<>();
    argList.add(migrationType);
    argList.add(sourceBucket);
    argList.addAll(distinctKeys);
    Map<String, MigrationState> out = new HashMap<>();
    for (MigrationState s : jdbcTemplate.query(sql, ROW_MAPPER, argList.toArray())) {
      out.put(s.getSourceKey(), s);
    }
    return out;
  }

  public List<MigrationState> findByStatus(String migrationType, String status) {
    return jdbcTemplate.query(SELECT_BY_STATUS, ROW_MAPPER, migrationType, status);
  }

  public void logSummary(String migrationType, Logger logger) {
    jdbcTemplate.query(COUNT_BY_STATUS, rs -> {
      logger.info("  {} = {}", rs.getString(1), rs.getInt(2));
    }, migrationType);
  }
}
