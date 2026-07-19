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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.pipestream.search.embeddings.EmbeddingProvider;
import ai.pipestream.search.embeddings.EmbeddingProviders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Model2VecProviderTest {

  // The fast-lane default: retrieval-tuned static model (BEIR SciFact recall@10 0.795, ~98.5%
  // of the all-MiniLM-L6-v2 transformer on the same eval; see embeddings-bench/beir_results.txt).
  private static final String MODEL_ID = "potion-retrieval-32M";
  private static final Path MODEL_DIR =
      Path.of("/work/main/embeddings-bench/potion-retrieval-32m-opennlp");
  // The original 256-dim static model, kept in the registry (and used as the cross-provider
  // negative control against OVMS's 384-dim minilm).
  private static final String LEGACY_MODEL_ID = "minilm-static";
  private static final Path LEGACY_MODEL_DIR =
      Path.of("/work/main/embeddings-bench/minilm-static-opennlp");

  private static Model2VecProvider provider() {
    return new Model2VecProvider(
        Map.of(MODEL_ID, MODEL_DIR, LEGACY_MODEL_ID, LEGACY_MODEL_DIR));
  }

  @Test
  void loadsEmbedsAndIsDeterministic() {
    assumeTrue(Files.isDirectory(MODEL_DIR), "model2vec model directory not present");
    Model2VecProvider provider = provider();
    assertTrue(provider.supports(MODEL_ID));
    assertFalse(provider.supports("no-such-model"));
    assertEquals(Model2VecProvider.NAME, provider.name());

    int dims = provider.dims(MODEL_ID);
    assertEquals(512, dims, "potion-retrieval-32M is 512-dim");

    List<float[]> batch =
        provider.embed(MODEL_ID, List.of("hello world", "distributed search", "hello world"));
    assertEquals(3, batch.size());
    assertEquals(dims, batch.get(0).length);
    // Determinism: identical texts produce bit-identical vectors.
    assertEquals(
        Float.floatToIntBits(batch.get(0)[17]), Float.floatToIntBits(batch.get(2)[17]),
        "identical texts must embed identically");
  }

  @Test
  void isDiscoverableViaServiceLoader() {
    assumeTrue(Files.isDirectory(MODEL_DIR), "model2vec model directory not present");
    System.setProperty(
        Model2VecProvider.CONFIG_PROPERTY,
        writeRegistry());
    try {
      EmbeddingProvider discovered = EmbeddingProviders.forModel(MODEL_ID);
      assertEquals(Model2VecProvider.NAME, discovered.name());
    } finally {
      System.clearProperty(Model2VecProvider.CONFIG_PROPERTY);
    }
  }

  @Test
  void forModelFailsLoudWhenNothingSupports() {
    IllegalStateException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> EmbeddingProviders.forModel("definitely-not-a-model"));
    assertTrue(e.getMessage().contains("definitely-not-a-model"));
  }

  private static String writeRegistry() {
    try {
      Path registry = Files.createTempFile("model2vec-registry", ".properties");
      Files.writeString(registry, MODEL_ID + "=" + MODEL_DIR + "\n");
      registry.toFile().deleteOnExit();
      return registry.toString();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
