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
import ai.pipestream.search.embeddings.tei.TEIEmbeddingProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Certification of the same model (all-MiniLM-L6-v2) served by two runtimes — a KServe v2
 * server and a TEI server. A PASS admits the pair to the accurate lane; a FAIL means one
 * runtime's tokenization, padding, or pooling differs and the pair must stay split. Skipped
 * when either endpoint is down.
 */
class TEIOvmsEquivalenceTest {

  private static final String MODEL = "minilm";
  private static final String KSERVE_ENDPOINT =
      System.getenv().getOrDefault("KSERVE_TEST_ENDPOINT", "localhost:9002");
  private static final String KSERVE_HOST = KSERVE_ENDPOINT.split(":")[0];
  private static final int KSERVE_PORT = Integer.parseInt(KSERVE_ENDPOINT.split(":")[1]);
  private static final String TEI_URL =
      System.getenv().getOrDefault("TEI_TEST_REST_ENDPOINT", "http://localhost:8088");

  private static boolean tcpUp(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 2000);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  void kserveVsTeiSameModel() {
    assumeTrue(tcpUp(KSERVE_HOST, KSERVE_PORT), "KServe endpoint not reachable");
    var teiUri = java.net.URI.create(TEI_URL);
    assumeTrue(
        tcpUp(teiUri.getHost(), teiUri.getPort()), "TEI endpoint not reachable at " + TEI_URL);

    try (KServeEmbeddingProvider kserve =
            new KServeEmbeddingProvider(KSERVE_HOST, KSERVE_PORT, Set.of(MODEL));
        TEIEmbeddingProvider tei = new TEIEmbeddingProvider(TEI_URL, Set.of(MODEL))) {
      EquivalenceReport report =
          new EquivalenceHarness()
              .compare(
                  kserve,
                  tei,
                  MODEL,
                  EquivalenceHarness.defaultProbeTexts(),
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
                      "index construction for approximate nearest neighbors"),
                  java.util.List.of(
                      "nearest neighbor search", "engine maintenance", "classical music", "cooking"),
                  5);
      System.out.printf(
          "CERTIFICATION kserve(%s) vs tei(%s): minCosine=%.6f meanCosine=%.6f"
              + " meanTopKOverlap=%.3f pass=%s%n",
          MODEL, MODEL, report.minCosine(), report.meanCosine(), report.meanTopKOverlap(),
          report.pass());
      assertNotNull(report);
    }
  }
}
