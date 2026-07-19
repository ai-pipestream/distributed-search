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

import ai.pipestream.search.embeddings.EmbeddingProvider;
import com.google.protobuf.ByteString;
import inference.GrpcPredictV2.InferTensorContents;
import inference.GrpcPredictV2.ModelInferRequest;
import inference.GrpcPredictV2.ModelInferResponse;
import inference.GrpcPredictV2.ModelMetadataRequest;
import inference.GrpcPredictV2.ModelMetadataResponse;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link EmbeddingProvider} client for KServe v2 gRPC serving runtimes (OpenVINO Model Server,
 * Triton): the quality lane's transport. Text goes out as a single BYTES tensor named
 * {@code strings}; the serving runtime replies with one FP32 embedding row per input text.
 *
 * <p>Configuration mirrors {@code model2vec-provider}: the no-arg constructor (ServiceLoader)
 * reads an endpoint from the system property {@value #ENDPOINT_PROPERTY} or the
 * {@value #ENDPOINT_ENV_VAR} environment variable ({@code host:port}) and the served model ids
 * from {@value #MODELS_PROPERTY}/{@value #MODELS_ENV_VAR} (comma-separated); with no
 * configuration the provider supports nothing and is inert. Hosts use
 * {@link #KServeEmbeddingProvider(String, int, Collection)} directly.
 *
 * <p>Plaintext channels only; TLS is a deliberate follow-up, not an omission.
 */
public final class KServeEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

  /** Provider id used for registration and lookup: {@value}. */
  public static final String NAME = "kserve";

  /** System property naming the KServe endpoint ({@code host:port}): {@value}. */
  public static final String ENDPOINT_PROPERTY = "ai.pipestream.search.embeddings.kserve.endpoint";

  /** Environment variable naming the KServe endpoint ({@code host:port}): {@value}. */
  public static final String ENDPOINT_ENV_VAR = "KSERVE_ENDPOINT";

  /** System property naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_PROPERTY = "ai.pipestream.search.embeddings.kserve.models";

  /** Environment variable naming the served model ids (comma-separated): {@value}. */
  public static final String MODELS_ENV_VAR = "KSERVE_MODELS";

  private static final String INPUT_TENSOR = "strings";
  private static final long DEADLINE_SECONDS = 30;

  private final Set<String> models;
  private final ManagedChannel channel;
  private final GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub stub;
  private final ConcurrentHashMap<String, Integer> dimsCache = new ConcurrentHashMap<>();
  private final String endpointDescription;

  /** ServiceLoader entry point; configuration via the properties/env vars above. */
  public KServeEmbeddingProvider() {
    this(configuredEndpoint(), configuredModels());
  }

  private KServeEmbeddingProvider(String[] endpoint, Set<String> models) {
    this(endpoint == null ? null : endpoint[0], endpoint == null ? -1 : Integer.parseInt(endpoint[1]), models);
  }

  /**
   * Create a provider over one KServe endpoint.
   *
   * @param host the serving host
   * @param port the KServe v2 gRPC port
   * @param models the model ids this endpoint serves
   */
  public KServeEmbeddingProvider(String host, int port, Collection<String> models) {
    this.models = Set.copyOf(models);
    this.endpointDescription = host + ":" + port;
    this.channel =
        ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
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
      throw new IllegalArgumentException(
          "endpoint must be host:port, got: " + endpoint);
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
    return models.contains(model);
  }

  @Override
  public int dims(String model) {
    requireSupported(model);
    return dimsCache.computeIfAbsent(model, this::fetchDims);
  }

  private int fetchDims(String model) {
    ModelMetadataResponse metadata;
    try {
      metadata =
          stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
              .modelMetadata(ModelMetadataRequest.newBuilder().setName(model).build());
    } catch (StatusRuntimeException e) {
      throw unavailable(model, e);
    }
    // The embedding tensor is the single output; its last shape entry is the row width.
    var outputs = metadata.getOutputsList();
    if (outputs.isEmpty() || outputs.get(0).getShapeCount() == 0) {
      throw new IllegalStateException(
          "model '" + model + "' at " + endpointDescription + " reports no output shape");
    }
    var shape = outputs.get(0).getShapeList();
    long dims = shape.get(shape.size() - 1);
    if (dims <= 0 || dims > Integer.MAX_VALUE) {
      throw new IllegalStateException(
          "model '" + model + "' at " + endpointDescription + " reports dynamic dims: " + shape);
    }
    return (int) dims;
  }

  @Override
  public List<float[]> embed(String model, List<String> texts) {
    requireSupported(model);
    InferTensorContents contents =
        InferTensorContents.newBuilder()
            .addAllBytesContents(texts.stream().map(ByteString::copyFromUtf8).toList())
            .build();
    ModelInferRequest request =
        ModelInferRequest.newBuilder()
            .setModelName(model)
            .addInputs(
                ModelInferRequest.InferInputTensor.newBuilder()
                    .setName(INPUT_TENSOR)
                    .setDatatype("BYTES")
                    .addShape(texts.size())
                    .setContents(contents))
            .build();
    ModelInferResponse response;
    try {
      response =
          stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS).modelInfer(request);
    } catch (StatusRuntimeException e) {
      throw unavailable(model, e);
    }
    if (response.getOutputsCount() == 0) {
      throw new IllegalStateException(
          "model '" + model + "' at " + endpointDescription + " returned no outputs");
    }
    ModelInferResponse.InferOutputTensor output = response.getOutputs(0);
    int batch = texts.size();
    List<Float> flat = new ArrayList<>(output.getContents().getFp32ContentsList());
    if (flat.isEmpty() && response.getRawOutputContentsCount() > 0) {
      ByteBuffer raw = response.getRawOutputContents(0).asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
      while (raw.remaining() >= Float.BYTES) {
        flat.add(raw.getFloat());
      }
    }
    if (flat.isEmpty() || flat.size() % batch != 0) {
      throw new IllegalStateException(
          "model '" + model + "' at " + endpointDescription + " returned " + flat.size()
              + " floats for a batch of " + batch);
    }
    int dims = flat.size() / batch;
    List<float[]> vectors = new ArrayList<>(batch);
    for (int i = 0; i < batch; i++) {
      float[] vector = new float[dims];
      for (int j = 0; j < dims; j++) {
        vector[j] = flat.get(i * dims + j);
      }
      vectors.add(vector);
    }
    return vectors;
  }

  private void requireSupported(String model) {
    if (!supports(model)) {
      throw new IllegalArgumentException(
          "unknown model '" + model + "'; registered: " + models);
    }
  }

  private IllegalStateException unavailable(String model, StatusRuntimeException e) {
    return new IllegalStateException(
        "KServe call for model '" + model + "' at " + endpointDescription + " failed: "
            + e.getStatus(),
        e);
  }

  @Override
  public void close() {
    channel.shutdownNow();
  }
}
