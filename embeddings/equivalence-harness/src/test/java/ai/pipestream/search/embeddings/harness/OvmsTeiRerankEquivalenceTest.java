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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.pipestream.search.embeddings.ovms.OvmsRerankProvider;
import ai.pipestream.search.embeddings.tei.TEIRerankProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The first cross-runtime rerank certification: the same cross-encoder
 * (BAAI/bge-reranker-base) served by OVMS and by TEI. Kendall tau compares
 * orderings, so small score-scale differences pass by design; a FAIL means one runtime
 * tokenizes or assembles pairs differently and the pair stays split. Skipped when either
 * endpoint is down. The served model must be a two-input cross-encoder (no
 * token_type_ids): OVMS's rerank calculator builds pair assembly itself and fills
 * token_type_ids with zeros, so three-input models cannot certify there.
 */
class OvmsTeiRerankEquivalenceTest {

  private static final String MODEL = "bge-reranker";
  private static final String OVMS_URL =
      System.getenv().getOrDefault("OVMS_TEST_RERANK_ENDPOINT", "http://localhost:8003");
  private static final String TEI_URL =
      System.getenv().getOrDefault("TEI_TEST_RERANK_ENDPOINT", "http://localhost:8089");

  private static boolean urlUp(String url) {
    try (Socket socket = new Socket()) {
      var uri = java.net.URI.create(url);
      socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 2000);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  void ovmsVsTeiSameReranker() {
    assumeTrue(urlUp(OVMS_URL), "OVMS rerank endpoint not reachable at " + OVMS_URL);
    assumeTrue(urlUp(TEI_URL), "TEI rerank endpoint not reachable at " + TEI_URL);

    try (OvmsRerankProvider ovms = new OvmsRerankProvider(OVMS_URL, Set.of(MODEL));
        TEIRerankProvider tei = new TEIRerankProvider(TEI_URL, Set.of(MODEL))) {
      RerankEquivalenceReport report =
          new RerankEquivalenceHarness()
              .compare(
                  ovms,
                  tei,
                  MODEL,
                  List.of(
                      "nearest neighbor search", "engine maintenance", "classical music"),
                  EquivalenceHarness.defaultProbeTexts(),
                  5);
      System.out.printf(
          "CERTIFICATION rerank ovms(%s) vs tei(%s): minTau=%.4f meanTau=%.4f"
              + " meanTopKOverlap=%.3f pass=%s%n",
          MODEL, MODEL, report.minKendallTau(), report.meanKendallTau(),
          report.meanTopKOverlap(), report.pass());
      assertNotNull(report);
      assertTrue(report.pass(), "same model on two runtimes must certify");
    }
  }
}
