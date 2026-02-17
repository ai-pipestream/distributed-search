# Research: Resumable HNSW Search (Streaming Frontier)

## The Concept
Standard HNSW search is "One-Shot": you ask for Top-K, it traverses the graph, returns results, and discards all state (visited set, candidate heap). If you need 10 more results, you must start over.

**Resumable HNSW** turns the searcher into a **Stateful Iterator**.
*   **Pause**: The searcher finds $K$ results but keeps the `CandidateHeap` and `VisitedBitSet` in memory.
*   **Resume**: When the coordinator requests more data, the searcher pops the next best candidate from the *existing* heap and continues traversal from exactly where it left off.

## Why This Changes Everything
1.  **Zero Waste**: We never re-traverse the same nodes for the same query.
2.  **True Streaming**: The coordinator can "sip" results from the cluster. It asks for 10. If the user scrolls down, it asks for 10 more. The cluster does *exactly* the incremental work needed, nothing more.
3.  **Dynamic K**: We don't need to guess "Global K" upfront. We can start with $K=10$ and let the application drive the depth of the search based on relevance or user interaction.

## Implementation Strategy
We need to introduce a `ResumableHnswSearcher` in our Lucene fork.

### 1. The State Object
```java
public class HnswSearchContext {
    public final NeighborQueue candidates;
    public final FixedBitSet visited;
    public final int[] entryPoints;
    // ... other internal state
}
```

### 2. The API
```java
public interface ResumableSearcher {
    /**
     * Start a new search and return the context.
     */
    HnswSearchContext start(float[] query, int initialK);

    /**
     * Continue an existing search to find 'nextK' more results.
     */
    TopDocs continueSearch(HnswSearchContext context, int nextK);
}
```

### 3. The "Leak"
We must modify `HnswGraphSearcher` to allow its internal loops to yield control back to the caller without clearing the `candidates` or `visited` structures. This effectively inverts the control loop of the standard search.

## Challenges
*   **Memory Pressure**: Keeping state open for thousands of concurrent queries could bloat heap usage. We need a "Context Lease" system to expire idle searches.
*   **Thread Safety**: A `HnswSearchContext` is not thread-safe. The coordinator must ensure that "resume" calls for the same query are serialized (or handled by the same worker thread).
