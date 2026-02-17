# Strategy: ordToDoc Integration & Mapping

## Objective
Bridge the gap between HNSW's internal **Ordinal-based** graph and Lucene's **Document-based** index to enable document-centric pruning and efficient result enrichment.

## The Technical Gap
In Lucene HNSW, graph nodes are **Ordinals** ($0, 1, 2...N$). A single Document can have multiple ordinals if it contains multiple vector fields or chunked embeddings. 
Standard HNSW searchers only "see" ordinals. They don't know that Ordinal 5 and Ordinal 500 both belong to Document A.

## The Solution: Native ordToDoc Mapping

By integrating the `ordToDoc` mapping directly into the `KnnCollector` and `HnswGraphSearcher`, we unlock three "Stronger" capabilities:

### 1. Multi-Vector Short-Circuit
As detailed in `DOCUMENT_CENTRIC_SHORT_CIRCUIT.md`, we can skip scoring Ordinal 500 if Document A is already "satisfied" in our Top-K via Ordinal 5.

### 2. Document-Level Diversification
We can enforce a rule that says *"Return the Top 10 documents, but never more than 2 chunks from the same document."* This is handled in the `collect()` loop rather than a post-search filter, saving traversal cycles.

### 3. All-Chunk Enrichment
Because the searcher is document-aware, when a document "wins" the Top-K, the system can proactively fetch **all other ordinals** for that DocID. 
*   **Benefit**: The final gRPC response can include the "Winning Chunk" (highest score) plus all "Context Chunks" for that document, providing the RAG LLM with a complete picture without a second lookup.

## Implementation Hook
```java
// Inside HnswGraphSearcher.java
int docId = scorer.ordToDoc(friendOrd);
if (knnCollector.isDocSatisfied(docId)) {
    // PRUNE: We already have enough high-quality chunks for this document
    continue; 
}
```

## Synergy with gRPC
In a distributed environment, the coordinator broadcasts **Satisfied DocIDs** instead of just scores. This prevents "Shard Contention" where multiple nodes are all wasting time searching for different chunks of the same globally-dominant document.
