# Document-Centric KNN: The Multi-Vector Short-Circuit

## The Evolution: Beyond Vector-Centric Search

While **Collaborative HNSW** focuses on sharing a global score to prune irrelevant branches, it still treats every vector as an independent entity. In modern RAG (Retrieval-Augmented Generation) workflows, a single document is often split into $N$ chunks, each with its own vector. 

This creates the **"RAG Chunking Tax"**:
1.  **Redundant Scoring**: If a document has 10 chunks, and 3 are highly relevant, Lucene explores the HNSW graph 10 times, potentially scoring all 10 even if the first chunk already secured the document's place in the Top-K.
2.  **Result Blinding**: A single high-relevance document can dominate the Top-K results with its own internal chunks, pushing out other relevant documents and requiring expensive post-search deduplication.

## The Strategy: Multi-Vector Short-Circuit

By leveraging Lucene's internal `ordToDoc` mapping, we can pivot the search engine to be **Document-Aware** during the HNSW traversal itself.

### 1. The `DistinctDocKnnCollector`
Instead of a simple heap of vectors, we implement a collector that understands the 1:N relationship between Documents and Ordinals. 
*   It tracks the **Top-K Distinct Documents**.
*   It maintains a "Satisfied" set of DocIDs that have already reached a similarity threshold sufficient for the current Top-K.

### 2. Immediate Graph Pruning
When the `HnswGraphSearcher` encounters a new candidate ordinal $O$:
1.  **Lookup**: $D = ordToDoc(O)$.
2.  **Check**: If $D$ is already in the "Satisfied" set with a score $S' \ge S_{current}$, the searcher **ABORTS** immediately.
3.  **Action**: No floating-point math is performed for the vector; no neighbors are added to the candidate queue; the branch is pruned.

## The "Stronger" Factor: Collaborative Synergy

This approach becomes exponentially more powerful in a distributed gRPC environment:

| Feature | Collaborative HNSW | Document-Centric Short-Circuit |
| :--- | :--- | :--- |
| **Shared State** | Global $K^{th}$ Score | Global $K^{th}$ Score + **Global Satisfied DocIDs** |
| **Pruning Scope** | Scores below the bar | Scores below the bar **AND** redundant chunks of "won" docs |
| **Network Gain** | Shards stop searching for bad results | Shards stop searching for *already found* documents |
| **Diversity** | Requires post-processing | Guaranteed $K$ distinct documents at the engine level |

### Cluster-Wide Document Awareness
In a sharded cluster, Shard A might find a "perfect" chunk for *Document 100*. Using the gRPC back-channel, the coordinator broadcasts: **"Document 100 is satisfied at Score 0.99."**

Shards B through Z, which might be mid-traversal for other chunks of *Document 100*, receive this signal and **instantly drop those branches**. This eliminates the "distributed competition" where shards waste cycles fighting over chunks of the same document.

## Expected Impacts
*   **CPU Utilization**: Anticipated **50-80% reduction** in total graph nodes visited for highly-chunked datasets (like Wikipedia or technical manuals).
*   **Latency**: Significant reduction in P99 latency by preventing "heavy" documents from clogging the search frontier of multiple shards simultaneously.
*   **Accuracy**: Higher diversity in the initial retrieval set, providing better context for the LLM in RAG pipelines.

## Implementation Path
1.  **Lucene Core**: Implement `DistinctDocKnnCollector` as a `Decorator`.
2.  **HnswGraphSearcher**: Add a "shouldVisit(ordinal)" check to the search loop to allow the collector to short-circuit before scoring.
3.  **gRPC Transport**: Update `ThresholdUpdate` proto to include a `repeated int64 satisfied_doc_ids` field.
4.  **Coordinator**: Manage the global "Satisfied Set" and broadcast deltas to active shards.
