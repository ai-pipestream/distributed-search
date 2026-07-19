/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.pipestream.search.embeddings.ovms;

import ai.pipestream.search.embeddings.RerankProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * {@link RerankProvider} client for OpenVINO Model Server's Cohere-compatible
 * {@code POST /v3/rerank}: one request carries the model, the query, and all documents, and
 * the response lists {@code {index, relevance_score}} results — this class maps them back to
 * input order.
 *
 * <p>The rerank graph is a REST-first servable: its KServe v2 gRPC surface takes the same JSON
 * as an opaque payload, so this client stays on the documented REST API. Embeddings over gRPC
 * keep their own path via {@code KServeEmbeddingProvider}.
 *
 * <p>Configuration mirrors the other providers: the no-arg constructor (ServiceLoader) reads
 * {@value #ENDPOINT_PROPERTY}/{@value #ENDPOINT_ENV_VAR} (base URL) and
 * {@value #MODELS_PROPERTY}/{@value #MODELS_ENV_VAR} (comma-separated); with no configuration
 * the provider supports nothing and is inert.
 */
public final class OvmsRerankProvider implements RerankProvider, AutoCloseable {

  /** Provider id used for registration and lookup: {@value}. */
  public static final String NAME = "ovms";

  /** System property naming the OVMS reranker base URL: {@value}. */
  public static final String ENDPOINT_PROPERTY = "ai.pipestream.search.rerank.ovms.endpoint";

  /** Environment variable naming the OVMS reranker base URL: {@value}. */
  public static final String ENDPOINT_ENV_VAR = "OVMS_RERANK_ENDPOINT";

  /** System property naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_PROPERTY = "ai.pipestream.search.rerank.ovms.models";

  /** Environment variable naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_ENV_VAR = "OVMS_RERANK_MODELS";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Set<String> models;
  private final String baseUrl;
  private final HttpClient client;

  /** ServiceLoader entry point; configuration via the properties/env vars above. */
  public OvmsRerankProvider() {
    this(configuredEndpoint(), configuredModels());
  }

  /**
   * Create a provider over one OVMS reranker endpoint.
   *
   * @param baseUrl the OVMS base URL, e.g. {@code http://localhost:8000}; may be null, which
   *     makes the provider inert
   * @param models the model ids routed to this endpoint
   */
  public OvmsRerankProvider(String baseUrl, Collection<String> models) {
    this.baseUrl = baseUrl == null ? null : baseUrl.replaceAll("/+$", "");
    this.models = Set.copyOf(models);
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  private static String configuredEndpoint() {
    String endpoint = System.getProperty(ENDPOINT_PROPERTY);
    if (endpoint == null || endpoint.isBlank()) {
      endpoint = System.getenv(ENDPOINT_ENV_VAR);
    }
    return endpoint;
  }

  private static Set<String> configuredModels() {
    String models = System.getProperty(MODELS_PROPERTY);
    if (models == null || models.isBlank()) {
      models = System.getenv(MODELS_ENV_VAR);
    }
    if (models == null || models.isBlank()) {
      return Set.of();
    }
    return Set.of(models.trim().split("\\s*,\\s*"));
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean supports(String model) {
    return baseUrl != null && models.contains(model);
  }

  @Override
  public List<Float> score(String model, String query, List<String> documents) {
    if (!supports(model)) {
      throw new IllegalArgumentException(
          "unknown model '" + model + "'; registered: " + models);
    }
    if (documents.isEmpty()) {
      return List.of();
    }
    StringBuilder body = new StringBuilder("{\"model\":");
    body.append(MAPPER.valueToTree(model).toString()).append(",\"query\":");
    body.append(MAPPER.valueToTree(query).toString()).append(",\"documents\":[");
    for (int i = 0; i < documents.size(); i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append(MAPPER.valueToTree(documents.get(i)).toString());
    }
    body.append("]}");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/v3/rerank"))
            .timeout(Duration.ofSeconds(60))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    JsonNode results;
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "OVMS rerank at " + baseUrl + " answered " + response.statusCode() + ": "
                + abbreviate(response.body()));
      }
      results = MAPPER.readTree(response.body()).path("results");
    } catch (IOException e) {
      throw new UncheckedIOException("OVMS rerank call to " + baseUrl + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted calling OVMS rerank at " + baseUrl, e);
    }
    if (!results.isArray() || results.size() != documents.size()) {
      throw new IllegalStateException(
          "OVMS rerank at " + baseUrl + " returned "
              + (results.isArray() ? results.size() : "non-array") + " results for "
              + documents.size() + " documents");
    }
    // Results carry {index, relevance_score}; restore input order.
    List<Float> scores = new ArrayList<>(java.util.Collections.nCopies(documents.size(), 0f));
    for (JsonNode entry : results) {
      scores.set(entry.get("index").asInt(), (float) entry.get("relevance_score").asDouble());
    }
    return scores;
  }

  private static String abbreviate(String body) {
    return body.length() <= 200 ? body : body.substring(0, 200) + "...";
  }

  @Override
  public void close() {
    client.close();
  }
}
