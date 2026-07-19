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

import ai.pipestream.search.embeddings.EmbeddingProvider;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EmbeddingProvider} client for Text Embeddings Inference over its HTTP/JSON API
 * ({@code POST /embed}): the batched-call transport. TEI batches a whole request server-side,
 * which makes this the fastest correct way to serve this interface's batch contract — TEI's
 * gRPC API takes one text per message and returns stream responses out of order (measured), so
 * a correct gRPC batch is lockstep and slower. For per-worker streaming ingestion, see
 * {@code TEIGrpcEmbeddingProvider}.
 *
 * <p>Server note: TEI rejects a client batch larger than its {@code max_client_batch_size}
 * (default 32) with HTTP 422 — hosts should cap batches at 32 and use concurrency for
 * throughput.
 *
 * <p>Configuration mirrors the other providers: the no-arg constructor (ServiceLoader) reads
 * {@value #ENDPOINT_PROPERTY}/{@value #ENDPOINT_ENV_VAR} (base URL) and
 * {@value #MODELS_PROPERTY}/{@value #MODELS_ENV_VAR} (comma-separated); with no configuration
 * the provider supports nothing and is inert.
 *
 * <p>{@link #dims(String)} is probed (one short embed, cached): TEI does not report the
 * embedding width over the wire.
 */
public final class TEIEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

  /** Provider id used for registration and lookup: {@value}. */
  public static final String NAME = "tei";

  /** System property naming the TEI base URL: {@value}. */
  public static final String ENDPOINT_PROPERTY = "ai.pipestream.search.embeddings.tei.endpoint";

  /** Environment variable naming the TEI base URL: {@value}. */
  public static final String ENDPOINT_ENV_VAR = "TEI_ENDPOINT";

  /** System property naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_PROPERTY = "ai.pipestream.search.embeddings.tei.models";

  /** Environment variable naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_ENV_VAR = "TEI_MODELS";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Set<String> models;
  private final String baseUrl;
  private final HttpClient client;
  private final ConcurrentHashMap<String, Integer> dimsCache = new ConcurrentHashMap<>();

  /** ServiceLoader entry point; configuration via the properties/env vars above. */
  public TEIEmbeddingProvider() {
    this(configuredEndpoint(), configuredModels());
  }

  /**
   * Create a provider over one TEI endpoint.
   *
   * @param baseUrl the TEI base URL, e.g. {@code http://localhost:8080}; may be null, which makes
   *     the provider inert
   * @param models the model ids routed to this endpoint
   */
  public TEIEmbeddingProvider(String baseUrl, Collection<String> models) {
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
  public int dims(String model) {
    requireSupported(model);
    return dimsCache.computeIfAbsent(model, id -> embed(id, List.of("dimension probe")).get(0).length);
  }

  @Override
  public List<float[]> embed(String model, List<String> texts) {
    requireSupported(model);
    if (texts.isEmpty()) {
      return List.of();
    }
    StringBuilder body = new StringBuilder("{\"inputs\":[");
    for (int i = 0; i < texts.size(); i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append(MAPPER.valueToTree(texts.get(i)).toString());
    }
    body.append("]}");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/embed"))
            .timeout(Duration.ofSeconds(30))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    JsonNode vectors;
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "TEI at " + baseUrl + " answered " + response.statusCode() + ": "
                + abbreviate(response.body()));
      }
      vectors = MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new UncheckedIOException("TEI call to " + baseUrl + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted calling TEI at " + baseUrl, e);
    }
    if (!vectors.isArray() || vectors.size() != texts.size()) {
      throw new IllegalStateException(
          "TEI at " + baseUrl + " returned " + (vectors.isArray() ? vectors.size() : "non-array")
              + " vectors for " + texts.size() + " texts");
    }
    List<float[]> result = new ArrayList<>(texts.size());
    for (JsonNode vector : vectors) {
      float[] floats = new float[vector.size()];
      for (int i = 0; i < floats.length; i++) {
        floats[i] = (float) vector.get(i).asDouble();
      }
      result.add(floats);
    }
    return result;
  }

  private void requireSupported(String model) {
    if (!supports(model)) {
      throw new IllegalArgumentException(
          "unknown model '" + model + "'; registered: " + models);
    }
  }

  private static String abbreviate(String body) {
    return body.length() <= 200 ? body : body.substring(0, 200) + "...";
  }

  @Override
  public void close() {
    client.close();
  }
}
