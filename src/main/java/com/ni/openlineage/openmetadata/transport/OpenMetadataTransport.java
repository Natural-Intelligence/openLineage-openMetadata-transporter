package com.ni.openlineage.openmetadata.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineageClientException;
import io.openlineage.client.transports.TokenProvider;
import io.openlineage.client.transports.Transport;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.http.Consts.UTF_8;
import static org.apache.http.HttpHeaders.*;
import static org.apache.http.entity.ContentType.APPLICATION_JSON;

@Slf4j
public final class OpenMetadataTransport extends Transport implements Closeable {

  private final CloseableHttpClient http;
  private final URI uri;
  private final String pipelineServiceName;
  private final String pipelineName;
  private final String pipelineServiceUrl;
  private @Nullable
  final TokenProvider tokenProvider;
  private @Nullable
  final String pipelineUrl;
  private @Nullable
  final String pipelineDescription;

  private final Map<LineageType, Set<String>> tableNamesCache = new ConcurrentHashMap<>();
  private final static String LAST_UPDATE_TIME = "lastUpdateTime";

  public enum LineageType {
    OUTLET,
    INLET
  }

  public OpenMetadataTransport(@NonNull final OpenMetadataConfig openMetadataConfig) {
    this(withTimeout(openMetadataConfig.getTimeout()), openMetadataConfig);
  }

  private static CloseableHttpClient withTimeout(Double timeout) {
    int timeoutMs;
    if (timeout == null) {
      timeoutMs = 5000;
    } else {
      timeoutMs = (int) (timeout * 1000);
    }

    RequestConfig config =
        RequestConfig.custom()
            .setConnectTimeout(timeoutMs)
            .setConnectionRequestTimeout(timeoutMs)
            .setSocketTimeout(timeoutMs)
            .build();
    return HttpClientBuilder.create().setDefaultRequestConfig(config).build();
  }

  public OpenMetadataTransport(
      @NonNull final CloseableHttpClient httpClient, @NonNull final OpenMetadataConfig openMetadataConfig) {
    this.http = httpClient;
    this.uri = openMetadataConfig.getUrl();
    this.tokenProvider = openMetadataConfig.getAuth();
    this.pipelineName = openMetadataConfig.getPipelineName();
    this.pipelineServiceUrl = openMetadataConfig.getPipelineServiceUrl();
    this.pipelineServiceName = getServerName(this.pipelineServiceUrl);
    this.pipelineUrl = "/tree?dag_id=" + this.pipelineName;
    this.pipelineDescription = openMetadataConfig.getPipelineDescription();
  }

  @Override
  public void emit(@NonNull OpenLineage.RunEvent runEvent) {
    try {
      if (runEvent.getEventType().equals(OpenLineage.RunEvent.EventType.START)) {
        Set<String> inputTableNames = getTableNames(runEvent.getInputs(), LineageType.INLET);
        inputTableNames.forEach(tableName -> {
          sendToOpenMetadata(tableName, LineageType.INLET);
        });
      }

      if (runEvent.getEventType().equals(OpenLineage.RunEvent.EventType.COMPLETE)) {
        Set<String> outputTableNames = getTableNames(runEvent.getOutputs(), LineageType.OUTLET);
        outputTableNames.forEach(tableName -> {
          sendToOpenMetadata(tableName, LineageType.OUTLET);
        });
      }
    } catch (Exception e) {
      log.error("failed to emit event to OpenMetadata: {}", e.getMessage(), e);
    }
  }

  private Set<String> getTableNames(List<? extends OpenLineage.Dataset> datasets, LineageType lineageType) {
    if (datasets == null) {
      return Collections.emptySet();
    }
    Set<String> tableNames = extractTableNamesFromSymlinks(datasets);

    // Handle table names from JDBC queries that don't have symlinks
    if (tableNames.isEmpty()) {
      tableNames = extractTableNamesFromDataSet(datasets);
    }

    tableNamesCache.putIfAbsent(lineageType, new HashSet<>());
    Set<String> filteredTableNames = tableNames.stream().filter(t -> !tableNamesCache.get(lineageType).contains(t)).collect(Collectors.toSet());
    tableNamesCache.computeIfPresent(lineageType, (k, v) -> {
      v.addAll(filteredTableNames);
      return v;
    });
    return filteredTableNames;
  }

  private Set<String> extractTableNamesFromSymlinks(List<? extends OpenLineage.Dataset> datasets) {
    return datasets.stream().filter(d -> d.getFacets() != null && d.getFacets().getSymlinks() != null &&
            d.getFacets().getSymlinks().getIdentifiers() != null)
        .flatMap(d -> d.getFacets().getSymlinks().getIdentifiers().stream())
        .map(i -> i.getName())
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private Set<String> extractTableNamesFromDataSet(List<? extends OpenLineage.Dataset> datasets) {
    return datasets.stream()
        .filter(d -> d != null && d.getName() != null && d.getNamespace() != null)
        .map(d -> generateTableName(d.getName(), d.getNamespace()))
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private String generateTableName(String name, String namespace) {
    if (name.contains(".")) {
      return name;
    } else {
      String dbName = extractDbNameFromUrl(namespace);
      if (dbName != null) {
        return dbName + "." + name;
      }
    }
    return null;
  }

  public void sendToOpenMetadata(String tableName, LineageType lineageType) {
    try {
      Set<String> tableIds = getTableIds(tableName);

      tableIds.forEach(tableId -> {
        createOrUpdatePipelineService();
        String pipelineId = createOrUpdatePipeline();
        createOrUpdateLineage(pipelineId, tableId, lineageType);
        if (lineageType.equals(LineageType.OUTLET)) {
          updateTableLastUpdateTime(tableId, tableName);
        }
        log.info("{} lineage was sent successfully to OpenMetadata for pipeline: {}, table: {}", lineageType, pipelineName, tableName);
        System.out.println(String.format("%s lineage was sent successfully to OpenMetadata for pipeline: %s, table: %s",
            lineageType, pipelineName, tableName));
      });
    } catch (Exception e) {
      log.error("Failed to send {} lineage to OpenMetadata for table {} pipeline {} due to: {}", lineageType, tableName, pipelineName, e.getMessage(), e);
    }
  }

  private Set<String> getTableIds(String tableName) {
    try {
      HttpGet request = createGetTableRequest(tableName);
      Map response = sendRequest(request);
      Map<String, Object> hitsResult = (Map<String, Object>) response.get("hits");
      int totalHits = Integer.parseInt(((Map<String, Object>) hitsResult.get("total")).get("value").toString());
      if (totalHits == 0) {
        log.error("Failed to get id of table {} from OpenMetadata.", tableName);
        return Collections.emptySet();
      }
      List<Map<String, Object>> tablesData = (List<Map<String, Object>>) hitsResult.get("hits");
      return tablesData.stream().map(t -> ((Map<String, Object>) t.get("_source")).get("id").toString()).collect(Collectors.toSet());

    } catch (Exception e) {
      log.error("Failed to get id of table {} from OpenMetadata: ", tableName, e);
      throw new OpenLineageClientException(e);
    }
  }

  public HttpGet createGetTableRequest(String tableName) throws Exception {
    String path = "api/v1/search/query";
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("size", "10");
    queryParams.put("q", "fullyQualifiedName:*" + tableName);
    return createGetRequest(path, queryParams);
  }

  private String createOrUpdatePipelineService() {
    try {
      HttpPut request = createPipelineServiceRequest();
      Map response = sendRequest(request);
      return response.get("id").toString();
    } catch (Exception e) {
      log.error("Failed to create/update service pipeline {} in OpenMetadata: ", pipelineServiceName, e);
      throw new OpenLineageClientException(e);
    }
  }

  private String createOrUpdatePipeline() {
    try {
      HttpPut request = createPipelineRequest();
      Map response = sendRequest(request);
      return response.get("id").toString();
    } catch (Exception e) {
      log.error("Failed to create/update pipeline {} in OpenMetadata: ", pipelineName, e);
      throw new OpenLineageClientException(e);
    }
  }

  private void createOrUpdateLineage(String pipelineId, String tableId, LineageType lineageType) {
    try {
      HttpPut request = createLineageRequest(pipelineId, tableId, lineageType);
      sendRequest(request);
    } catch (Exception e) {
      log.error("Failed to create/update lineage in OpenMetadata for pipeline id {} and tableId {}: ", pipelineId, tableId, e);
      throw new OpenLineageClientException(e);
    }
  }

  private boolean isLastUpdateTimeCustomPropertyExists() throws Exception {
    String path = "api/v1/metadata/types/name/table";
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("fields", "customProperties");
    HttpGet request = createGetRequest(path, queryParams);
    Map<String, Object> response = (Map<String, Object>) sendRequest(request);
    List<Map<String, Object>> customProperties = (List<Map<String, Object>>) response.get("customProperties");
    if (customProperties.stream().filter(c -> c.get("name").equals(LAST_UPDATE_TIME)).count() > 0) {
      return true;
    }
    return false;
  }

  private void createLastUpdateTimeCustomProperty() throws Exception {
    String tableEntityId = getTableEntityId();
    String stringTypeId = getStringTypeId();
    HttpPut request = createLastUpdateTimeCustomPropertyRequest(tableEntityId, stringTypeId);
    sendRequest(request);
  }

  private String getTableEntityId() throws Exception {
    String path = "api/v1/metadata/types/name/table";
    HttpGet request = createGetRequest(path, null);
    Map<String, Object> response = (Map<String, Object>) sendRequest(request);
    return response.get("id").toString();
  }

  private String getStringTypeId() throws Exception {
    String path = "api/v1/metadata/types";
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("category", "field");
    HttpGet request = createGetRequest(path, queryParams);
    Map<String, Object> response = (Map<String, Object>) sendRequest(request);
    List<Map<String, Object>> dataTypes = (List<Map<String, Object>>) response.get("data");
    return dataTypes.stream().filter(d -> d.get("name").equals("string")).findFirst().map(d -> d.get("id").toString()).orElse(null);
  }

  public HttpPut createLastUpdateTimeCustomPropertyRequest(String tableEntityId, String stringTypeId) throws Exception {
    Map requestMap = new HashMap<>();
    requestMap.put("description", "Table's last update time");
    requestMap.put("name", LAST_UPDATE_TIME);

    Map propertyTypeMap = new HashMap<>();
    propertyTypeMap.put("id", stringTypeId);
    propertyTypeMap.put("type", "string");

    requestMap.put("propertyType", propertyTypeMap);
    String jsonRequest = toJsonString(requestMap);
    return createPutRequest("/api/v1/metadata/types/" + tableEntityId, jsonRequest);
  }

  private void updateTableLastUpdateTime(String tableId, String tableName) {
    try {
      if (!isLastUpdateTimeCustomPropertyExists()) {
        createLastUpdateTimeCustomProperty();
      }
      sendUpdateTableLastUpdateTimeRequest(tableId, tableName);
    } catch (Exception e) {
      log.error("Failed to update last update time in OpenMetadata for table {} due to error: {}", tableName, e.getMessage(), e);
    }
  }

  private void sendUpdateTableLastUpdateTimeRequest(String tableId, String tableName) throws Exception {
    String url = this.uri + "/api/v1/tables/" + tableId;
    String currentTime = LocalDateTime.now() + " UTC";
    String jsonPatchPayload = "[" +
        " {" +
        "    \"op\": \"add\"," +
        "    \"path\": \"/extension\"," +
        "    \"value\": {" +
        "      \"" + LAST_UPDATE_TIME + "\": \"" + currentTime + "\"" +
        "    }" +
        "  }" +
        "]";
    createPatchRequest(url, jsonPatchPayload);
  }

  private Map sendRequest(HttpRequestBase request) throws IOException {
    try (CloseableHttpResponse response = http.execute(request)) {
      throwOnHttpError(response);
      String jsonResponse = EntityUtils.toString(response.getEntity());
      return fromJsonString(jsonResponse);
    }
  }

  public HttpPut createLineageRequest(String pipelineId, String tableId, LineageType lineageType) throws Exception {
    Map edgeMap = new HashMap<>();
    if (lineageType == LineageType.OUTLET) {
      edgeMap.put("fromEntity", createEntityMap("pipeline", pipelineId));
      edgeMap.put("toEntity", createEntityMap("table", tableId));
    } else {
      edgeMap.put("toEntity", createEntityMap("pipeline", pipelineId));
      edgeMap.put("fromEntity", createEntityMap("table", tableId));
    }

    Map requestMap = new HashMap<>();
    requestMap.put("edge", edgeMap);

    String jsonRequest = toJsonString(requestMap);
    return createPutRequest("/api/v1/lineage", jsonRequest);
  }

  private String toJsonString(Map map) throws JsonProcessingException {
    ObjectMapper objectMapper = new ObjectMapper();
    return objectMapper.writeValueAsString(map);
  }

  private Map fromJsonString(String jsonString) throws JsonProcessingException {
    if (jsonString == null || jsonString.isEmpty()) {
      return null;
    }
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(jsonString, Map.class);
  }

  private Map createEntityMap(String type, String id) {
    Map entityMap = new HashMap();
    entityMap.put("type", type);
    entityMap.put("id", id);
    return entityMap;
  }

  public HttpGet createGetRequest(String path, Map<String, String> queryParams) throws Exception {
    return (HttpGet) createHttpRequest(HttpGet::new, path, queryParams);
  }

  public HttpPut createPutRequest(String path, String jsonRequest) throws URISyntaxException, MalformedURLException {
    HttpPut request = (HttpPut) createHttpRequest(HttpPut::new, path, null);
    request.setEntity(new StringEntity(jsonRequest, APPLICATION_JSON));
    return request;
  }

  public void createPatchRequest(String url, String jsonPayload) throws Exception {
    HttpClient client = HttpClientBuilder.create().build();
    HttpPatch patchRequest = new HttpPatch(url);

    patchRequest.setHeader(CONTENT_TYPE, "application/json-patch+json");
    if (tokenProvider != null) {
      patchRequest.setHeader(AUTHORIZATION, tokenProvider.getToken());
    }
    patchRequest.setEntity(new StringEntity(jsonPayload));

    HttpResponse response = client.execute(patchRequest);
    int statusCode = response.getStatusLine().getStatusCode();
    String jsonResponse = EntityUtils.toString(response.getEntity());
    if (statusCode != 200) {
      throw new Exception("Patch request to OpenMetadata failed with status " + statusCode + ": " + jsonResponse);
    }
  }

  private HttpRequestBase createHttpRequest(Supplier<HttpRequestBase> supplier, String path,
                                            Map<String, String> queryParams) throws URISyntaxException, MalformedURLException {
    URIBuilder uriBuilder = new URIBuilder(this.uri);
    uriBuilder.setPath(path);
    if (queryParams != null) {
      queryParams.entrySet().forEach(e -> uriBuilder.addParameter(e.getKey(), e.getValue()));
    }
    URI omUri = uriBuilder.build();
    final HttpRequestBase request = supplier.get();
    request.setURI(omUri);
    request.addHeader(ACCEPT, APPLICATION_JSON.toString());
    request.addHeader(CONTENT_TYPE, APPLICATION_JSON.toString());

    if (tokenProvider != null) {
      request.addHeader(AUTHORIZATION, tokenProvider.getToken());
    }
    return request;
  }

  private static String getServerName(String pipelineServiceUrl) {
    try {
      URL url = new URL(pipelineServiceUrl);
      String host = url.getHost();
      int firstDot = host.indexOf('.');
      if (firstDot > 0) {
        return host.substring(0, firstDot);
      } else {
        return host;
      }
    } catch (Exception e) {
      log.error("Failed to extract hostname from url {}", pipelineServiceUrl);
    }
    return null;
  }

  public HttpPut createPipelineServiceRequest() throws Exception {
    Map requestMap = new HashMap<>();
    requestMap.put("name", pipelineServiceName);
    requestMap.put("serviceType", "Airflow");

    Map connectionConfig = new HashMap<>();
    connectionConfig.put("config", new HashMap<String, String>() {{
      put("type", "Airflow");
      put("hostPort", pipelineServiceUrl);
    }});
    requestMap.put("connection", connectionConfig);
    String jsonRequest = toJsonString(requestMap);
    return createPutRequest("/api/v1/services/pipelineServices", jsonRequest);
  }

  public HttpPut createPipelineRequest() throws Exception {
    Map requestMap = new HashMap<>();
    requestMap.put("name", pipelineName);
    requestMap.put("pipelineUrl", pipelineUrl);

    if (pipelineDescription != null && !pipelineDescription.isEmpty()) {
      requestMap.put("description", pipelineDescription);
    }
    requestMap.put("service", pipelineServiceName);
    String jsonRequest = toJsonString(requestMap);
    return createPutRequest("/api/v1/pipelines", jsonRequest);
  }

  public String extractDbNameFromUrl(String url) {
    if (url != null) {
      if (url.startsWith("redshift")) {
        return "public";
      }
      Pattern pattern = Pattern.compile("^[^:]+://[^/]+:[0-9]+/([^?]+)");
      Matcher matcher = pattern.matcher(url);

      if (matcher.find()) {
        return matcher.group(1);
      } else {
        log.warn("OpenLineageTransport error: Invalid URL or unable to extract database name: " + url);
      }
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    http.close();
  }

  private void throwOnHttpError(@NonNull HttpResponse response) throws IOException {
    final int code = response.getStatusLine().getStatusCode();
    if (code >= 400 && code < 600) { // non-2xx
      String message =
          String.format(
              "code: %d, response: %s", code, EntityUtils.toString(response.getEntity(), UTF_8));

      throw new OpenLineageClientException(message);
    }
  }
}
