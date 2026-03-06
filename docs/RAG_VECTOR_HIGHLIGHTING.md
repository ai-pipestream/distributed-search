# Research: RAG Vector Highlighting & Two-Phase Document Retrieval

## The Vision: Beyond Single-Chunk Retrieval
Currently, ANN search in HNSW is fundamentally "vector-centric." A search returns the Top-K *vectors* (chunks). However, modern RAG (Retrieval-Augmented Generation) applications and user interfaces operate on *documents*. 

The ultimate goal is to allow the user to search by **Document**, returning $K$ documents, but also revealing $J$ semantically relevant chunks within each of those documents. This enables advanced UX features like:
*   **Semantic Highlighting:** Visually highlighting the specific paragraphs in a document that match the semantic intent of the query.
*   **Relevance Heatmaps:** Generating a visual "heat map" of a document, showing which sections are most dense with relevant information.
*   **Comprehensive Context:** Passing not just one chunk, but a contiguous block of highly relevant chunks to an LLM for generation.

## The Architecture: Fast-Match -> Deep-Verify (Alignment)
To achieve this without paying the massive "RAG Chunking Tax" during traversal, we propose a Two-Phase approach that mirrors how Lucene handles text highlighting (e.g., `UnifiedHighlighter`) and how ColBERT handles Late Interaction alignment.

### Phase 1: Fast-Match (Traversal-Layer Deduplication)
Leveraging the **Document-Centric Short-Circuiting** (DCSC) feature:
1.  The HNSW traversal uses `DistinctDocKnnCollector`.
2.  Once *any* chunk for Document $X$ hits the global threshold, Document $X$ is added to the "Satisfied Set."
3.  All other chunks for Document $X$ are skipped during the graph traversal.
*Result:* We quickly identify the Top-K *unique* documents without wasting CPU scoring redundant chunks.

### Phase 2: Deep-Verify & "Highlighting" (Post-Processing)
Once the Top-K documents are isolated, we perform an exhaustive, targeted alignment step:
1.  **Isolate:** For the Top-K DocIDs, retrieve *all* associated vectors/chunks (using DocValues or `LateInteractionField`).
2.  **Exhaustive Scoring:** Score every chunk of these specific documents against the query.
3.  **Tagging:** Identify all chunks within these documents that fall within a defined "relevance range" (e.g., within 5% of the max score).
*Result:* The system returns $K$ documents, each annotated with a subset of $J$ highly relevant chunks.

## Synergy with Collaborative Search
When this two-phase Document-Centric approach is combined with **Collaborative HNSW Search** in a distributed environment, the efficiency gains compound:
1.  **Collaborative Pruning:** Prunes irrelevant branches across the cluster.
2.  **Document-Centric Short-Circuiting:** Prunes redundant chunks within the local graph traversal.
3.  **Targeted Highlighting:** Offloads the heavy multi-vector scoring to a post-processing step on a vastly reduced dataset (only the Top-K docs).

By combining these, we potentially reduce HNSW ANN search costs by nearly an order of magnitude, making massive-scale, document-level semantic search and RAG entirely feasible.

## Implementation Considerations
*   **Where should Phase 2 live?** It could be implemented at the Lucene layer (as a specialized Highlighter or a two-phase Query/Collector) or optionally at the Coordinator layer in OpenSearch/Elasticsearch.
*   Putting it in the Coordinator layer allows the shards to return fast, minimal results (DocID + Best Score), while the Coordinator fetches the full vector payloads only for the final, globally sorted Top-K documents.
