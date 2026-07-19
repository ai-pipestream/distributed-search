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

package ai.pipestream.search.embeddings.model2vec;

import ai.pipestream.search.embeddings.EmbeddingProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import opennlp.embeddings.StaticEmbeddingModel;

/**
 * In-process {@link EmbeddingProvider} backed by OpenNLP static (model2vec-style) embeddings:
 * no GPU, no network hop, fast enough for CPU-only nodes. This is the fast-lane default: its
 * vector space is its own, it is not expected to certify against any transformer serving of a
 * different model, and it is included in the equivalence harness as the negative control.
 *
 * <p>Configuration maps model ids to model directories (each holding the WordPiece layout:
 * {@code vocab.txt}, {@code model.safetensors}, {@code tokenizer_config.json},
 * {@code config.json}). Models load lazily on first use and are cached.
 *
 * <p>The no-arg constructor — required for {@link java.util.ServiceLoader} discovery — reads a
 * properties file whose location comes from the system property
 * {@value #CONFIG_PROPERTY} or, failing that, the {@value #CONFIG_ENV_VAR} environment
 * variable; each property key is a model id and each value its model directory. With no
 * configuration the provider supports nothing and is inert. Hosts that construct providers
 * directly use {@link #Model2VecProvider(Map)} and need no global state.
 */
public final class Model2VecProvider implements EmbeddingProvider {

  /** Provider id used for registration and lookup: {@value}. */
  public static final String NAME = "model2vec";

  /** System property naming the model-registry properties file: {@value}. */
  public static final String CONFIG_PROPERTY = "ai.pipestream.search.embeddings.model2vec.config";

  /** Environment variable naming the model-registry properties file: {@value}. */
  public static final String CONFIG_ENV_VAR = "MODEL2VEC_MODELS";

  private final Map<String, Path> modelDirectories;
  private final ConcurrentHashMap<String, StaticEmbeddingModel> models =
      new ConcurrentHashMap<>();

  /** ServiceLoader entry point; configuration via {@link #CONFIG_PROPERTY}/{@link #CONFIG_ENV_VAR}. */
  public Model2VecProvider() {
    this(loadConfiguredDirectories());
  }

  /**
   * Create a provider over an explicit model registry.
   *
   * @param modelDirectories model id to model directory; retained defensively
   */
  public Model2VecProvider(Map<String, Path> modelDirectories) {
    this.modelDirectories = Map.copyOf(modelDirectories);
  }

  /**
   * Load a model registry from a properties file (key = model id, value = model directory).
   *
   * @param propertiesFile the registry file
   * @return the parsed registry
   * @throws IOException if the file cannot be read
   */
  public static Map<String, Path> loadModelRegistry(Path propertiesFile) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(propertiesFile)) {
      properties.load(in);
    }
    Map<String, Path> registry = new LinkedHashMap<>();
    for (String id : properties.stringPropertyNames()) {
      registry.put(id, Path.of(properties.getProperty(id)));
    }
    return registry;
  }

  private static Map<String, Path> loadConfiguredDirectories() {
    String location = System.getProperty(CONFIG_PROPERTY);
    if (location == null || location.isBlank()) {
      location = System.getenv(CONFIG_ENV_VAR);
    }
    if (location == null || location.isBlank()) {
      return Map.of();
    }
    try {
      return loadModelRegistry(Path.of(location));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read model registry: " + location, e);
    }
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean supports(String model) {
    return modelDirectories.containsKey(model);
  }

  @Override
  public int dims(String model) {
    return get(model).dimension();
  }

  @Override
  public List<float[]> embed(String model, List<String> texts) {
    StaticEmbeddingModel embeddingModel = get(model);
    // StaticEmbeddingModel is single-text today; batching is a plain loop.
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (String text : texts) {
      vectors.add(embeddingModel.embed(text));
    }
    return vectors;
  }

  private StaticEmbeddingModel get(String model) {
    Path directory = modelDirectories.get(model);
    if (directory == null) {
      throw new IllegalArgumentException(
          "unknown model '" + model + "'; registered: " + modelDirectories.keySet());
    }
    return models.computeIfAbsent(model, id -> load(id, directory));
  }

  private static StaticEmbeddingModel load(String id, Path directory) {
    try {
      return StaticEmbeddingModel.load(directory);
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "cannot load model2vec model '" + id + "' from " + directory + ": " + e.getMessage(), e);
    }
  }
}
