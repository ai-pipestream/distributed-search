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
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tei.v1.EmbedGrpc;
import tei.v1.Tei.EmbedRequest;
import tei.v1.Tei.EmbedResponse;

/**
 * {@link EmbeddingProvider} client for Text Embeddings Inference over its gRPC API
 * ({@code tei.v1.Embed}): binary protobuf transport instead of the HTTP/JSON API.
 *
 * <p>TEI's gRPC messages carry one text per request; batches are pipelined over the
 * bidirectional {@code EmbedStream}. This provider drives the stream in lockstep — one
 * request outstanding at a time — because the stream carries no request index, so ordering
 * is only guaranteed by waiting for each response before sending the next. Pipelined sending
 * with an explicit index map is a future optimization.
 *
 * <p>Configuration mirrors the other providers: the no-arg constructor (ServiceLoader) reads
 * {@value #ENDPOINT_PROPERTY}/{@value #ENDPOINT_ENV_VAR} ({@code host:port}) and
 * {@value #MODELS_PROPERTY}/{@value #MODELS_ENV_VAR} (comma-separated); with no configuration
 * the provider supports nothing and is inert. Plaintext channel only.
 *
 * <p>{@link #dims(String)} is probed (one short embed, cached): TEI does not report the
 * embedding width over the wire.
 */
public final class TEIGrpcEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

  /** Provider id used for registration and lookup: {@value}. */
  public static final String NAME = "tei-grpc";

  /** System property naming the TEI gRPC endpoint ({@code host:port}): {@value}. */
  public static final String ENDPOINT_PROPERTY = "ai.pipestream.search.embeddings.tei.endpoint";

  /** Environment variable naming the TEI gRPC endpoint ({@code host:port}): {@value}. */
  public static final String ENDPOINT_ENV_VAR = "TEI_ENDPOINT";

  /** System property naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_PROPERTY = "ai.pipestream.search.embeddings.tei.models";

  /** Environment variable naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_ENV_VAR = "TEI_MODELS";

  private static final long DEADLINE_SECONDS = 30;

  private final Set<String> models;
  private final ManagedChannel channel;
  private final EmbedGrpc.EmbedStub stub;
  private final ConcurrentHashMap<String, Integer> dimsCache = new ConcurrentHashMap<>();
  private final String endpointDescription;

  /** ServiceLoader entry point; configuration via the properties/env vars above. */
  public TEIGrpcEmbeddingProvider() {
    this(configuredEndpoint(), configuredModels());
  }

  private TEIGrpcEmbeddingProvider(String[] endpoint, Set<String> models) {
    this(
        endpoint == null ? null : endpoint[0],
        endpoint == null ? -1 : Integer.parseInt(endpoint[1]),
        models);
  }

  /**
   * Create a provider over one TEI gRPC endpoint.
   *
   * @param host the TEI host; may be null, which makes the provider inert
   * @param port the TEI gRPC port
   * @param models the model ids routed to this endpoint
   */
  public TEIGrpcEmbeddingProvider(String host, int port, Collection<String> models) {
    this.models = Set.copyOf(models);
    this.endpointDescription = host + ":" + port;
    this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    this.stub = EmbedGrpc.newStub(channel);
  }

  private static String[] configuredEndpoint() {
    String endpoint = System.getProperty(ENDPOINT_PROPERTY);
    if (endpoint == null || endpoint.isBlank()) {
      endpoint = System.getenv(ENDPOINT_ENV_VAR);
    }
    if (endpoint == null || endpoint.isBlank()) {
      return null;
    }
    String[] parts = endpoint.split(":", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException("endpoint must be host:port, got: " + endpoint);
    }
    return parts;
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
    return channel != null && models.contains(model);
  }

  @Override
  public int dims(String model) {
    requireSupported(model);
    return dimsCache.computeIfAbsent(
        model, id -> embed(id, List.of("dimension probe")).get(0).length);
  }

  @Override
  public List<float[]> embed(String model, List<String> texts) {
    requireSupported(model);
    if (texts.isEmpty()) {
      return List.of();
    }
    LinkedBlockingQueue<EmbedResponse> responses = new LinkedBlockingQueue<>();
    List<Throwable> errors = new ArrayList<>(1);
    CountDownLatch done = new CountDownLatch(1);
    StreamObserver<EmbedRequest> requests =
        stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
            .embedStream(
                new StreamObserver<>() {
                  @Override
                  public void onNext(EmbedResponse response) {
                    responses.add(response);
                  }

                  @Override
                  public void onError(Throwable t) {
                    errors.add(t);
                    done.countDown();
                  }

                  @Override
                  public void onCompleted() {
                    done.countDown();
                  }
                });
    List<float[]> vectors = new ArrayList<>(texts.size());
    try {
      for (String text : texts) {
        requests.onNext(EmbedRequest.newBuilder().setInputs(text).setNormalize(true).build());
        EmbedResponse response = responses.poll(DEADLINE_SECONDS, TimeUnit.SECONDS);
        if (response == null) {
          throw new IllegalStateException(
              "TEI at " + endpointDescription + " timed out mid-stream");
        }
        var floats = response.getEmbeddingsList();
        float[] vector = new float[floats.size()];
        for (int i = 0; i < floats.size(); i++) {
          vector[i] = floats.get(i);
        }
        vectors.add(vector);
      }
      requests.onCompleted();
      if (!done.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
            "TEI at " + endpointDescription + " did not close the stream");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      requests.onError(e);
      throw new IllegalStateException("interrupted calling TEI at " + endpointDescription, e);
    } catch (RuntimeException e) {
      requests.onError(e);
      throw e;
    }
    if (!errors.isEmpty()) {
      Throwable t = errors.get(0);
      throw new IllegalStateException(
          "TEI call to " + endpointDescription + " failed: "
              + (t instanceof StatusRuntimeException sre ? sre.getStatus() : t),
          t);
    }
    return vectors;
  }

  private void requireSupported(String model) {
    if (!supports(model)) {
      throw new IllegalArgumentException("unknown model '" + model + "'; registered: " + models);
    }
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdownNow();
    }
  }
}
