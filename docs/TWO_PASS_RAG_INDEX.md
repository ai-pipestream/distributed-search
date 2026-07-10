# Research: Chunk-Level Cross-Document Alignment (The "Two-Pass" Index)

## The Core Problem: The Document vs. Chunk Paradox
In multi-vector (RAG) datasets, standard HNSW creates a massive graph of individual chunks. A single document with 3,000 chunks creates a dense internal sub-graph. 

The problem is that standard similarity metrics (cosine, dot product) are entirely **Vector-Centric**. They have no idea that two nearby chunks belong to the same document. This causes "Graph Congestion" where the Top-K results are dominated by thousands of redundant chunks from a single highly-relevant document, forcing the searcher to waste CPU stepping over them to find the next unique document.

## The Solution: The Two-Pass Adaptive Index
We propose a radical shift from static vector indexing to a **Self-Optimizing Semantic Graph**. Instead of relying on static centroids (which lose fidelity), we use a two-pass indexing strategy that forces the graph to understand *Cross-Document Similarity* at the *Chunk Level*.

### Pass 1: The Initial "Greedy" HNSW Graph
*   **Action:** Standard HNSW indexing.
*   **State:** Every chunk is a node. Edges connect chunks based purely on raw mathematical similarity.
*   **Result:** A massive, congested graph where documents dominate their local neighborhoods.

### Pass 2: The "Cross-Document Edge" Re-Optimization
The second pass is where the RAG-specific optimization occurs. The goal is to rewire the graph to ensure that we are maximizing the surface area of *unique documents* in any given neighborhood.

For every chunk $C_i$ in Document $D_X$:
1.  **The Local Search:** We perform an internal HNSW search starting from $C_i$.
2.  **The Filter:** We enforce a strict exclusion filter: **Find the Top-$K$ chunks that DO NOT belong to Document $D_X$.**
3.  **The Wiring (The Magic):** We take these highly similar *cross-document* chunks and create strong, preferential HNSW edges between them and $C_i$. 

## Why This Works (The Topology Shift)
By explicitly wiring chunks to their nearest neighbors in *other* documents, we fundamentally alter the graph topology:

1.  **Breaking the Document Cluster:** We prevent a document's chunks from only pointing to each other.
2.  **Fast-Laning to Diversity:** When a searcher enters a high-scoring neighborhood for Document $X$, the graph edges now act as "fast lanes" that point directly to the most semantically similar chunks in Documents $Y$ and $Z$.
3.  **Natural Short-Circuiting:** The searcher naturally explores diverse documents without needing an arbitrary, oversized $K$ limit to brute-force its way out of Document $X$'s local cluster.

## Live Updates & Continuous Optimization
This is not a static centroid model; it's a dynamic, living index.

*   **Ingestion:** When Document $W$ is ingested, its chunks run Pass 1 (standard HNSW insertion). Then they immediately run Pass 2, finding their nearest neighbors in the existing graph that belong to *other* documents, establishing their cross-document edges.
*   **Background Merges:** During Lucene segment merges (e.g., `TieredMergePolicy`), the background threads can quietly rerun Pass 2 for "hot" neighborhoods, continuously optimizing the cross-document surface area.

## The Result: A True "RAG Index"
This creates an index where **Document Similarity is defined by Chunk Connectivity.** It solves the "Needle in the Haystack" problem because the exact, high-fidelity vectors are preserved (no centroids), but the pathways *between* them are optimized for diverse document retrieval.

## Research Context & Novelty
This "Two-Pass" indexing strategy represents a novel intersection of graph theory and multi-vector retrieval (MVR) paradigms.

### 1. Distinction from Search-Time Deduplication (e.g., Vespa)
While modern systems like **Vespa** implement document-centric retrieval by modifying the HNSW search loop (stopping after $K$ unique `docids`), they still operate on a standard, vector-centric graph.
*   **The Difference:** Our approach optimizes at **Indexing Time**. By re-wiring the graph in Pass 2, we change the **Graph Topology** to favor cross-document navigation. This prevents the searcher from getting "trapped" in a dense single-document cluster, rather than simply deduplicating the results once the trap has already been sprung.

### 2. Metadata-Driven Edge Diversification
Standard HNSW uses a distance-based diversity heuristic (don't connect to a neighbor if a closer one already exists).
*   **The Innovation:** We introduce a **Metadata Constraint** to this heuristic. In Pass 2, the graph is actively re-engineered to maintain "Document Diversity." The graph edges act as a **Semantic Highway** between distinct source documents, ensuring that even in highly redundant chunked datasets, the search frontier always points toward new information sources.

### 3. Solving the MaxSim Recall Risk
Existing research (e.g., **Col-Bandit**) attempts to prune search based on score uncertainty.
*   **Our Solution:** By focusing on **Navigation (Topology)** rather than **Math (Pruning)**, we maintain bit-identical MaxSim parity. We aren't skipping the calculation; we are ensuring that the HNSW searcher is naturally "pushed" toward chunks of different documents, making the traversal itself document-aware without sacrificing absolute recall.
