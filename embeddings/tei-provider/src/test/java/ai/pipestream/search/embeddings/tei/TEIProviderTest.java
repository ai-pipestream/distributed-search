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

package ai.pipestream.search.embeddings.tei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Live-endpoint tests for both TEI transports: HTTP/JSON (batched path) and gRPC
 * (streaming-worker path). Each is skipped when its endpoint is down.
 */
class TEIProviderTest {

  private static final String REST_URL =
      System.getenv().getOrDefault("TEI_TEST_REST_ENDPOINT", "http://localhost:8088");
  private static final String GRPC_ENDPOINT =
      System.getenv().getOrDefault("TEI_TEST_GRPC_ENDPOINT", "localhost:3000");
  private static final String MODEL = "minilm";

  private static boolean tcpUp(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 2000);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  void restEmbedsAndReportsDims() {
    var uri = java.net.URI.create(REST_URL);
    assumeTrue(tcpUp(uri.getHost(), uri.getPort()), "TEI REST endpoint not reachable at " + REST_URL);
    try (TEIEmbeddingProvider provider = new TEIEmbeddingProvider(REST_URL, Set.of(MODEL))) {
      assertEquals(TEIEmbeddingProvider.NAME, provider.name());
      assertTrue(provider.supports(MODEL));
      assertEquals(384, provider.dims(MODEL));

      List<float[]> batch = provider.embed(MODEL, List.of("hello world", "hello world"));
      assertEquals(2, batch.size());
      assertEquals(384, batch.get(0).length);
      assertEquals(
          Float.floatToIntBits(batch.get(0)[7]),
          Float.floatToIntBits(batch.get(1)[7]),
          "identical texts must embed identically");
    }
  }

  @Test
  void grpcEmbedsAndReportsDims() {
    String host = GRPC_ENDPOINT.split(":")[0];
    int port = Integer.parseInt(GRPC_ENDPOINT.split(":")[1]);
    assumeTrue(tcpUp(host, port), "TEI gRPC endpoint not reachable at " + GRPC_ENDPOINT);
    try (TEIGrpcEmbeddingProvider provider =
        new TEIGrpcEmbeddingProvider(host, port, Set.of(MODEL))) {
      assertEquals(TEIGrpcEmbeddingProvider.NAME, provider.name());
      assertTrue(provider.supports(MODEL));
      assertEquals(384, provider.dims(MODEL));

      List<float[]> batch = provider.embed(MODEL, List.of("hello world", "hello world"));
      assertEquals(2, batch.size());
      assertEquals(384, batch.get(0).length);
      assertEquals(
          Float.floatToIntBits(batch.get(0)[7]),
          Float.floatToIntBits(batch.get(1)[7]),
          "identical texts must embed identically");
    }
  }
}
