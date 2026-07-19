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
 * A source of text embeddings: one named backend (a local in-process model, or a client of a
 * remote serving runtime) that can embed texts for the models it supports.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} and must be plain Java:
 * blocking calls, JDK types only, no framework annotations. Hosts (knn-node, ProtoMolt actions,
 * batch pipelines) wire providers in as plain jars.
 *
 * <p><b>The two-lane policy.</b> A provider/model pair may only be <em>mixed</em> with another
 * provider serving the same model — round-robin across endpoints, or different providers for
 * different batches of one pipeline — after the equivalence harness
 * ({@code equivalence-harness} module) has certified that pair as producing equivalent vectors
 * for that model (the "accurate lane"). Without certification a model must stay pinned to a
 * single provider (the "fast lane"), because two models, or one model served with different
 * tokenizer/padding configuration, produce different vector spaces that silently corrupt any
 * index or comparison that mixes them. The certification registry and the collection-level
 * model-identity pin are what enforce this; this interface deliberately knows nothing about it.
 */
public interface EmbeddingProvider {

  /**
   * The stable provider id, e.g. {@code "model2vec"}, {@code "openvino"}, {@code "tei"}.
   *
   * @return the provider id
   */
  String name();

  /**
   * Whether this provider can serve the given model id.
   *
   * @param model the model identifier as pinned by the calling collection
   * @return true if {@link #embed(String, List)} and {@link #dims(String)} will work for it
   */
  boolean supports(String model);

  /**
   * The vector dimension of the given model. Collections pin this value and reject mismatches.
   *
   * @param model the model identifier
   * @return the number of float components per embedding
   */
  int dims(String model);

  /**
   * Embed each text, blocking, returning one vector per input in the same order.
   *
   * @param model the model identifier
   * @param texts the texts to embed
   * @return one vector per input text, in order
   */
  List<float[]> embed(String model, List<String> texts);
}
