package com.epam.reportportal.service.impl;

import com.epam.reportportal.logging.LogMigration;
import com.epam.reportportal.model.AccessToken;
import com.epam.reportportal.model.ApiKey;
import com.epam.reportportal.service.MigrationService;
import com.epam.reportportal.utils.AccessTokenRowMapper;
import com.google.common.io.BaseEncoding;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

@Service
@Order(1)
@ConditionalOnProperty(value = "rp.token.migration", havingValue = "true")
public class TokenMigrationService implements MigrationService {

  private static final Logger logger = LoggerFactory.getLogger(TokenMigrationService.class);
  private static final String SELECT_ACCESS_TOKENS =
      "SELECT token_id, user_id FROM public.oauth_access_token";
  private static final String INSERT_API_KEYS =
      "INSERT INTO public.api_keys (name, hash, created_at, user_id) VALUES (?, ?, ?, ?)";

  private static final String DROP_OAUTH_ACCESS_TOKEN_TABLE =
      "DROP TABLE public.oauth_access_token";
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  public TokenMigrationService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  @LogMigration("Migration of old tokens to api keys")
  public void migrate() {
    List<AccessToken> tokens = jdbcTemplate.query(SELECT_ACCESS_TOKENS, new AccessTokenRowMapper());
    List<ApiKey> apiKeys = new ArrayList<>();
    for (AccessToken token : tokens) {
      apiKeys.add(createApiKeyFromAccessToken(token));
    }
    jdbcTemplate.batchUpdate(INSERT_API_KEYS, new BatchPreparedStatementSetter() {
      public void setValues(@NonNull PreparedStatement preparedStatement, int i)
          throws SQLException {
        ApiKey apiKey = apiKeys.get(i);
        preparedStatement.setString(1, apiKey.getName());
        preparedStatement.setString(2, apiKey.getHash());
        preparedStatement.setTimestamp(3, Timestamp.valueOf(apiKey.getCreatedAt()));
        preparedStatement.setLong(4, apiKey.getUserId());
      }

      public int getBatchSize() {
        return apiKeys.size();
      }
    });

    logger.info(
        "Finished token migration. {} access tokens have been migrated to API keys.",
        apiKeys.size()
    );

    jdbcTemplate.execute(DROP_OAUTH_ACCESS_TOKEN_TABLE);
    logger.info("Dropped the oauth_access_token table.");
  }

  private ApiKey createApiKeyFromAccessToken(AccessToken token) {
    String tokenId = token.getTokenId();
    String hashedKey = BaseEncoding.base16().upperCase().encode(DigestUtils.sha3_256(tokenId));
    return new ApiKey("Legacy API Key", hashedKey, LocalDateTime.now(), token.getUserId());
  }
}
