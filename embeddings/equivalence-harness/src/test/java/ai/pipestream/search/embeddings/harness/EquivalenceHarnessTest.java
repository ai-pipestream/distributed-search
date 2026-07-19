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

package ai.pipestream.search.embeddings.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.pipestream.search.embeddings.EmbeddingProvider;
import ai.pipestream.search.embeddings.model2vec.Model2VecProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EquivalenceHarnessTest {

  private static final String MODEL_ID = "minilm-static";
  private static final Path MODEL_DIR = Path.of("/work/main/embeddings-bench/minilm-static-opennlp");

  private final EquivalenceHarness harness = new EquivalenceHarness();

  @Test
  void identicalProvidersPass() {
    EmbeddingProvider a = new HashEmbeddingProvider("hash", 42, 64);
    EmbeddingProvider b = new HashEmbeddingProvider("hash", 42, 64);
    EquivalenceReport report =
        harness.compare(
            a, b, "m", EquivalenceHarness.defaultProbeTexts(), corpus(), queries(), 5);
    assertTrue(report.pass(), "identical providers must certify: " + report);
    assertTrue(report.minCosine() >= EquivalenceHarness.DEFAULT_MIN_COSINE);
  }

  /**
   * The negative control: two providers whose vectors come from the same distribution but
   * different seeds — the stand-in for "same model family, different weights/space". The gate
   * must close. If this ever passes, the harness itself is broken.
   */
  @Test
  void differentlySeededProvidersFail() {
    EmbeddingProvider a = new HashEmbeddingProvider("hash-a", 42, 64);
    EmbeddingProvider b = new HashEmbeddingProvider("hash-b", 1337, 64);
    EquivalenceReport report =
        harness.compare(
            a, b, "m", EquivalenceHarness.defaultProbeTexts(), corpus(), queries(), 5);
    assertFalse(report.pass(), "differently-seeded providers must not certify: " + report);
  }

  @Test
  void dimensionMismatchFailsImmediately() {
    EmbeddingProvider a = new HashEmbeddingProvider("hash-a", 42, 64);
    EmbeddingProvider b = new HashEmbeddingProvider("hash-b", 42, 128);
    EquivalenceReport report =
        harness.compare(
            a, b, "m", EquivalenceHarness.defaultProbeTexts(), corpus(), queries(), 5);
    assertFalse(report.pass(), "dimension mismatch must not certify");
  }

  @Test
  void model2VecSelfEquivalencePasses() {
    assumeTrue(Files.isDirectory(MODEL_DIR), "model2vec model directory not present");
    Model2VecProvider provider = new Model2VecProvider(Map.of(MODEL_ID, MODEL_DIR));
    EquivalenceReport report =
        harness.compare(
            provider,
            provider,
            MODEL_ID,
            EquivalenceHarness.defaultProbeTexts(),
            corpus(),
            queries(),
            5);
    assertTrue(report.pass(), "a provider must certify against itself: " + report);
  }

  private static List<String> corpus() {
    List<String> corpus = new ArrayList<>(EquivalenceHarness.defaultProbeTexts());
    corpus.add("repair manuals for small gasoline engines");
    corpus.add("the symphony's second movement opens quietly");
    corpus.add("how distributed systems reach consensus");
    corpus.add("seasonal recipes for late summer tomatoes");
    corpus.add("index construction for approximate nearest neighbors");
    return corpus;
  }

  private static List<String> queries() {
    return List.of(
        "nearest neighbor search", "engine maintenance", "classical music", "cooking");
  }

  /**
   * A deterministic stub: text hash + seed to a pseudo-random unit vector. Same seed and text
   * give the same vector; different seeds give unrelated vectors — enough to exercise the
   * harness's pass and fail paths without any model files.
   */
  private static final class HashEmbeddingProvider implements EmbeddingProvider {
    private final String name;
    private final long seed;
    private final int dims;

    HashEmbeddingProvider(String name, long seed, int dims) {
      this.name = name;
      this.seed = seed;
      this.dims = dims;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean supports(String model) {
      return true;
    }

    @Override
    public int dims(String model) {
      return dims;
    }

    @Override
    public List<float[]> embed(String model, List<String> texts) {
      List<float[]> vectors = new ArrayList<>(texts.size());
      for (String text : texts) {
        Random random = new Random(seed ^ text.hashCode());
        float[] vector = new float[dims];
        double norm = 0;
        for (int i = 0; i < dims; i++) {
          vector[i] = (float) random.nextGaussian();
          norm += vector[i] * vector[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < dims; i++) {
          vector[i] /= (float) norm;
        }
        vectors.add(vector);
      }
      return vectors;
    }
  }
}
