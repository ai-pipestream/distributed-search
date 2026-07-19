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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.pipestream.search.embeddings.kserve.KServeEmbeddingProvider;
import ai.pipestream.search.embeddings.model2vec.Model2VecProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Cross-provider certification of the in-process static model against the transformer model
 * served by the KServe runtime. The two are expected to differ (a FAIL, keeping the static
 * model in its own lane); a PASS would need manual double-checking. Skipped when either side
 * is unavailable.
 */
class KServeEquivalenceTest {

  private static final String MODEL_ID = "minilm-static";
  private static final Path MODEL_DIR =
      Path.of(
          System.getenv()
              .getOrDefault(
                  "MODEL2VEC_TEST_MODEL_DIR", "/work/main/embeddings-bench/minilm-static-opennlp"));
  private static final String KSERVE_MODEL = "minilm";
  private static final String ENDPOINT =
      System.getenv().getOrDefault("KSERVE_TEST_ENDPOINT", "localhost:9002");
  private static final String HOST = ENDPOINT.split(":")[0];
  private static final int PORT = Integer.parseInt(ENDPOINT.split(":")[1]);

  private static boolean endpointUp() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(HOST, PORT), 2000);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  void model2VecVsServedTransformer() {
    assumeTrue(Files.isDirectory(MODEL_DIR), "model2vec model directory not present");
    assumeTrue(endpointUp(), "KServe endpoint not reachable at " + ENDPOINT);

    Model2VecProvider model2vec = new Model2VecProvider(Map.of(MODEL_ID, MODEL_DIR));
    try (KServeEmbeddingProvider kserve =
        new KServeEmbeddingProvider(HOST, PORT, Set.of(KSERVE_MODEL))) {
      EquivalenceReport report = certify(model2vec, kserve);
      System.out.printf(
          "CERTIFICATION model2vec(%s) vs kserve(%s): minCosine=%.6f meanCosine=%.6f"
              + " meanTopKOverlap=%.3f pass=%s%n",
          MODEL_ID, KSERVE_MODEL,
          report.minCosine(), report.meanCosine(), report.meanTopKOverlap(), report.pass());
      assertNotNull(report);
    }
  }

  private static EquivalenceReport certify(
      Model2VecProvider model2vec, KServeEmbeddingProvider kserve) {
    // The two providers know the model under different ids; wrap model2vec to answer to the
    // kserve id so the harness can compare them.
    var staticAsKserve =
        new ai.pipestream.search.embeddings.EmbeddingProvider() {
          @Override
          public String name() {
            return "model2vec";
          }

          @Override
          public boolean supports(String model) {
            return KSERVE_MODEL.equals(model);
          }

          @Override
          public int dims(String model) {
            return model2vec.dims(MODEL_ID);
          }

          @Override
          public java.util.List<float[]> embed(String model, java.util.List<String> texts) {
            return model2vec.embed(MODEL_ID, texts);
          }
        };
    java.util.List<String> corpus =
        java.util.List.of(
            "the quick brown fox jumps over the lazy dog",
            "distributed vector search at scale",
            "how do i change the oil in a 2019 honda civic",
            "a muted trumpet plays softly in the background",
            "quarterly earnings rose twelve percent year over year",
            "the committee postponed its decision until spring",
            "photosynthesis converts sunlight into chemical energy",
            "she sold seashells by the seashore",
            "an error occurred while deserializing the response",
            "the trail climbs steeply above the treeline",
            "repair manuals for small gasoline engines",
            "the symphony's second movement opens quietly",
            "how distributed systems reach consensus",
            "seasonal recipes for late summer tomatoes",
            "index construction for approximate nearest neighbors");
    java.util.List<String> queries =
        java.util.List.of(
            "nearest neighbor search", "engine maintenance", "classical music", "cooking");
    return new EquivalenceHarness()
        .compare(
            staticAsKserve, kserve, KSERVE_MODEL,
            EquivalenceHarness.defaultProbeTexts(), corpus, queries, 5);
  }
}
