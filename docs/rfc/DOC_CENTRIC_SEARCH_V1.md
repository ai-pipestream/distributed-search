# RFC: Document-Centric kNN with Shard-Local Block Joins and a Shared Floor

- **Status:** Draft
- **Affects:** `knn-node` (index and query paths), `embeddings/` (pipeline
  stage order), `knn-node/src/main/proto/v1alpha1` (additive fields)
- **Depends on:** `DiversifyingNearestChildrenKnnCollector` (Lucene `join`
  module, in the `ai.pipestream:lucene-join:11.0.0-experimental-SNAPSHOT`
  fork snapshot), the shared-floor collector stack
  (`SharedFloorKnnCollectorManager` / `FloorAwareKnnCollector` /
  `GlobalKnnFloor`, Lucene sandbox fork, apache/lucene#16357)
- **Related:** [SEARCH_API_V1.md](SEARCH_API_V1.md) (frame protocol this
  extends), the coordinator rerank head (landed, `knn.rerank.*`)

This RFC defines document-centric vector search over chunked documents:
queries return top-D *documents* with per-chunk scores for highlighting,
ingestion stays fully decoupled per chunk, and the shared score floor prunes
traversal on parent scores across shards. It replaces neither the existing
flat-chunk mode nor the collaborative floor; it composes them.

---

## 1. Problem

RAG-style search indexes documents as chunks: one parent document, hundreds
of chunk-level vectors. Two index shapes exist today, and both fail in one
direction.

**Nested (all chunks inside one document).** OpenSearch's nested kNN and
Lucene block join both place every chunk of a document in a single indexed
unit. The whole document (all chunks, all vectors) is assembled client-side
and written as one operation; updating one chunk rewrites the document; all
of a document's vector cost concentrates on one shard forever. Ingest
couples to document size, and large documents are the common case.

**Flat (every chunk an independent document).** Ingestion is free, but
top-k has no document-level guarantee: one large document's chunks can
dominate the heap, and the fixes (collapse, terms aggregation) are post-hoc
grouping that returns one chunk per document rather than the top-D
documents with their per-chunk scores.

The goal is the combination neither shape offers: document-level guarantees
at query time with chunk-level freedom at ingest time, plus the per-chunk
scores a frontend needs to highlight the matching regions of a document.

## 2. Vocabulary

- **Parent document (P):** the logical document a user reads: text,
  metadata, title. Stored once, fetched by id, not part of the vector
  indexes.
- **Chunk (C1..Cn):** one text segment of P with its embedding vector.
- **Parent stub:** a small Lucene document acting as the block-join anchor.
  Carries the parent doc id and optional filter metadata. Not the parent
  document itself.
- **Block:** one shard's group of chunks for one parent, written as a single
  `IndexWriter.addDocuments` call terminated by the parent stub, as the
  Lucene join module requires.
- **Parent score:** a document's score derived from its chunks. This design
  supports max-style scoring (`score(P) = max over chunks of score(C)`);
  see [§6.2](#62-the-floor-soundness-rule) for why.

## 3. Design overview

```
        ingest                                    query
                                                 
  P --------------> parent store          client --> SearchService.Search
  C1..Cn -> embed -> cluster -> route               (stream)        |
       (per chunk)   (balanced,   +---> shard A: [chunks, stub]     v
                      by vector)  +---> shard B: [chunks, stub]   coordinator
                                                                  |  merge by doc id (max)
        floor advertisements <--------------------------+         v
        (D-th best parent score)                   diversifying  ranked docs
                                                   collectors    + chunk scores
                                                   per shard,    + rerank head
                                                   floor-pruned  + highlight
```

Five mechanisms, each independently useful:

1. **Shard-local block joins.** Each shard holds only the chunks assigned
   to it, block-joined under a parent stub. The parent document lives
   outside the vector indexes and is fetched by id after ranking.
2. **Document-centric collection.** Each shard runs
   `DiversifyingNearestChildrenKnnCollector`: top-D distinct parents with a
   hard visit cap, per-parent child scores retained.
3. **A shared floor on parent scores.** The existing
   `FloorAwareKnnCollector` decorates the diversifying collector per leaf;
   `GlobalKnnFloor` tracks the D-th best parent score across the query and
   across nodes.
4. **Coordinator merge by doc id.** Parents arriving from several shards
   collapse to one result. For max scoring the merge is exact
   ([§5.3](#53-merge-exactness)).
5. **Chunk scores ride the hits.** The collector's per-parent child scores
   are returned with each hit, giving the frontend every above-threshold
   region of the document in one pass.

## 4. Index path

```
chunk -> embed -> cluster (balanced, by vector similarity) -> route -> block-write with stub
```

### 4.1 Embedding moves ahead of routing

Shard assignment needs the chunk vectors, so embedding happens before
routing in the write path. The embeddings providers (embeddings-spi) sit at
this stage. This is a stage reorder, not a new pass: embeddings were always
computed before indexing; they are now also an input to placement.

### 4.2 Balanced similarity clustering

A document's n chunks are grouped into S clusters (S = shard count) by
vector similarity, target size `ceil(n / S)` per cluster. Pure similarity
clustering produces uneven groups (a document may cluster 280/15/5), which
reintroduces skew permanently at ingest time, so the clustering is
balance-constrained: clusters cap near `ceil(n / S)` and overflow spills to
the next-nearest cluster. k-means, agglomerative, or a greedy
nearest-neighbor chain are all acceptable at document scale (hundreds of
vectors); the algorithm is an implementation choice behind one interface.

Why similarity and not position, and why co-location at all: see
[§7](#7-shard-assignment-analysis).

### 4.3 Placement

Each cluster is block-written to its shard with a parent stub carrying the
parent doc id. The starting shard rotates per document
(`hash(parent_doc_id) mod S` places the first cluster), so hot sections of
many documents spread across the cluster over time.

### 4.4 Updates

Blocks are immutable once written. Updating a chunk rewrites that shard's
block for the parent (the shard's share of the chunks), not the whole
document. Coupling shrinks from document scope to shard scope; it does not
vanish.

## 5. Query path

### 5.1 Per-shard collection

Each shard runs the diversifying collector over its local blocks: a heap of
top-D distinct parents, capped visits, per-parent child scores retained
(the collector's `ParentChildScore` entries). The visit cap bounds work per
shard regardless of floor activity.

### 5.2 Floor decoration

A new manager, `SharedFloorDiversifyingKnnCollectorManager`, composes the
two existing pieces exactly as `SharedFloorKnnCollectorManager` composes
`TopKnnCollector` with `FloorAwareKnnCollector`: per leaf, build the
diversifying collector (parent bitset, top-D heap) and decorate it with
`FloorAwareKnnCollector` against a `GlobalKnnFloor` sized to D documents.
The floor is sized and gated by the same math (`perShardGate`, activation
threshold, publish-once per leaf); the decoration is valid because
`DiversifyingNearestChildrenKnnCollector` is an `AbstractKnnCollector`, the
same base the floor wraps today.

### 5.3 Merge exactness

The coordinator merges shard results by parent doc id. For max scoring the
merge is exact:

```
score(P) = max over all chunks of P
         = max( max over shard A's chunks, max over shard B's chunks, ... )
```

Splitting a document across shards cannot change its global score, so the
distributed result equals a single-index block join over all chunks. This
is the property that makes shard-local blocks a placement choice rather
than a correctness compromise. Mean- and sum-style aggregation are out of
scope ([§6.2](#62-the-floor-soundness-rule)).

### 5.4 Hits, highlighting, and the downstream stages

Each merged hit carries the parent doc id, the parent score, and the
per-chunk scores from every contributing shard. A frontend highlights all
above-threshold regions of the document without a second query. The
existing rerank head reorders the merged documents; snippet extraction (a
per-document LLM pass) runs after rerank on the displayed page only, over
parent text fetched post-rank, as an agent-side stage. Neither is required
by this RFC; both compose with it.

## 6. The shared floor on parent scores

### 6.1 One unit per query, declared by the request

The floor tracks exactly one score unit per query execution, declared by
the query contract: chunk mode (today) or document mode (this RFC). The
manager construction already guards size (`GlobalKnnFloor.k() == k`); the
unit rides with the query definition. Score-band or bit-tag multiplexing of
several units into one float was considered and rejected: pruning decisions
live in the 4th-6th decimal of a similarity score, exactly where recall is
sensitive, and per-query floor state is too small to justify lossy
encoding. If multi-unit floors ever become real, units are tagged in the
coordination wire protocol, not in the score bits.

### 6.2 The floor soundness rule

Pruning against the parent floor is sound for max-style parent scores: a
chunk that cannot beat the D-th best parent score cannot lift its parent
into the top-D, because the parent's score is its best chunk. For
sum-aggregated parent scores the rule fails (many weak chunks sum past the
floor), and for mean it is fragile. This RFC supports max scoring only;
other aggregations need per-shard partial sums shipped with results and are
deferred.

### 6.3 Dedup falls out structurally

`GlobalKnnFloor`'s distinct-document contract (advertisers must dedup
documents appearing on several searchers) is satisfied by the coordinator's
merge: parents from multiple shards collapse by doc id before and after the
floor is consulted. The floor never sees duplicate parents.

## 7. Shard assignment analysis

Three policies were evaluated.

**Round-robin by chunk position (max spread).** Every shard holds some of
every document's chunks. Under a query where document X is highly relevant,
every shard keeps finding candidates of X that beat the rising floor, so
every shard keeps searching: total visits multiply by the shard count.
Rejected.

**Contiguous blocks by position.** X's relevant section concentrates on one
shard; that shard searches hard and advertises; the others see the floor
rise past anything they hold and exit early. Termination comes from
advertisement, which is position-independent, so concentration is pure win.
Positional adjacency is also a free proxy for semantic similarity, which
helps HNSW locality. Accepted as the fallback when clustering is disabled.

**Balanced similarity clusters (this RFC).** Upgrades concentration from
positional to semantic: a document that interleaves topics still places
each topic region on one shard, and block neighbors are nearest in vector
space, giving each shard a tight local subgraph per document. Costs one
clustering pass over vectors that already exist at ingest.

The shared floor is what makes concentration safe: early termination is a
property of score advertisement, not of where a document lives. Availability
is the one trade spread wins: a lost shard takes a contiguous document's
whole relevant section with it, degrading that document's score to whatever
survives elsewhere. Rotation of the starting shard per parent
([§4.3](#43-placement)) keeps the same failure from always hitting the same
shard.

## 8. Wire contract changes (v1alpha1, additive)

- `KnnQuery.document_centric` (bool): return top-D documents with chunk
  scores instead of top-k chunks. Orthogonal to the existing
  `KnnQuery.collaborative` flag; the modes compose
  ([§5.2](#52-floor-decoration)).
- `ChunkHit { string chunk_id; float score; string text; }` and
  `repeated ChunkHit chunks` on `Hit`: the per-chunk scores and text for
  highlighting, populated in document-centric mode.
- The frame protocol is unchanged: `SearchContext`, unordered `Hit`s,
  `Progress`, terminal `Summary` with the authoritative `top_doc_ids`.
- Snippet extraction is not a wire change: it runs post-`Summary` on the
  final ranking. A per-document `Snippets` RPC (modeled on `Explain`)
  remains an optional follow-up for clients that want the server to do it.

## 9. What exists and what is new

| Piece | Status |
|---|---|
| `DiversifyingNearestChildrenKnnCollector` (+ manager) | In Lucene `join`, shipped in the fork snapshot |
| `FloorAwareKnnCollector`, `GlobalKnnFloor`, gate math | In the fork sandbox (apache/lucene#16357), unchanged |
| `SharedFloorDiversifyingKnnCollectorManager` | Implemented in the fork's join module (`ai-pipestream/lucene@df49da5b`), with per-parent publication in `FloorAwareDiversifyingChildrenKnnCollector`; unit, policy, and recall tests green |
| Balanced similarity clustering of chunks | New, in the index pipeline (embeddings stage) |
| Block-join write path with parent stubs | New, in collection indexing |
| Coordinator merge by doc id with chunk payloads | New, replaces flat ranking in document-centric mode |
| v1alpha1 additive fields | New ([§8](#8-wire-contract-changes-v1alpha1-additive)) |
| Rerank head, snippet stage | Existing / agent-side, unchanged |

## 10. Test plan

- **Unit:** the composed collector over fixed vectors (floor engaged and
  disengaged, max-score soundness cases, publish-once per leaf); cluster
  balance (cap respected, overflow placement); merge exactness on
  constructed shard results.
- **Single-node integration:** a block-joined test index; document-centric
  queries with and without the floor must return identical ranked docs with
  equal scores, with visits strictly lower under the floor above the
  activation threshold.
- **Two-node live:** a document split across both shards returns once, with
  the true global max score, equal to a single-index baseline over the same
  chunks; floor advertisements observed on the coordination channel.
- **Bench:** recall, latency, and visits against flat-chunk mode at equal
  k, on the existing benchmark corpora; ingestion throughput against the
  nested-style baseline.

### 10.1 Proof order

Mechanism first, composition second. The flat shared-floor sweep is the
foundation: the composition inherits the gate math, greediness clamp, sync
discipline, and pro-rata sizing from the flat collector manager, so its only
open question is whether the visit savings survive the unit change from
documents to parents. If the flat numbers do not hold, there is no
document-centric claim to make.

For the composition itself, soundness and value are proven separately.
Soundness is already covered by unit tests, which assert the floor never
exceeds the true top-parents cutoff exactly, per query, plus the
publish-once-per-parent invariant. What the bench must prove is value, and
that is not a foregone conclusion: a parent floor converges more slowly
than a document floor, because the heap needs D distinct parents and each
parent is published exactly once, early and conservatively. The bench exists
to show the slower convergence still nets out.

Bench tooling for the document-centric arm (the block-join index builder
and the exact parent ground truth) is independent of the flat results and
can be built while the flat sweep runs. The headline claims land in order:
flat sweep first, composition second.

### 10.2 Bench metrics and graphs

The collectors expose `visitedCount`, so the harness only records; no new
instrumentation is needed in the library.

- Visited vectors per query at matched recall, stock versus floored, swept
  over k (log-log). The primary evidence graph.
- Recall versus visits per greediness setting, the tradeoff curve.
- Per-shard visit distribution: hot shards run long, cold shards stop
  early.
- Floor convergence trace: floor value over visited count per shard, with
  the merged cutoff as a reference line. This graph is unique to the
  mechanism and shows why it works.
- Chunks-per-document scaling, the differentiator the flat bench cannot
  produce: fixed corpus, vary chunks per document and D, plot visits at
  matched parent recall. Stock pays per chunk while the floor converges in
  parent units, so the visit gap should widen with chunk multiplicity. This
  is also the regime where nested-style baselines degrade, so the stock
  block-join arm doubles as the competitive comparison.

Every bench run asserts the floor never exceeds the true cutoff,
continuously, as telemetry. The cost is nil (the floor object is already
there) and it turns each run into a soundness proof at scale: a contract
violation caught on a multi-shard run is worth far more than one caught in
a toy test.

## 11. Non-goals

- Mean- or sum-aggregated parent scoring ([§6.2](#62-the-floor-soundness-rule)).
- Multi-unit score floors ([§6.1](#61-one-unit-per-query-declared-by-the-request)).
- Cross-index parent resolution (the parent store is keyed by id; its
  placement is a deployment choice).
- Server-side snippet extraction ([§8](#8-wire-contract-changes-v1alpha1-additive)).

## 12. Open questions

- Clustering algorithm: balanced k-means vs. a greedy nearest-neighbor
  chain; both are cheap at document scale, one must be chosen for the first
  implementation.
- Stub contents beyond the parent doc id: which filter metadata (language,
  source, tenant) earns a place in the block.
- Re-chunk protocol: how an updated document's new chunk set replaces old
  blocks across shards (delete-by-parent-id then rewrite, presumably).
- Maximum chunks per document before a document is rejected or split by a
  different policy.
