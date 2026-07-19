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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Live-endpoint tests against a KServe serving runtime; skipped when the endpoint is down. */
class KServeProviderTest {

  private static final String ENDPOINT =
      System.getenv().getOrDefault("KSERVE_TEST_ENDPOINT", "localhost:9002");
  private static final String HOST = ENDPOINT.split(":")[0];
  private static final int PORT = Integer.parseInt(ENDPOINT.split(":")[1]);
  private static final String MODEL = "minilm";

  private static boolean endpointUp() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(HOST, PORT), 2000);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  void embedsAndReportsDims() {
    assumeTrue(endpointUp(), "KServe endpoint not reachable at " + ENDPOINT);
    try (KServeEmbeddingProvider provider =
        new KServeEmbeddingProvider(HOST, PORT, Set.of(MODEL))) {
      assertEquals(KServeEmbeddingProvider.NAME, provider.name());
      assertTrue(provider.supports(MODEL));

      int dims = provider.dims(MODEL);
      assertEquals(384, dims, "minilm serves 384-dim embeddings");

      List<float[]> batch = provider.embed(MODEL, List.of("hello world", "hello world"));
      assertEquals(2, batch.size());
      assertEquals(dims, batch.get(0).length);
      // Serving is deterministic for identical requests.
      assertEquals(
          Float.floatToIntBits(batch.get(0)[7]),
          Float.floatToIntBits(batch.get(1)[7]),
          "identical texts must embed identically");
    }
  }
}
