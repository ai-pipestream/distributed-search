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

package ai.pipestream.search.embeddings.ovms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Live-endpoint tests against an OVMS reranker; skipped when the endpoint is down. */
class OvmsRerankProviderTest {

  private static final String BASE_URL =
      System.getenv().getOrDefault("OVMS_TEST_RERANK_ENDPOINT", "http://localhost:8003");
  private static final String MODEL = "bge-reranker";

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
  void scoresInInputOrderWithSaneRanking() {
    assumeTrue(endpointUp(), "OVMS rerank endpoint not reachable at " + BASE_URL);
    try (OvmsRerankProvider provider = new OvmsRerankProvider(BASE_URL, Set.of(MODEL))) {
      assertEquals(OvmsRerankProvider.NAME, provider.name());
      assertTrue(provider.supports(MODEL));

      List<Float> scores =
          provider.score(
              MODEL,
              "nearest neighbor search",
              List.of(
                  "approximate nearest neighbor search with HNSW graphs",
                  "how to change a car tire",
                  "classical composers of the 18th century",
                  "best recipes for sourdough bread"));

      assertEquals(4, scores.size());
      float top = scores.stream().max(Float::compare).orElseThrow();
      assertEquals(scores.get(0), top, "the vector-search document must rank first");
    }
  }
}
