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

import ai.pipestream.search.embeddings.RerankProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The reranker certification gate of the two-lane policy. Given the same model served by two
 * providers, {@link #compare} measures whether mixing them is safe by comparing *rankings*,
 * not scores:
 *
 * <ul>
 *   <li><b>Kendall tau</b> per probe query between the two providers' document orderings. Tau
 *       is scale-invariant, which is the point: two runtimes may emit different score scales
 *       (logits vs probabilities) while agreeing on order, and order is what a rerank merge
 *       consumes.
 *   <li><b>Top-k set overlap</b> per probe query — the property a merged head actually relies
 *       on.
 * </ul>
 *
 * Defaults: worst-query tau ≥ {@value #DEFAULT_MIN_TAU}, mean top-k overlap ≥ {@value
 * #DEFAULT_MIN_TOPK_OVERLAP}. As with the embedding gate, a PASS admits the pair to the
 * accurate lane and the negative control (a differently-seeded stub) must keep failing.
 */
public final class RerankEquivalenceHarness {

  /** Default worst-query Kendall tau threshold: {@value}. */
  public static final double DEFAULT_MIN_TAU = 0.99;

  /** Default mean top-k set-overlap threshold: {@value}. */
  public static final double DEFAULT_MIN_TOPK_OVERLAP = 0.99;

  /**
   * Certify one reranker pair for one model.
   *
   * @param a first provider
   * @param b second provider
   * @param model the model id both must support
   * @param queries probe queries
   * @param corpus the probe corpus ranked per query by each provider
   * @param topK depth of each provider's per-query top set
   * @param minTau worst-query tau threshold
   * @param minTopKOverlap mean top-k overlap threshold
   * @return the verdict
   */
  public RerankEquivalenceReport compare(
      RerankProvider a,
      RerankProvider b,
      String model,
      List<String> queries,
      List<String> corpus,
      int topK,
      double minTau,
      double minTopKOverlap) {
    double min = Double.MAX_VALUE;
    double tauSum = 0;
    double overlapSum = 0;
    for (String query : queries) {
      List<Float> scoresA = a.score(model, query, corpus);
      List<Float> scoresB = b.score(model, query, corpus);
      double tau = kendallTau(scoresA, scoresB);
      min = Math.min(min, tau);
      tauSum += tau;
      overlapSum += topKOverlap(scoresA, scoresB, topK);
    }
    int n = queries.size();
    double meanTau = n == 0 ? 0 : tauSum / n;
    double meanOverlap = n == 0 ? 0 : overlapSum / n;
    return new RerankEquivalenceReport(
        min, meanTau, meanOverlap, min >= minTau && meanOverlap >= minTopKOverlap);
  }

  /** Compare with the default thresholds. */
  public RerankEquivalenceReport compare(
      RerankProvider a,
      RerankProvider b,
      String model,
      List<String> queries,
      List<String> corpus,
      int topK) {
    return compare(
        a, b, model, queries, corpus, topK, DEFAULT_MIN_TAU, DEFAULT_MIN_TOPK_OVERLAP);
  }

  /**
   * Kendall tau between two score lists over the same documents: concordant minus discordant
   * index pairs over all pairs. Near-ties in floating scores count as concordant half-pairs.
   */
  static double kendallTau(List<Float> x, List<Float> y) {
    int n = x.size();
    long concordant = 0;
    long discordant = 0;
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        int orderX = Float.compare(x.get(i), x.get(j));
        int orderY = Float.compare(y.get(i), y.get(j));
        if (orderX == 0 || orderY == 0 || orderX == orderY) {
          concordant++;
        } else {
          discordant++;
        }
      }
    }
    long total = (long) n * (n - 1) / 2;
    return total == 0 ? 1.0 : (concordant - discordant) / (double) total;
  }

  private static double topKOverlap(List<Float> x, List<Float> y, int k) {
    Set<Integer> topX = topKIndices(x, k);
    Set<Integer> topY = topKIndices(y, k);
    Set<Integer> intersection = new HashSet<>(topX);
    intersection.retainAll(topY);
    return intersection.size() / (double) k;
  }

  private static Set<Integer> topKIndices(List<Float> scores, int k) {
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < scores.size(); i++) {
      indices.add(i);
    }
    indices.sort(Comparator.comparingDouble((Integer i) -> scores.get(i)).reversed());
    return new HashSet<>(indices.subList(0, Math.min(k, indices.size())));
  }
}
