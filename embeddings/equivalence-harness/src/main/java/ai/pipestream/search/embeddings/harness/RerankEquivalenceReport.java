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
 * The verdict of one reranker-pair certification run: whether the same model served by two
 * {@link ai.pipestream.search.embeddings.RerankProvider}s ranks documents equivalently enough
 * that the pair may be mixed.
 *
 * @param minKendallTau the worst rank correlation over the probe queries (the binding metric;
 *     scale-invariant, so it tolerates different score conventions)
 * @param meanKendallTau the mean rank correlation over the probe queries
 * @param meanTopKOverlap mean fraction of shared document indices between the two providers'
 *     per-query top-k sets
 * @param pass whether both thresholds were met
 */
public record RerankEquivalenceReport(
    double minKendallTau, double meanKendallTau, double meanTopKOverlap, boolean pass) {}
