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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.pipestream.search.embeddings.RerankProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RerankEquivalenceHarnessTest {

  private final RerankEquivalenceHarness harness = new RerankEquivalenceHarness();

  @Test
  void identicalProvidersPass() {
    RerankProvider a = new HashRerankProvider("hash", 42);
    RerankProvider b = new HashRerankProvider("hash", 42);
    RerankEquivalenceReport report =
        harness.compare(a, b, "m", queries(), corpus(), 5);
    assertTrue(report.pass(), "identical providers must certify: " + report);
  }

  /**
   * Score-scale differences must NOT fail certification: tau is scale-invariant, and order is
   * what a rerank merge consumes. A provider emitting logits and another emitting
   * probabilities over the same ranking are mixable.
   */
  @Test
  void scoreScaleShiftStillPasses() {
    RerankProvider a = new HashRerankProvider("hash", 42);
    RerankProvider shifted =
        new HashRerankProvider("hash-shifted", 42) {
          @Override
          public List<Float> score(String model, String query, List<String> documents) {
            return super.score(model, query, documents).stream()
                .map(s -> (float) Math.log1p(Math.exp(s * 100))) // monotone re-scaling
                .toList();
          }
        };
    RerankEquivalenceReport report =
        harness.compare(a, shifted, "m", queries(), corpus(), 5);
    assertTrue(report.pass(), "monotone re-scaling must not fail certification: " + report);
  }

  /**
   * The negative control: providers whose rankings differ must not certify. If this ever
   * passes, the gate is broken.
   */
  @Test
  void differentlySeededProvidersFail() {
    RerankProvider a = new HashRerankProvider("hash-a", 42);
    RerankProvider b = new HashRerankProvider("hash-b", 1337);
    RerankEquivalenceReport report =
        harness.compare(a, b, "m", queries(), corpus(), 5);
    assertFalse(report.pass(), "differently-seeded providers must not certify: " + report);
  }

  @Test
  void kendallTauBasics() {
    List<Float> x = List.of(3f, 2f, 1f);
    assertEquals(1.0, RerankEquivalenceHarness.kendallTau(x, x), 1e-9);
    assertEquals(-1.0, RerankEquivalenceHarness.kendallTau(x, List.of(1f, 2f, 3f)), 1e-9);
    // One adjacent swap out of three pairs: (3 concordant - 1 discordant... 2-1)/3.
    assertEquals(
        1.0 / 3.0, RerankEquivalenceHarness.kendallTau(x, List.of(3f, 1f, 2f)), 1e-9);
  }

  /**
   * One runtime emits exact ties where another emits the same scores at slightly different
   * precision (e.g. fp16 vs fp32 accumulation). Tau counts ties as concordant; the
   * tie-expanded top sets must agree too, or boundary picks differ for no real reason.
   */
  @Test
  void precisionTiesAtTheCutoffStillPass() {
    List<Float> withTies = List.of(0.9f, 0.1f, 0.1f, 0.1f, 0.1f, 0.01f);
    List<Float> nearTies = List.of(0.9f, 0.1000001f, 0.1f, 0.0999999f, 0.1f, 0.01f);
    RerankEquivalenceReport report =
        harness.compare(
            fixed(withTies), fixed(nearTies), "m", List.of("q"),
            List.of("a", "b", "c", "d", "e", "f"), 3);
    assertEquals(1.0, report.minKendallTau(), 1e-6);
    assertEquals(1.0, report.meanTopKOverlap(), 1e-6);
    assertTrue(report.pass(), "precision-level ties must not fail certification: " + report);
  }

  private static RerankProvider fixed(List<Float> scores) {
    return new RerankProvider() {
      @Override
      public String name() {
        return "fixed";
      }

      @Override
      public boolean supports(String model) {
        return true;
      }

      @Override
      public List<Float> score(String model, String query, List<String> documents) {
        return scores.subList(0, documents.size());
      }
    };
  }

  private static List<String> queries() {
    return List.of("nearest neighbor search", "engine maintenance", "classical music");
  }

  private static List<String> corpus() {
    List<String> corpus = new ArrayList<>(EquivalenceHarness.defaultProbeTexts());
    corpus.add("repair manuals for small gasoline engines");
    corpus.add("how distributed systems reach consensus");
    return corpus;
  }

  /** A deterministic stub: query+document hash to a pseudo-score, seeded per provider. */
  private static class HashRerankProvider implements RerankProvider {
    private final String name;
    private final long seed;

    HashRerankProvider(String name, long seed) {
      this.name = name;
      this.seed = seed;
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
    public List<Float> score(String model, String query, List<String> documents) {
      List<Float> scores = new ArrayList<>(documents.size());
      for (String document : documents) {
        long hash = seed;
        hash = 31 * hash + query.hashCode();
        hash = 31 * hash + document.hashCode();
        scores.add((hash % 1000) / 1000f);
      }
      return scores;
    }
  }
}
