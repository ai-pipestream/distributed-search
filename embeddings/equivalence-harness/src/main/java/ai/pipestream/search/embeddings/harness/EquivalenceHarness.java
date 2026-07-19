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

import ai.pipestream.search.embeddings.EmbeddingProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The certification gate of the two-lane embedding policy. Given the same model id served by
 * two providers, {@link #compare} measures whether mixing them is safe:
 *
 * <ul>
 *   <li><b>Vector equivalence</b> — pairwise cosine over a probe set; the worst pair must clear
 *       the threshold (default {@value #DEFAULT_MIN_COSINE}).
 *   <li><b>Retrieval equivalence</b> — each provider embeds a probe corpus and probe queries
 *       and ranks the corpus against its own vectors; the mean overlap of the two top-k index
 *       sets must clear the threshold (default {@value #DEFAULT_MIN_TOPK_OVERLAP}). This is the
 *       property a mixed pipeline actually relies on, and it catches configuration drift
 *       (tokenizer, padding, normalization flags) that near-1 vector cosines can hide.
 * </ul>
 *
 * A PASS admits the pair to the accurate lane (mixable by routers and batch pipelines). A FAIL
 * keeps the model pinned to one provider. The gate is expected to close: the test suite runs
 * model2vec against a differently-seeded stub as a negative control, and a gate that stops
 * failing that control is itself broken.
 */
public final class EquivalenceHarness {

  /** Default worst-pair cosine threshold: {@value}. */
  public static final double DEFAULT_MIN_COSINE = 0.999;

  /** Default mean top-k set-overlap threshold: {@value}. */
  public static final double DEFAULT_MIN_TOPK_OVERLAP = 0.99;

  /**
   * Certify one provider pair for one model.
   *
   * @param a first provider
   * @param b second provider
   * @param model the model id both must {@link EmbeddingProvider#supports(String) support}
   * @param probeTexts texts embedded by both and compared pairwise
   * @param corpus the probe corpus ranked per query by each provider against its own vectors
   * @param queries the probe queries
   * @param topK depth of each provider's per-query ranking
   * @param minCosine worst-pair cosine threshold
   * @param minTopKOverlap mean top-k overlap threshold
   * @return the verdict
   */
  public EquivalenceReport compare(
      EmbeddingProvider a,
      EmbeddingProvider b,
      String model,
      List<String> probeTexts,
      List<String> corpus,
      List<String> queries,
      int topK,
      double minCosine,
      double minTopKOverlap) {
    if (a.dims(model) != b.dims(model)) {
      return new EquivalenceReport(0, 0, 0, false);
    }
    List<float[]> va = a.embed(model, probeTexts);
    List<float[]> vb = b.embed(model, probeTexts);
    double min = Double.MAX_VALUE;
    double sum = 0;
    for (int i = 0; i < va.size(); i++) {
      double cosine = cosine(va.get(i), vb.get(i));
      min = Math.min(min, cosine);
      sum += cosine;
    }
    double meanCosine = va.isEmpty() ? 0 : sum / va.size();

    List<float[]> corpusA = a.embed(model, corpus);
    List<float[]> corpusB = b.embed(model, corpus);
    List<float[]> queriesA = a.embed(model, queries);
    List<float[]> queriesB = b.embed(model, queries);
    double overlapSum = 0;
    for (int q = 0; q < queriesA.size(); q++) {
      Set<Integer> topA = topK(queriesA.get(q), corpusA, topK);
      Set<Integer> topB = topK(queriesB.get(q), corpusB, topK);
      Set<Integer> intersection = new HashSet<>(topA);
      intersection.retainAll(topB);
      overlapSum += intersection.size() / (double) topK;
    }
    double meanOverlap = queriesA.isEmpty() ? 0 : overlapSum / queriesA.size();

    return new EquivalenceReport(
        min, meanCosine, meanOverlap, min >= minCosine && meanOverlap >= minTopKOverlap);
  }

  /** Compare with the default thresholds. */
  public EquivalenceReport compare(
      EmbeddingProvider a,
      EmbeddingProvider b,
      String model,
      List<String> probeTexts,
      List<String> corpus,
      List<String> queries,
      int topK) {
    return compare(
        a,
        b,
        model,
        probeTexts,
        corpus,
        queries,
        topK,
        DEFAULT_MIN_COSINE,
        DEFAULT_MIN_TOPK_OVERLAP);
  }

  private static Set<Integer> topK(float[] query, List<float[]> corpus, int k) {
    // min-heap of (similarity, index) by similarity; k largest scores survive.
    PriorityQueue<int[]> heap =
        new PriorityQueue<>(k, (x, y) -> Float.compare(Float.intBitsToFloat(x[0]), Float.intBitsToFloat(y[0])));
    for (int i = 0; i < corpus.size(); i++) {
      float similarity = cosine(query, corpus.get(i));
      if (heap.size() < k) {
        heap.add(new int[] {Float.floatToIntBits(similarity), i});
      } else if (similarity > Float.intBitsToFloat(heap.peek()[0])) {
        heap.poll();
        heap.add(new int[] {Float.floatToIntBits(similarity), i});
      }
    }
    Set<Integer> result = new HashSet<>();
    for (int[] entry : heap) {
      result.add(entry[1]);
    }
    return result;
  }

  private static float cosine(float[] x, float[] y) {
    double dot = 0, normX = 0, normY = 0;
    for (int i = 0; i < x.length; i++) {
      dot += (double) x[i] * y[i];
      normX += (double) x[i] * x[i];
      normY += (double) y[i] * y[i];
    }
    return (float) (dot / (Math.sqrt(normX) * Math.sqrt(normY) + 1e-12));
  }

  /** Fixed probe texts shared by certifications and tests; short, varied, English. */
  public static List<String> defaultProbeTexts() {
    List<String> probes = new ArrayList<>();
    probes.add("the quick brown fox jumps over the lazy dog");
    probes.add("distributed vector search at scale");
    probes.add("how do i change the oil in a 2019 honda civic");
    probes.add("a muted trumpet plays softly in the background");
    probes.add("quarterly earnings rose twelve percent year over year");
    probes.add("the committee postponed its decision until spring");
    probes.add("photosynthesis converts sunlight into chemical energy");
    probes.add("she sold seashells by the seashore");
    probes.add("an error occurred while deserializing the response");
    probes.add("the trail climbs steeply above the treeline");
    return List.copyOf(probes);
  }
}
