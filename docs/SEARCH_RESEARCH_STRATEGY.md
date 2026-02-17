# Strategy: Search/Re-search (Two-Phase KNN)

## Objective
Reduce total cluster-wide IO and CPU by satisfying "easy" queries with a minimal first-pass search, only escalating to a full search if certain similarity or density thresholds aren't met.

## The Workflow

### Phase 1: The "Probe"
1.  **Minimal K**: The coordinator issues a search to all shards with a very low $K'$ (e.g., $K = 5$).
2.  **Aggressive Pruning**: Shards return their absolute best results immediately.
3.  **Fast Termination**: If the union of these results contains $N$ hits above a "Confidence Threshold" (e.g., similarity > 0.95), the query returns to the user. Total search time is effectively the latency of the fastest shards.

### Phase 2: The "Deep Search"
1.  **Targeted Re-query**: If Phase 1 fails to find enough high-confidence results, the coordinator analyzes the hits.
2.  **Shard Filtering**: It identifies which shards actually contained the best candidates and issues a second, full-K search **only to those shards**.
3.  **Late Merging**: Results from the deep search are merged with the initial probes.

## Pros
*   **Massive Savings for Near-Duplicates**: For queries with exact or near-exact matches, cluster work is reduced by ~90%.
*   **Reduced Coordinator Fan-in**: Prevents the coordinator from being overwhelmed by $K 	imes Shards$ results for every query.

## Cons
*   **Double Latency for "Hard" Queries**: If no high-confidence results are found, the user pays the penalty of two sequential round-trips.
*   **Complex Scoring**: Requires careful tuning of the "Confidence Threshold" to avoid missing relevant results that might be buried slightly deeper in the graph.

## Best Use Cases
*   Deduplication engines.
*   High-traffic "Popularity" searches where common queries often have very high-similarity matches.
