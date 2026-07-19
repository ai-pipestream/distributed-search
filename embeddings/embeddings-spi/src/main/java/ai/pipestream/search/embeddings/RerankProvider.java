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

package ai.pipestream.search.embeddings;

import java.util.List;

/**
 * A cross-encoder reranker: given a query and a batch of candidate documents, one relevance
 * score per document. This is the head stage of a retrieve-then-rerank pipeline — a cheap
 * first stage (HNSW, BM25) proposes a deep candidate set, and a reranker re-scores the head
 * of it with a model too expensive to run over the whole index.
 *
 * <p>Same conventions as {@link EmbeddingProvider}: plain Java, blocking, JDK types,
 * discovered via {@link java.util.ServiceLoader}. The two-lane policy applies with a rerank
 * twist: two providers may only be mixed for the same model after the ranked-list
 * certification (top-k overlap and rank correlation, not vector cosine) passes — rerankers
 * differ in score scale even when they agree on order, so mixing uncertified scores corrupts
 * merges.
 */
public interface RerankProvider {

  /**
   * The stable provider id, e.g. {@code "tei"}.
   *
   * @return the provider id
   */
  String name();

  /**
   * Whether this provider can serve the given reranker model id.
   *
   * @param model the model identifier as pinned by the calling collection
   * @return true if {@link #score(String, String, List)} will work for it
   */
  boolean supports(String model);

  /**
   * Score each document against the query, blocking, returning one score per document in the
   * same order. Scores are only meaningful within one model and provider — never merge raw
   * scores across models.
   *
   * @param model the model identifier
   * @param query the query text
   * @param documents the candidate documents
   * @return one score per document, in order
   */
  List<Float> score(String model, String query, List<String> documents);
}
