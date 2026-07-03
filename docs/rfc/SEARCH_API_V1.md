# RFC: Public Search API v1 (`ai.pipestream.search.v1alpha1`)

- **Status:** Draft
- **Proto files:** `knn-node/src/main/proto/v1alpha1/{common,search_service,index_service,admin_service}.proto`
- **Package:** `ai.pipestream.search.v1alpha1` (graduates to `.v1` when frozen; see [Versioning](#7-versioning-and-compatibility))

This RFC defines the first public wire surface of the engine. The current
protos (`knn.collab.KnnNodeService`, `ai.pipestream.index.v1.IndexService`)
grew out of the research harness; they leak internals (raw global ordinals,
coordinator floor frames, string-typed metadata) and cannot evolve without
breaking the benchmark tooling. v1 is a parallel, versioned surface: the old
protos stay untouched until clients migrate.

---

## 1. Design pillars and rationale

### 1.1 Streaming-first

Every long-running interaction is a stream, not a bigger response message.

- **Search** is server-streaming. The engine's collectors are incremental by
  construction — hits enter the top-k set one at a time, and the
  collaborative-floor machinery already produces mid-flight telemetry
  (visited counts, kth-best floors). Buffering all of that into one response
  would throw away exactly the property that differentiates this engine:
  results and progress exist *before* the query finishes. Streaming gives
  clients first-hit latency close to shard latency, lets UIs render
  progressively, and makes budget-limited queries observable (you can watch
  the budget being spent).
- **Bulk indexing** is bidirectional streaming with per-document acks. A
  unary-per-doc API caps throughput at RTT; a fire-and-forget client stream
  hides failures and gives the server no way to slow a producer down while
  Lucene flushes and merges. Bidi gives us both: acks carry per-doc status,
  and the server's response stream doubles as a flow-control channel
  ([§4](#4-bulkindex-backpressure-protocol)).
- **Metadata** is observable via an etcd-style watch. Routers, ingest
  pipelines, and dashboards all need to know when collections appear, shards
  move, or replicas change — polling `ListCollections` is racy and slow.
  A revisioned event stream (`WatchCollections`) gives them a consistent,
  resumable view.

### 1.2 Typed Query AST

The query language is a protobuf tree (`Query` with a `oneof` per node
type), not a JSON string and not a monolithic request message.

- Protobuf gives us structural validation for free (a `KnnQuery` cannot lack
  a vector *field*, a `RangeQuery` bound is typed), and every SDK gets a
  builder-based query DSL without any client-side parser.
- Node types in v1: `bool` (must / should / must_not / filter), `term`,
  `match` (analyzed), `phrase`, `range` (numeric/date via typed
  `FieldValue` bounds), `knn`, `hybrid`, `match_all`, and `query_string`.
- **Filters are first-class on `knn`** (`KnnQuery.filter`), and they are
  *pre-filters*: the filter restricts graph traversal, it does not trim
  results afterwards. This is a semantic guarantee of the API, not an
  optimization hint — post-filtering silently returns fewer than k results
  and we refuse to offer it implicitly.
- `knn` also carries the engine-specific knobs that make this engine what it
  is: `collaborative` (cross-shard floor sharing) and a per-clause
  `visit_budget`, plus `num_candidates` for classic ef_search control.
- `hybrid` composes any sub-queries and declares its fusion explicitly:
  `rrf { k }` or `linear { weights }`. Fusion is data, not server config, so
  two clients can rank the same collection differently.
- **Escape hatch:** `query_string` carries a Lucene classic query string
  parsed server-side. Rationale: migration from existing Lucene/OpenSearch
  tooling and ad-hoc exploration. It is deliberately the *only* stringly
  query node, and it carries the weakest compatibility promise
  ([§7](#7-versioning-and-compatibility)).
- We borrowed field naming from `opensearch-protobufs` where it is good
  (`field`, `value`, `boost`, `filter`, `minimum_should_match`, snake_case
  oneof variants) and rejected its shape: no 30-variant `QueryContainer`,
  no `optional` spam, no `x_name` tagging, no `ObjectMap` grab-bags. Every
  v1 message is small enough to read on one screen.

### 1.3 Per-query budgets

`SearchRequest.budget` (`SearchBudget`) carries three limits, and the
collectors honor all of them cooperatively:

| Field        | Meaning                                                      |
|--------------|--------------------------------------------------------------|
| `time_ms`    | wall-clock budget for the whole query                        |
| `max_visits` | graph-node/document visit budget, summed across shards       |
| `max_hits`   | result budget: max `Hit` frames the server may stream        |

Rationale: this engine's core trade is *recall for visits*. Making the trade
per-query (rather than per-collection or server-wide) lets one deployment
serve both "cheap and fast" autocomplete traffic and "spend the budget"
offline evaluation. Zero means "server default" — there is always an
effective budget, and the effective values are echoed back in `Progress` and
`Summary` frames so clients can see what they actually got. A query that
hits a budget is **not an error**: it terminates the stream normally with
`Summary.terminated_by` set to the exhausted budget and
`total_hits_relation = GTE`.

### 1.4 Collection admin with typed schemas

`CollectionAdminService` replaces the collection RPCs currently bolted onto
the legacy `IndexService`. The key change is the schema: instead of
`(vector_dimension, similarity, embedding_model)` plus untyped
`map<string,string>` metadata, a collection declares typed fields:

- `keyword`, `text` (with an `AnalyzerRef`), `numeric` (int32/int64/float/
  double), `date`, `boolean`, `dense_vector` (dims, similarity, index
  params — HNSW `m`/`ef_construction` in v1), each with a `stored` flag.
- `AnalyzerRef` is a `oneof`: a named built-in enum, or a
  `PluggableAnalyzer { name, endpoint, params }` reference resolved by the
  server's analyzer registry. This is the seam for OpenNLP pipelines and
  remote gRPC analyzers later, without any schema change.
- Sharding (`num_shards`) and replication (`ReplicationParams.replicas`)
  are creation-time parameters.

Typed schemas are what make the typed Query AST checkable: a `range` on a
`text` field or a wrong-dims vector is an `INVALID_ARGUMENT` at the API
boundary, not a silent Lucene mis-index.

### 1.5 Error model, pagination, versioning

- **Errors:** `google.rpc.Status` everywhere. RPC-level failures use the
  standard gRPC rich error model (status + details in trailers). In-band
  partial failures embed a `google.rpc.Status` field: per document
  (`DocAck.status`) and per shard (`ShardSummary.status`). No
  `bool success` / `string error` pairs anywhere in v1.
- **Pagination:** search_after-style opaque cursors. `Summary.next_cursor`
  carries the sort position of the last returned hit; the client passes it
  back verbatim in `SearchRequest.search_after`. No server-side scroll
  state, so cursors survive load-balancer re-routing and replica failover.
  Cursors are *not* guaranteed valid across schema changes.
- **Versioning:** proto packages are versioned (`ai.pipestream.search.
  v1alpha1`, files under `knn-node/src/main/proto/v1alpha1/`); see
  [§7](#7-versioning-and-compatibility).

---

## 2. Search stream frame protocol

A `Search` call produces a stream of `SearchResponse` frames, each exactly
one of `context`, `hit`, `progress`, `summary`.

### 2.1 Server guarantees (ordering)

1. **Exactly one `SearchContext`, always first.** The first frame of every
   stream carries the server-issued `query_id` (and the experiment arm, if
   one applied), *before* any hit. This is what lets a client log a click
   against `query_id` while the query is still running.
2. **Exactly one `Summary`, always last.** If the stream completes with
   gRPC status `OK`, the final frame is a `Summary`. No frame follows it.
   It echoes `query_id` and the experiment context, so the terminal frame
   alone is enough to correlate with the serving log.
3. **Errors are gRPC errors.** A stream that fails (bad query, unknown
   collection, every shard failed) terminates with a non-OK gRPC status and
   *no* `Summary` frame. Clients must not treat a missing `Summary` as
   anything but failure.
4. **Progress is monotonic.** Within the stream, `Progress.visited`,
   `Progress.elapsed_ms`, and `Progress.shards_completed` are
   non-decreasing, and `Progress.kth_best_floor` is non-decreasing once the
   top-k set is full.
5. **Hit budget.** At most `SearchBudget.max_hits` `Hit` frames are sent.
6. **Final ranking lives in the Summary.** `Summary.top_doc_ids` is the
   authoritative ranked result (best first, at most `size` entries), and
   every id in it was previously delivered as a `Hit` frame.
7. **Positions are emission-time.** `Hit.result_position` is the 1-based
   global position of the hit at the moment it was streamed — i.e. what a
   progressively-rendering UI showed. It is an analytics/display value;
   the final ranking (guarantee 6) supersedes it.

### 2.2 What a client must tolerate

- **Hits arrive unordered.** Shards race; a `Hit` with a lower score may
  arrive after one with a higher score.
- **Hits are a superset of the result.** A streamed `Hit` may later be
  displaced from the top-k and absent from `Summary.top_doc_ids`.
  The canonical client is: buffer hits by `doc_id`, render optimistically
  if desired, and materialize the final list from `top_doc_ids`.
- **Duplicate `doc_id`s are possible** (replica retries, shard overlap
  during relocation). Last-received wins; scores will be equal or improved.
- **`result_position` values do not form a permutation.** Two hits can
  carry the same position (a later, better hit re-claims an earlier slot),
  and positions can exceed `size`. Log them as-seen; reconcile offline
  against `Summary.top_doc_ids` if exact final positions matter.
- **`Progress` frames are optional and unevenly spaced.** Zero progress
  frames is a valid stream. `progress_interval_ms` is a request, not a
  contract.
- **Early termination is success.** Check `Summary.terminated_by` and
  `total_hits_relation`, not the gRPC status, to distinguish exhaustive
  from budget-limited results.
- **Partial shard failure is success with evidence.** Non-OK
  `ShardSummary.status` entries mean the ranking may be missing that
  shard's documents; the stream still ends `OK`.
- **Unknown frame variants must be skipped.** A future v1alpha revision may
  add new `oneof` variants to `SearchResponse`; old clients will see them
  as unset and must ignore, not fail.

### 2.3 Cancellation

Clients cancel by cancelling the gRPC call. The server treats cancellation
as budget-exhaustion-by-client: collectors stop at the next budget
checkpoint. No `Summary` is delivered after cancellation.

---

## 3. Explain

`Explain` is unary: one `(collection, doc_id, query)` in, one Lucene-style
recursive `Explanation` tree out. It exists so that relevance debugging does
not require reproducing the engine's scoring in client code. It executes
without budgets (single document) and is not intended for production hot
paths.

---

## 4. BulkIndex backpressure protocol

`BulkIndex` is a credit-window protocol layered on the bidi stream. HTTP/2
flow control alone is not enough: it reflects socket buffers, not Lucene
flush/merge pressure, which is where this engine actually falls over.

### 4.1 Roles

- **Client sends:** optionally one `BulkOptions` first (stream-default
  collection), then `IndexDocument` frames with strictly increasing
  `client_seq`, and `FlushMarker` frames wherever it needs durability.
- **Server sends:** `FlowControl` frames (credit grants), `DocAck` frames
  (one per document, correlated by `client_seq`), and `FlushAck` frames
  (one per `FlushMarker`).

### 4.2 Credit rules

1. The server's **first frame is a `FlowControl`** carrying the initial
   window `w`: the max number of un-acked documents the client may have in
   flight.
2. Each `DocAck` **returns one credit** (in-flight count decreases).
3. A new `FlowControl` frame **replaces** the window (it is not additive).
   `STATE_READY` restores the normal window; `STATE_THROTTLED` shrinks it
   because flush/merge backlog is building; `STATE_PAUSED` sets it to 0 —
   stop sending documents until the next `FlowControl`. `FlushMarker`
   frames are always allowed, so a paused client can still checkpoint.
4. A client that overruns its window is violating the protocol; the server
   may terminate the stream with `RESOURCE_EXHAUSTED`.

### 4.3 Ack and durability semantics

- `DocAck.status == OK` means *accepted and routed to a shard*: visible
  after the next flush, durable only after a covering `FlushAck`.
- Per-document failures (schema mismatch, wrong vector dims, unknown
  collection) come back as non-OK `DocAck.status` and **do not terminate
  the stream** — bulk loads keep flowing past bad documents.
- Acks may arrive **out of order across shards** (they are ordered per
  shard, but the client must correlate by `client_seq`, not position).
- `FlushAck.through_seq = n` guarantees every document with
  `client_seq <= n` is durable. A client that needs at-least-once semantics
  replays un-flush-acked documents on reconnect; indexing is
  upsert-by-`doc_id`, so replays are idempotent when the client assigns ids.

### 4.4 Why not just unary-with-batches?

Batched unary (`BulkRequest{repeated doc}`) forces the client to pick a
batch size that is simultaneously a latency, memory, and error-granularity
decision, and gives the server only one lever (fail/delay the whole batch).
The credit window separates those concerns: frame size stays small and
constant, the *server* owns the concurrency lever, and error granularity is
always per document.

---

## 5. Experiments and ranking profiles

The engine supports A/B experimentation as a *serving-side* concern only:
it decides which ranking behavior a query gets and stamps the query so
external analytics can measure the outcome. It never stores behavioral
data ([§6](#6-analytics-event-contract)).

### 5.1 Model

- **`RankingProfile`** (admin service): a named bundle of server-side
  behavior switches — fusion override for hybrid queries, default budgets,
  collaborative traversal on/off, search-time analyzer override. Unset
  fields mean "no override".
- **`Experiment`** (admin service, global or per-collection): a name, a
  hash `salt`, and weighted `arms`, each arm referencing a profile by name.
- **Assignment is deterministic and stateless:**
  `arm = weighted_pick(hash64(session_pseudo_id + salt), arms)`. Any node
  computes the identical assignment with zero coordination — a hard
  requirement for a masterless cluster. Changing the salt reshuffles the
  population; there is no sticky per-user state to migrate.
- Clients opt in by sending `SearchRequest.client_context.
  session_pseudo_id`. No id, no participation: the query serves the
  default profile and `SearchContext.experiment` stays unset.
  `experiment_overrides` (name → arm) forces an arm for QA; forced
  assignments are marked `ASSIGNMENT_SOURCE_OVERRIDE` so they can be
  excluded from analysis.
- Experiment and profile changes are observable via `WatchExperiments`,
  with the same revisioned, resumable semantics as `WatchCollections`, so
  serving nodes pick up rollouts without polling and dashboards can show
  exactly when a variant went live.

### 5.2 Privacy stance

`ClientContext` is **pseudonymous by contract**. `session_pseudo_id` must
be a random per-session token; it must not be a user id, email, device id,
or anything else that identifies a person. The engine treats it as an
opaque hash input, never persists it, and never returns it. There is no
PII expectation anywhere in the v1 surface, and none should be smuggled in
via `experiment_overrides`.

### 5.3 Per-query overhead

The entire experimentation surface costs one small frame per search
(`SearchContext`: `query_id` + `ExperimentContext`), one `int32` per hit
(`result_position`), and two echoed fields in the `Summary`. Nothing else
touches the search hot path; assignment is one hash per query.

---

## 6. Analytics event contract

**The engine never stores behavioral analytics.** No clicks, no sessions,
no dwell times, no event log. What it does instead is make the *join*
possible: every search is stamped with a server-issued `query_id` and the
serving arm, delivered in the first frame (so events can fire mid-query)
and echoed in the last. Measurement happens in whatever analytics tool the
product already uses; A/B analysis is

```
join( serving_log,  client_events )  on query_id
```

run in that tool or offline.

### 6.1 Standard client-side events

Clients (web/app frontends) should emit four events. Field sources:
`query_id` and `variant` come from the `SearchContext` frame
(`variant` = `experiment_name` + `:` + `arm`; empty when no experiment
applied); `position` is the `Hit.result_position` the user actually saw;
`doc_id` is the hit's id.

| Event              | When                                             | query_id | variant | position | doc_id |
|--------------------|--------------------------------------------------|----------|---------|----------|--------|
| `search_performed` | `SearchContext` frame received                   | yes      | yes     | —        | —      |
| `result_click`     | user activates a result                          | yes      | yes     | yes      | yes    |
| `result_dwell`     | user returns / leaves after ≥ threshold on a doc | yes      | yes     | yes      | yes    |
| `search_abandoned` | results rendered, no click before session moves on | yes    | yes     | —        | —      |

### 6.2 Mapping: GA4 (custom events)

Send as custom events with event-scoped custom parameters. Register
`query_id`, `variant`, `position`, `doc_id` as custom dimensions/metrics in
GA4 Admin, or the parameters will not be queryable; for the join itself use
the BigQuery export (the GA4 UI cannot join on high-cardinality params).

| Contract field | GA4                                                         |
|----------------|-------------------------------------------------------------|
| event          | `event_name` = `search_performed` / `result_click` / `result_dwell` / `search_abandoned` |
| query_id       | event param `query_id` (event-scoped custom dimension)      |
| variant        | event param `variant` (event-scoped custom dimension)       |
| position       | event param `position` (custom metric, integer)             |
| doc_id         | event param `doc_id` (event-scoped custom dimension)        |

`search_performed` may additionally set the recommended `search` event's
`search_term` param if the product wants GA4's built-in site-search
reports; that is cosmetic and not part of this contract.

### 6.3 Mapping: Matomo

Matomo's **native Site Search tracking** (`trackSiteSearch(keyword,
category, resultsCount)`) powers its built-in search reports — use it for
`search_performed`, but it cannot carry the join key by itself. Attach
`query_id`/`variant` as event-scoped **custom dimensions** (configure two
dimension slots in Matomo admin). Clicks and dwell map to Matomo events.

| Contract field | Matomo                                                             |
|----------------|--------------------------------------------------------------------|
| search_performed | `trackSiteSearch(term, collection, result_count)` + custom dims `query_id`, `variant` |
| result_click   | `trackEvent('search', 'result_click', doc_id, position)` + same custom dims |
| result_dwell   | `trackEvent('search', 'result_dwell', doc_id, dwell_seconds)` + same custom dims |
| search_abandoned | `trackEvent('search', 'search_abandoned')` + same custom dims     |

Join via the Reporting/Raw-data API (`Live.getLastVisitsDetails` or the
raw `log_link_visit_action` tables) on the `query_id` dimension.

### 6.4 Mapping: Plausible (custom props)

Plausible custom events with custom properties:

| Contract field | Plausible                                                      |
|----------------|-----------------------------------------------------------------|
| event          | `plausible('search_performed' \| 'result_click' \| 'result_dwell' \| 'search_abandoned', {props})` |
| query_id       | `props.query_id`                                               |
| variant        | `props.variant`                                                |
| position       | `props.position` (stringified; Plausible props are strings)    |
| doc_id         | `props.doc_id`                                                 |

Caveat: Plausible's dashboard aggregates props and is not built for
high-cardinality keys like `query_id`; the join requires the Stats API /
raw events export (or self-hosted ClickHouse access). Plausible is the
weakest fit of the four — fine for variant-level funnels, marginal for
per-query joins.

### 6.5 Mapping: Snowplow (structured events)

Using the five-field structured event (`se_*`); one convention, applied
uniformly:

| se field      | Value                                          |
|---------------|------------------------------------------------|
| `se_category` | `search`                                       |
| `se_action`   | `search_performed` / `result_click` / `result_dwell` / `search_abandoned` |
| `se_label`    | `doc_id` (empty for performed/abandoned)       |
| `se_property` | `query_id` `\|` `variant` (pipe-delimited)     |
| `se_value`    | `position` (dwell events: dwell seconds)       |

Teams already running Snowplow with schema registries should prefer a
self-describing event schema (`iglu:ai.pipestream/search_event/1-0-0`)
with the four contract fields as typed properties; the structured-event
mapping above is the zero-setup fallback.

### 6.6 Server-side serving log

For every search the server emits one serving-log record, shaped as an
OpenTelemetry **log record / span event** so any OTLP pipeline can export
it (the engine keeps no copy beyond its normal log retention):

| OTel attribute            | Source                                    |
|---------------------------|-------------------------------------------|
| `search.query_id`         | `SearchContext.query_id`                  |
| `search.collection`       | `SearchRequest.collection`                |
| `search.experiment`       | `ExperimentContext.experiment_name`       |
| `search.arm`              | `ExperimentContext.arm`                   |
| `search.assignment_source`| `ExperimentContext.assignment_source`     |
| `search.k`                | effective `SearchRequest.size`            |
| `search.latency_ms`       | `Summary.took_ms`                         |
| `search.visited`          | `Summary.visited`                         |
| `search.budget.time_ms`   | effective `SearchBudget.time_ms`          |
| `search.budget.max_visits`| effective `SearchBudget.max_visits`       |
| `search.budget.max_hits`  | effective `SearchBudget.max_hits`         |
| `search.terminated_by`    | `Summary.terminated_by`                   |
| `search.kth_best_floor`   | `Summary.kth_best_floor`                  |

A/B analysis is then a plain equi-join: export the serving log to the
warehouse, export the analytics tool's events, join on `query_id`, group
by `arm`. Which metric wins (CTR, dwell, abandonment, latency) and what
counts as significant is the analyst's business, not the engine's
([§8](#8-not-in-v1)).

---

## 7. Versioning and compatibility

- **Package = contract.** Everything under `ai.pipestream.search.v1alpha1`
  moves together. Files live in `knn-node/src/main/proto/v1alpha1/`; a new
  major or pre-release version is a new directory and package, served
  side-by-side (gRPC service names embed the package, so `.../v1alpha1.
  SearchService/Search` and a future `.../v1.SearchService/Search` coexist
  on one port).
- **While in `v1alpha1`:** additive changes (new fields, new `oneof`
  variants, new enum values, new RPCs) may land at any time; breaking
  changes are allowed but require bumping to `v1alpha2`. Alpha means: we
  will not silently change the meaning of a serialized field, but we may
  make you recompile.
- **Graduation to `v1`:** freezes the surface. After that, only additive
  changes; anything breaking starts `v2alpha1`.
- **Client obligations (forward compatibility):** ignore unknown fields,
  unknown `oneof` variants, and unknown enum values (treat as
  `*_UNSPECIFIED`). These are standard proto3 semantics; the frame protocol
  above restates them where they bite.
- **Deprecations** are marked with `[deprecated = true]` plus a comment
  naming the replacement, and survive at least one minor release of the
  server before removal (removal only at a version bump).
- **The `query_string` escape hatch is exempt** from the query-semantics
  guarantee: its parse behavior tracks the embedded Lucene version. Its
  *wire shape* follows the normal rules.
- **Legacy surface:** `knn.collab.KnnNodeService` and
  `ai.pipestream.index.v1.IndexService` are unversioned research protos.
  They remain untouched and unsupported-for-external-use; they will be
  retired after the coordinator and benchmark harness move to v1
  (see [§9](#9-migration-notes-legacy--v1alpha1)).

---

## 8. Not in v1

Explicitly out of scope. "Not in v1" means no wire shape is reserved and no
partial implementation will be accepted; each of these needs its own design
round.

- **Facets beyond counts.** v1 ships no aggregations at all; the first
  facet work (a later `v1alpha` revision) will be limited to simple term
  counts attached to the `Summary` frame. Statistical aggregations,
  histograms, and nested aggregations are out of scope for all of v1.
- **Highlighting.** Requires storing/re-analyzing text and a fragmenter
  contract; interacts badly with streamed hits (highlights would inflate
  every `Hit` frame). Deferred, including the vector-highlighting research
  in `docs/RAG_VECTOR_HIGHLIGHTING.md`.
- **Suggest / autocomplete.** Different index structures and latency
  envelope; should be its own service, not a query node.
- **Cross-collection joins** (and cross-collection search generally).
  `SearchRequest.collection` is singular by design; fan-out/fusion across
  collections belongs in a client or gateway until the engine has a
  cross-collection ranking story.
- **No stats engine, no event storage, no significance testing.** The
  experimentation surface ([§5](#5-experiments-and-ranking-profiles))
  deliberately stops at assignment + stamping. The engine does not store
  click/dwell/abandonment events, does not compute CTR or any derived
  metric, and does not decide winners — analysis happens in the analytics
  tool or offline on the `query_id` join ([§6](#6-analytics-event-contract)).
- Also consciously absent, without needing a design round: server-side
  embedding on the write path (v1 clients send vectors; the legacy
  text-embedding path stays on the legacy proto until an embedding service
  design exists), scripted scoring, dynamic mapping, and schema mutation.

---

## 9. Migration notes (legacy → v1alpha1)

Where the existing wire surface conflicts with v1 semantics — these need
migration planning, not mechanical renames:

1. **Hit identity.** Legacy `SearchHit.global_id` is an `int64` global
   ordinal that leaks index layout and changes across rebuilds. v1 `Hit.
   doc_id` is the client-assigned string id. The coordinator currently
   joins on `global_id`; it needs an ord→id resolution step (cf.
   `docs/ORD_TO_DOC_INTEGRATION.md`) before it can front the v1 surface.
2. **Search request shape.** Legacy `SearchRequest` is knn-only
   (`vector, k, collaborative` at the top level). In v1 those live inside
   `Query.knn`, so the shard service must grow a query-tree executor;
   `collaborative` moves from a request flag to a per-clause flag.
3. **Terminal frame.** Legacy streams end with a `SearchDebug` that is
   optional and unordered relative to hits. v1 mandates exactly-one-
   `Summary`-always-last plus `top_doc_ids` — the current shard loop emits
   hits and debug independently and has no final re-rank step.
4. **Coordinate RPC.** The coordinator↔shard floor-exchange
   (`Coordinate`) is an internal protocol and intentionally has no v1
   public equivalent; it must be split into an internal-only proto package
   rather than being versioned as public API.
5. **Collection creation.** Legacy `CreateCollectionRequest` has
   `vector_dimension`/`similarity`/`embedding_model` as scalars and no
   field schema. v1 requires a `CollectionSchema`; migrating existing
   collections means synthesizing a schema (one `dense_vector` field +
   `chunk` stored text + string keywords from `metadata`).
6. **Server-side embedding.** Legacy `IndexDocumentRequest.text` lets the
   server embed via DJL. v1 `BulkIndex` has no embedding path (see §8), so
   ingest pipelines that rely on it must embed client-side or stay on the
   legacy proto until an embedding-service design lands.
7. **Error reporting.** Legacy responses carry `bool success` +
   `string error`; v1 uses `google.rpc.Status`. Adapters must map, not
   forward.

## 10. Open questions (deferred)

- **Cursor + collaborative floors.** `search_after` resumption interacts
  with the collaborative floor: a resumed query could seed its floor from
  the cursor's kth-best. Deferred until the pagination implementation.
- **Per-`should`-clause budgets in `hybrid`.** v1 budgets are per-request
  plus per-`knn`-clause; whether hybrid sub-queries need independent time
  budgets is open.
- **Linear fusion normalization.** `LinearFusion` assumes min-max
  normalization; whether the normalizer should be selectable (z-score,
  none) is open.
- **Watch compaction window.** How long `WatchCollections.from_revision`
  history is retained (and thus when `OUT_OF_RANGE` fires) is a server
  policy still to be defined.
- **Replica read routing.** v1 exposes replica *placement* but no
  read-preference knob on `SearchRequest`; deferred until replication
  lands.
- **Vector element types.** `Vector` is float32-only; float16/int8/binary
  quantized vectors will need either new `Vector` variants or a
  bytes-with-dtype representation.
- **Experiment layering.** What happens when a global and a per-collection
  experiment both match a query is undefined in v1alpha1 (proposal: most
  specific wins, one experiment per query — never stacked). Multi-variate
  or mutually-exclusive experiment groups are out of scope.
- **RankingProfile reach.** Profiles currently switch fusion, budgets,
  collaboration, and analyzer. Whether they should also override
  `num_candidates` defaults or per-field boosts is open — each addition
  widens the surface that must stay deterministic across nodes.
- **Serving-log emission point.** In a coordinator-per-query topology the
  coordinator owns the Summary and should emit the serving-log record;
  who emits it for direct-to-shard debug queries is unresolved.
