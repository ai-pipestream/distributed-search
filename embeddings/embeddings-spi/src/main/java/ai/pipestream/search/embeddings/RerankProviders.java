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

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * {@link ServiceLoader} discovery for {@link RerankProvider}s; mirrors {@link
 * EmbeddingProviders}.
 */
public final class RerankProviders {

  private RerankProviders() {}

  /**
   * Load every registered rerank provider, in classpath order.
   *
   * @return the discovered providers, possibly empty
   */
  public static List<RerankProvider> load() {
    List<RerankProvider> providers = new ArrayList<>();
    ServiceLoader.load(RerankProvider.class).forEach(providers::add);
    return List.copyOf(providers);
  }

  /**
   * The first registered provider supporting the given model.
   *
   * @param model the model identifier
   * @return a supporting provider
   * @throws IllegalStateException if no registered provider supports the model
   */
  public static RerankProvider forModel(String model) {
    List<RerankProvider> providers = load();
    return providers.stream()
        .filter(provider -> provider.supports(model))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no rerank provider supports model '"
                        + model
                        + "'; registered providers: "
                        + providers.stream().map(RerankProvider::name).toList()));
  }
}
