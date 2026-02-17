# Strategy: Dynamic Collaborative K (The Reactive Firehose)

## Objective
Eliminate the "Over-search Problem" in distributed systems where every shard is forced to find $K$ results, even if its local data is entirely irrelevant to the query.

## The Mechanism: The Reactive Firehose

Instead of a "Pause and Resume" model which introduces latency bubbles, we utilize a **Bi-directional Reactive Firehose**:

1.  **Continuous Stream**: Shards begin searching for a large maximum budget (e.g., $K_{max}=5000$).
2.  **Instant Emission**: As soon as a competitive hit is found, it is pushed into the gRPC bi-directional stream to the coordinator.
3.  **Brain-Led Coordination**: The coordinator maintains the global Top-K and Document Set. 
    *   It broadcasts the **Global $K^{th}$ Score** back to all shards to prune their local HNSW frontiers.
    *   It broadcasts **Satisfied DocIDs** to prevent shards from scoring redundant chunks.
4.  **The Kill Switch**: Once the global $K$ is satisfied (across all shards), the coordinator **cancels the streams**. Shards receive the `onCancelled` signal, trigger `results.earlyTerminate()`, and exit the search loop instantly.

## Collaborative Synergy
This model leverages gRPC bi-directional streaming to handle multiplexing across multiple concurrent queries. Shards never stop searching until they are finished or killed by the coordinator. This ensures the hardware is always utilized for relevant work and never sitting idle during network round-trips.

## Impact
*   **Zero Latency Bubble**: Shards work at full tilt until satisfied or killed.
*   **CPU Efficient**: Eliminates redundant vector math as early as possible.
*   **Balanced Cluster Load**: Shards with relevant data do more work; shards with irrelevant data are "killed" after a few milliseconds.
*   **Ideal for High K**: When $K=5000$, this prevents the cluster from doing the work of 50,000+ vector scores if the top 5,000 are found in the first few shards.
