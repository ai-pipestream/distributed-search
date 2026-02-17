# Research: Topology-Aware Coordination (Neighborhood Hints)

## Objective
Solve the "Recall Collapse" in collaborative HNSW by sharing approximate topological data (neighborhood hints) alongside raw scores. This prevents shards from being pruned prematurely if they are exploring high-value clusters that have different score distributions or "bridges" than the lead shard.

## The Problem: The Score-Only Blind Spot
In distributed HNSW, a global minimum score bar is a "blunt instrument." 
*   **The Bridge Problem**: A shard may need to traverse through low-similarity nodes (0.70) to reach a high-similarity cluster (0.95). If a global bar of 0.90 is set, the path to the winner is cut.
*   **Graph Skew**: Different shards have different connectivity. A 0.88 on the Ryzen host might be "deep" in its graph, while the Pi node at the same timestamp is still "shallow" and finding 0.80s.

## Strategy 1: Centroid ID (Coarse Alignment)
*   **Concept**: Perform coarse K-Means clustering at index time. Assign every vector a `CentroidID`.
*   **The Signal**: The lead shard broadcasts: *"I am currently finding winners in **Centroid #42**."*
*   **The Logic**: Peer shards check their current HNSW candidates. 
    *   If `candidate.centroid == 42`, **BYPASS** the global score bar (stay un-pruned).
    *   If `candidate.centroid != 42`, apply the global bar aggressively.
*   **Payload**: 4 bytes (`int32`).
*   **Pros**: Ultra-low network overhead; perfect for "Cluster-wide focus."

## Strategy 2: Binary Bit-Signature (Hamming Alignment)
*   **Concept**: Convert the 1024-dim float vector into a 1024-bit binary signature (1 = positive, 0 = negative).
*   **The Signal**: Lead shard sends the 128-byte bitset of its current best candidate.
*   **The Logic**: Peer shards perform a fast XOR (Hamming distance) between their local frontier and the lead bitset.
    *   If `HammingDistance(local, lead) < Threshold`, the shard is in the "right neighborhood" and continues searching.
*   **Payload**: 128 bytes.
*   **Pros**: No pre-indexing requirement; ARM-optimized (XOR/Popcount are extremely fast on Pi 5).

## Strategy 3: Principal Component Sketch (Feature Alignment)
*   **Concept**: Use dimensionality reduction (e.g., first 16 dimensions) to create a "sketch" of the winning vector.
*   **The Signal**: Lead shard sends `float[16]` of its best candidate.
*   **The Logic**: Peer shards calculate a "cheap" distance against this 16-dim sketch to estimate proximity to the global search frontier.
*   **Payload**: 64 bytes.
*   **Pros**: More granular than Centroid IDs; handles high-dimensional "planes" better than binary signatures.

---

## Integration with Dynamic K'
These hints enable **Adaptive Shard Budgets**:
1.  **Isolation Mode**: Shards start with $K=10$ and aggressive pruning.
2.  **Affinity Mode**: If a shard detects it is in the "winning neighborhood" (via hint), it automatically expands its budget to $K=GlobalK$ and relaxes its pruning bar.

## Impact on 8-Node Pi Cluster
By using **Topology-Aware Coordination**, we can maintain **99% Recall** while still achieving the **30-50% Visited Reduction** we saw on localhost. Shards only "bail out" when they are mathematically and topologically proven to be in irrelevant neighborhoods.
