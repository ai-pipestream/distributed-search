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

import ai.pipestream.search.embeddings.tei.TEIRerankProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Live reranker self-certification: a provider must certify against itself (sanity for the
 * gate), and the verdict is printed for the record. A true cross-runtime rerank pair
 * (KServe-served cross-encoder vs TEI-served one) runs here once a second reranker endpoint
 * exists. Skipped when the endpoint is down.
 */
class TEIRerankEquivalenceTest {

  private static final String BASE_URL =
      System.getenv().getOrDefault("TEI_TEST_RERANK_ENDPOINT", "http://localhost:8089");
  private static final String MODEL = "reranker";

  private static boolean endpointUp() {
    try (Socket socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(
              java.net.URI.create(BASE_URL).getHost(), java.net.URI.create(BASE_URL).getPort()),
          2000);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  void selfCertificationPasses() {
    assumeTrue(endpointUp(), "TEI rerank endpoint not reachable at " + BASE_URL);
    try (TEIRerankProvider provider = new TEIRerankProvider(BASE_URL, Set.of(MODEL))) {
      RerankEquivalenceReport report =
          new RerankEquivalenceHarness()
              .compare(
                  provider,
                  provider,
                  MODEL,
                  java.util.List.of(
                      "nearest neighbor search", "engine maintenance", "classical music"),
                  EquivalenceHarness.defaultProbeTexts(),
                  5);
      System.out.printf(
          "CERTIFICATION rerank tei(%s) vs itself: minTau=%.4f meanTau=%.4f"
              + " meanTopKOverlap=%.3f pass=%s%n",
          MODEL, report.minKendallTau(), report.meanKendallTau(), report.meanTopKOverlap(),
          report.pass());
      assertNotNull(report);
      org.junit.jupiter.api.Assertions.assertTrue(report.pass(), "self-certification must pass");
    }
  }
}
