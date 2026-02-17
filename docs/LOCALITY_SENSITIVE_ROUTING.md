# Research: Locality Sensitive Sharding (LSS) & Routing

## The Concept
Current sharding is random (Round-Robin or Hash-based). This guarantees uniform distribution but forces **every shard** to be searched for **every query**.

**Locality Sensitive Sharding (LSS)** groups "similar" vectors onto the same shard at index time.
*   **Clustering**: Vectors are assigned to shards based on a coarse clustering algorithm (e.g., K-Means on the coordinator).
*   **Routing**: "Literature" vectors go to Shards 1-2. "Technical" vectors go to Shards 3-4.

## The Query-Time Advantage
When a query arrives, the coordinator first checks a lightweight "Centroid Index" to determine which shards are most likely to hold relevant data.

1.  **Targeted Search**: If the query is "Technical," the coordinator sends the request *only* to Shards 3 and 4.
2.  **Pruning**: Shards 1, 2, 5, 6, 7, 8 do **zero work**.

## The `ordToDoc` Twist
We can encode routing metadata into the `GlobalId` or leverage `ordToDoc` to handle "Multi-Shard Documents."

If a document is large and has chunks that span multiple topics (e.g., a technical manual with a history section), its chunks might land on different shards.
*   **Index Time**: We record that `DocID: 100` exists on `Shard: 3` and `Shard: 5`.
*   **Query Time**: If Shard 3 finds a high-scoring chunk for Doc 100, it can signal Shard 5 to "boost" that document or signal Shard 1 to "ignore" it.

## Implementation Strategy
1.  **Coarse Quantizer**: A small, in-memory index on the Coordinator that maps `Vector -> ShardID`.
2.  **Routing Table**: A map of `ShardID -> Metadata` (e.g., "History", "Tech", "Biology").
3.  **Smart Router**: A gRPC interceptor that intercepts the `SearchRequest` and decides:
    *   **Broadcast**: Query is ambiguous; send to all.
    *   **Unicast/Multicast**: Query is specific; send only to relevant shards.

## Impact on Cluster
*   **IO Reduction**: 75% of shards stay idle for any given specific query.
*   **Cache Locality**: Shards become "experts" in their domain, keeping relevant graph neighborhoods hot in the page cache.
