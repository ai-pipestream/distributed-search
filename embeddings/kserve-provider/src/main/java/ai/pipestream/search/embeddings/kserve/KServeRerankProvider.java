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

package ai.pipestream.search.embeddings.kserve;

import ai.pipestream.search.embeddings.RerankProvider;
import com.google.protobuf.ByteString;
import inference.GrpcPredictV2.InferTensorContents;
import inference.GrpcPredictV2.ModelInferRequest;
import inference.GrpcPredictV2.ModelInferResponse;
import inference.GRPCInferenceServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@link RerankProvider} client for KServe v2 serving runtimes (OpenVINO Model Server, Triton)
 * hosting a cross-encoder: the query as a one-element BYTES tensor, the documents as an
 * N-element BYTES tensor, one FP32 score per document out.
 *
 * <p>Tensor names are constructor-configurable (defaults {@code query} / {@code documents},
 * first output tensor) because KServe has no rerank-specific tensor convention; check the
 * served model's {@code ModelMetadata} before wiring a new endpoint.
 *
 * <p>Configuration mirrors {@link KServeEmbeddingProvider}: the no-arg constructor
 * (ServiceLoader) reads {@value #ENDPOINT_PROPERTY}/{@value #ENDPOINT_ENV_VAR}
 * ({@code host:port}) and {@value #MODELS_PROPERTY}/{@value #MODELS_ENV_VAR}
 * (comma-separated); with no configuration the provider supports nothing and is inert.
 * Plaintext channel only.
 */
public final class KServeRerankProvider implements RerankProvider, AutoCloseable {

  /** Provider id used for registration and lookup: {@value}. */
  public static final String NAME = "kserve";

  /** System property naming the KServe reranker endpoint ({@code host:port}): {@value}. */
  public static final String ENDPOINT_PROPERTY = "ai.pipestream.search.rerank.kserve.endpoint";

  /** Environment variable naming the KServe reranker endpoint ({@code host:port}): {@value}. */
  public static final String ENDPOINT_ENV_VAR = "KSERVE_RERANK_ENDPOINT";

  /** System property naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_PROPERTY = "ai.pipestream.search.rerank.kserve.models";

  /** Environment variable naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_ENV_VAR = "KSERVE_RERANK_MODELS";

  private static final long DEADLINE_SECONDS = 60;

  private final Set<String> models;
  private final ManagedChannel channel;
  private final GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub stub;
  private final String endpointDescription;
  private final String queryTensor;
  private final String documentsTensor;

  /** ServiceLoader entry point; configuration via the properties/env vars above. */
  public KServeRerankProvider() {
    this(configuredEndpoint(), configuredModels());
  }

  private KServeRerankProvider(String[] endpoint, Set<String> models) {
    this(
        endpoint == null ? null : endpoint[0],
        endpoint == null ? -1 : Integer.parseInt(endpoint[1]),
        models);
  }

  /**
   * Create a provider over one KServe reranker endpoint with the default tensor names.
   *
   * @param host the serving host; may be null, which makes the provider inert
   * @param port the KServe v2 gRPC port
   * @param models the model ids routed to this endpoint
   */
  public KServeRerankProvider(String host, int port, Collection<String> models) {
    this(host, port, models, "query", "documents");
  }

  /**
   * Create a provider over one KServe reranker endpoint with explicit tensor names.
   *
   * @param host the serving host
   * @param port the KServe v2 gRPC port
   * @param models the model ids routed to this endpoint
   * @param queryTensor the query input tensor's name
   * @param documentsTensor the documents input tensor's name
   */
  public KServeRerankProvider(
      String host, int port, Collection<String> models, String queryTensor, String documentsTensor) {
    this.models = Set.copyOf(models);
    this.endpointDescription = host + ":" + port;
    this.queryTensor = queryTensor;
    this.documentsTensor = documentsTensor;
    this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    this.stub = GRPCInferenceServiceGrpc.newBlockingStub(channel);
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
  public List<Float> score(String model, String query, List<String> documents) {
    if (!supports(model)) {
      throw new IllegalArgumentException(
          "unknown model '" + model + "'; registered: " + models);
    }
    if (documents.isEmpty()) {
      return List.of();
    }
    ModelInferRequest request =
        ModelInferRequest.newBuilder()
            .setModelName(model)
            .addInputs(bytesTensor(queryTensor, List.of(query)))
            .addInputs(bytesTensor(documentsTensor, documents))
            .build();
    ModelInferResponse response;
    try {
      response = stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS).modelInfer(request);
    } catch (StatusRuntimeException e) {
      throw new IllegalStateException(
          "KServe rerank for model '" + model + "' at " + endpointDescription + " failed: "
              + e.getStatus(),
          e);
    }
    if (response.getOutputsCount() == 0) {
      throw new IllegalStateException(
          "model '" + model + "' at " + endpointDescription + " returned no outputs");
    }
    ModelInferResponse.InferOutputTensor output = response.getOutputs(0);
    List<Float> scores = new ArrayList<>(output.getContents().getFp32ContentsList());
    if (scores.isEmpty() && response.getRawOutputContentsCount() > 0) {
      ByteBuffer raw =
          response.getRawOutputContents(0).asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
      while (raw.remaining() >= Float.BYTES) {
        scores.add(raw.getFloat());
      }
    }
    if (scores.size() != documents.size()) {
      throw new IllegalStateException(
          "model '" + model + "' at " + endpointDescription + " returned " + scores.size()
              + " scores for " + documents.size() + " documents");
    }
    return scores;
  }

  private static ModelInferRequest.InferInputTensor bytesTensor(String name, List<String> values) {
    return ModelInferRequest.InferInputTensor.newBuilder()
        .setName(name)
        .setDatatype("BYTES")
        .addShape(values.size())
        .setContents(
            InferTensorContents.newBuilder()
                .addAllBytesContents(values.stream().map(ByteString::copyFromUtf8).toList()))
        .build();
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdownNow();
    }
  }
}
