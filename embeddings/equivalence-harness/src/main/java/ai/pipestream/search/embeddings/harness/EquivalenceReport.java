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

/**
 * The verdict of one provider-pair certification run: whether the same model served by two
 * {@link ai.pipestream.search.embeddings.EmbeddingProvider}s is equivalent enough that the
 * pair may be mixed (the "accurate lane").
 *
 * @param minCosine the worst pairwise cosine over the probe texts (the binding metric)
 * @param meanCosine the mean pairwise cosine over the probe texts
 * @param meanTopKOverlap mean fraction of shared corpus indices between the two providers'
 *     per-query top-k sets over the probe corpus (retrieval equivalence — the property mixing
 *     actually relies on; catches tokenizer/padding drift that vector cosine can miss)
 * @param pass whether both thresholds were met
 */
public record EquivalenceReport(
    double minCosine, double meanCosine, double meanTopKOverlap, boolean pass) {}
