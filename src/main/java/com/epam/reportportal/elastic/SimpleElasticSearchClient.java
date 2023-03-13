package com.epam.reportportal.elastic;

import com.epam.reportportal.model.LogMessage;
import com.epam.reportportal.model.SearchResponse;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Simple client to work with Elasticsearch.
 *
 * @author <a href="mailto:maksim_antonov@epam.com">Maksim Antonov</a>
 */
@Service
public class SimpleElasticSearchClient {

  private final static String SEARCH_QUERY_JSON =
      "{\"query\":{\"match_all\":{}},\"size\":1,\"sort\":[{\"id\":{\"order\":\"asc\"}}]}";

  private final static String SEARCH_BY_LAUNCH_ID_JSON =
      "{\n\"query\":{\n\"term\":{\n\"launchId\":?\n}\n}\n}";

  private final String host;
  private final RestTemplate restTemplate;

  public SimpleElasticSearchClient(@Value("${rp.elasticsearch.host}") String host,
      @Value("${rp.elasticsearch.username}") String username,
      @Value("${rp.elasticsearch.password}") String password) {
    restTemplate = new RestTemplate();
    restTemplate.getInterceptors().add(new BasicAuthenticationInterceptor(username, password));

    this.host = host;
  }

  public void save(TreeMap<Long, List<LogMessage>> logMessageMap) {
    if (CollectionUtils.isEmpty(logMessageMap)) {
      return;
    }
    String create = "{\"create\":{ }}\n";

    logMessageMap.descendingMap().forEach((projectId, logMessageList) -> {
      String indexName = "logs-reportportal-" + logMessageList.get(0).getProjectId();
      StringBuilder jsonBodyBuilder = new StringBuilder();
      for (LogMessage logMessage : logMessageList) {
        jsonBodyBuilder.append(create).append(convertToJson(logMessage)).append("\n");
      }
      restTemplate.put(host + "/" + indexName + "/_bulk?refresh",
          getStringHttpEntity(jsonBodyBuilder.toString())
      );
    });
  }

  public Optional<LogMessage> getFirstLogFromElasticSearch() {

    SearchResponse searchResponse =
        restTemplate.postForObject(host + "/_search", getStringHttpEntity(SEARCH_QUERY_JSON),
            SearchResponse.class
        );

    if (searchResponse != null && searchResponse.getHits() != null
        && searchResponse.getHits().getHits() != null && !searchResponse.getHits().getHits()
        .isEmpty()) {
      return Optional.of(searchResponse.getHits().getHits().get(0).getSource());
    }

    return Optional.empty();
  }

  public Optional<LogMessage> getLogFromLaunch(Long launchId, Long projectId) {
    String indexName = "logs-reportportal-" + projectId;
    String query = SEARCH_BY_LAUNCH_ID_JSON.replace("?", launchId.toString());
    SearchResponse searchResponse =
        restTemplate.postForObject(host + "/" + indexName + "/_search?size=1",
            getStringHttpEntity(query), SearchResponse.class
        );

    if (searchResponse != null && !CollectionUtils.isEmpty(searchResponse.getHits().getHits())) {
      return Optional.of(searchResponse.getHits().getHits().get(0).getSource());
    } else {
      return Optional.empty();
    }
  }

  public void updateLogsLaunchIdByTestItemId(List<Long> testItemIds, Long launchId,
      Long projectId) {
    String indexName = "logs-reportportal-" + projectId;
    if (CollectionUtils.isEmpty(testItemIds)) {
      return;
    }

    String logUpdateBody = getUpdateLaunchIdJson(testItemIds, launchId).toString();

    restTemplate.postForEntity(host + "/" + indexName + "/_update_by_query",
        getStringHttpEntity(logUpdateBody), String.class
    );
  }

  private JSONObject convertToJson(LogMessage logMessage) {
    JSONObject personJsonObject = new JSONObject();
    personJsonObject.put("id", logMessage.getId());
    personJsonObject.put("message", logMessage.getLogMessage());
    personJsonObject.put("itemId", logMessage.getItemId());
    personJsonObject.put("@timestamp", logMessage.getLogTime());
    personJsonObject.put("launchId", logMessage.getLaunchId());

    return personJsonObject;
  }

  private JSONObject getUpdateLaunchIdJson(List<Long> testItemIds, Long newLaunchId) {
    JSONObject body = new JSONObject();
    body.put("script", "ctx._source.launchId = " + newLaunchId);

    JSONArray testItemArray = new JSONArray(testItemIds);

    JSONObject terms = new JSONObject();
    terms.put("itemId", testItemArray);

    JSONObject query = new JSONObject();
    query.put("terms", terms);

    body.put("query", query);

    return body;
  }

  private HttpEntity<String> getStringHttpEntity(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    return new HttpEntity<>(body, headers);
  }

}
