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

package ai.pipestream.search.embeddings.tei;

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
 * {@link RerankProvider} client for Text Embeddings Inference's {@code POST /rerank}: one
 * request carries the query and all documents, and the response lists {@code {index, score}}
 * pairs sorted by score — this class maps them back to input order.
 *
 * <p>Configuration mirrors {@link TEIEmbeddingProvider}: the no-arg constructor (ServiceLoader)
 * reads {@value #ENDPOINT_PROPERTY}/{@value #ENDPOINT_ENV_VAR} (base URL) and
 * {@value #MODELS_PROPERTY}/{@value #MODELS_ENV_VAR} (comma-separated); with no configuration
 * the provider supports nothing and is inert. A TEI container serves either an embedding model
 * or a reranker, so point this at a reranker endpoint, not an embedding one.
 */
public final class TEIRerankProvider implements RerankProvider, AutoCloseable {

  /** Provider id used for registration and lookup: {@value}. */
  public static final String NAME = "tei";

  /** System property naming the TEI reranker base URL: {@value}. */
  public static final String ENDPOINT_PROPERTY = "ai.pipestream.search.rerank.tei.endpoint";

  /** Environment variable naming the TEI reranker base URL: {@value}. */
  public static final String ENDPOINT_ENV_VAR = "TEI_RERANK_ENDPOINT";

  /** System property naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_PROPERTY = "ai.pipestream.search.rerank.tei.models";

  /** Environment variable naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_ENV_VAR = "TEI_RERANK_MODELS";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Set<String> models;
  private final String baseUrl;
  private final HttpClient client;

  /** ServiceLoader entry point; configuration via the properties/env vars above. */
  public TEIRerankProvider() {
    this(configuredEndpoint(), configuredModels());
  }

  /**
   * Create a provider over one TEI reranker endpoint.
   *
   * @param baseUrl the TEI base URL, e.g. {@code http://localhost:8080}; may be null, which makes
   *     the provider inert
   * @param models the model ids routed to this endpoint
   */
  public TEIRerankProvider(String baseUrl, Collection<String> models) {
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
    StringBuilder body = new StringBuilder("{\"query\":");
    body.append(MAPPER.valueToTree(query).toString()).append(",\"texts\":[");
    for (int i = 0; i < documents.size(); i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append(MAPPER.valueToTree(documents.get(i)).toString());
    }
    body.append("]}");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/rerank"))
            .timeout(Duration.ofSeconds(60))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    JsonNode ranked;
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "TEI rerank at " + baseUrl + " answered " + response.statusCode() + ": "
                + abbreviate(response.body()));
      }
      ranked = MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new UncheckedIOException("TEI rerank call to " + baseUrl + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted calling TEI rerank at " + baseUrl, e);
    }
    if (!ranked.isArray() || ranked.size() != documents.size()) {
      throw new IllegalStateException(
          "TEI rerank at " + baseUrl + " returned "
              + (ranked.isArray() ? ranked.size() : "non-array") + " scores for "
              + documents.size() + " documents");
    }
    // The response is score-sorted {index, score} pairs; restore input order.
    List<Float> scores = new ArrayList<>(java.util.Collections.nCopies(documents.size(), 0f));
    for (JsonNode entry : ranked) {
      scores.set(entry.get("index").asInt(), (float) entry.get("score").asDouble());
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
