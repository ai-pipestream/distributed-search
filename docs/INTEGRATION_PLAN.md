# Integration plan — ProtoMolt, embeddings, distributed search

**Status:** v1 · **Date:** 2026-07-18
**North star:** output of one gRPC service flows into a distributed-search
node and is searched successfully using the shared-floor Lucene jars.

This plan consolidates the decisions made during the 2026-07-18 review of
ProtoMolt (`/work/main/dev-tools/protomolt`), pipestream-embedder, and the
shared-floor Lucene work. Companion documents:
`PROTOMOLT_INTEGRATION.md` (earlier decision doc) and
`PROTOMOLT_REVIEW_NOTES.md` (code-level review findings with file references).

## 1. Fold-in split (ProtoMolt)

Distributed Search becomes a ProtoMolt backend in two pieces, on different
timelines:

- **Folds in (when SNAPSHOTs are resolvable):** the search action module
  (ProtoAction implementations + one `register()` line in
  `ProtoMoltCatalog.full(...)`) and the `index-distributed-lucene` mapper
  (two classes + services file, reusing `ProtoLuceneMapper` verbatim — the
  fork is API-compatible with Lucene main). Neither depends on the Lucene
  fork; both publish cleanly.
- **Stays out (for now):** the engine node (ScaleCube-clustered,
  Quarkus-hosted, Lucene-fork-dependent). Consumed as a deployable product.
- **Connector:** gRPC + server reflection. Enabling reflection on knn-node
  makes every search RPC MCP-operable today via ProtoMolt's `reflect` +
  `grpc-invoke` verbs, zero code.

## 2. SNAPSHOT strategy

- **Distinct groupIds, always.** Fork artifacts publish as
  `ai.pipestream:lucene-*` and `ai.pipestream:opennlp-*` — never
  `org.apache.*` coordinates, so fork and official lineages coexist on one
  classpath and one repository without shadowing. ProtoMolt pins official
  `lucene-core:10.5.0`; that stays untouched until upstream ships the feature.
- **Fork branch model** (same as the OpenNLP uber fork): `main` mirrors the
  Apache project exactly; the default integration branch carries main +
  feature work; artifacts build only from the integration branch, with
  `README-PREVIEW.md` + `PIPESTREAM-PROVENANCE.txt` recording exactly which
  refs each build contains. Not an ASF release; report issues to ai-pipestream,
  never upstream.
- **Now (done):** local `publishToMavenLocal` only.
  Lucene: branch `kristian-11.x-features` in the fork worktree carries the
  attribution commit; `ai.pipestream:lucene-core` / `lucene-sandbox`
  `11.0.0-experimental-SNAPSHOT` are in `~/.m2` (jars + sources + javadoc;
  unsigned `jars` publication — the `signedJars` one needs a GPG signatory).
  OpenNLP: `ai.pipestream:opennlp-{embeddings,api,runtime,subword,cli}`
  `3.x-experimental-SNAPSHOT` installed from the uber worktree's built jars
  with generated stub POMs — **consumers must declare every jar explicitly;
  the fork's real publishing setup (dl-deploy branch) replaces this
  bootstrap.**
- **Later:** SNAPSHOT deploys to the ai.pipestream snapshot repository (the
  `central.sonatype.com/repository/maven-snapshots` pattern already used by
  pipestream-embedder), then nmcp release flow. ProtoMolt's Central-only
  policy is revisited at that point, not before.

## 3. Two-lane embedding policy

The equivalence harness is a **certification gate**, not a benchmark:

- **Accurate lane:** the same model served by two runtimes, certified
  equivalent by the harness — min pairwise cosine ≥ 0.999 on the probe set
  *and* mean top-5 retrieval overlap ≥ 0.99 (retrieval equivalence is the
  property that matters; cosine alone misses tokenizer/padding drift).
  Certified pairs may be mixed by the router and split across batch
  pipelines.
- **Fast lane:** model2vec — self-consistent, pinned to its own provider,
  its own collections, never mixed. It fails cross-model certification by
  design and is included in the harness as the **negative control** (kept in
  CI: if model2vec-vs-BGE ever passes, the gate is broken).
- The certified-equivalence registry (model id → certified provider set)
  lives next to the collection pin; the router round-robins only within the
  certified set, everything else stays pinned. Mostly one provider per model
  in practice; mixing pays in batch pipelines.

## 4. Collection model-identity pin

Collection metadata pins, atomically:

```text
collection -> schema coordinate + descriptor digest + indexing-plan digest
              + embedding model id + vector dims
```

Querying a model2vec-indexed collection with BGE vectors returns confident
garbage; the pin is what makes lanes enforceable rather than conventional.
Reject mismatched model/dims at both index and query time. Never resolve
"latest" per write (two shards could index under different schemas during a
registry update).

## 5. Provider map

| Box | Hardware | Provider | Role |
|---|---|---|---|
| GPU node | NVIDIA | TEI (`/embed`, `/rerank`) | quality embeddings + cross-encoder rerank for the high-K head |
| CPU node | Intel | OpenVINO/OVMS (KServe v2 gRPC) | CPU-optimized quality lane |
| Pi nodes / any CPU | ARM/x86 | model2vec in-process (OpenNLP static embeddings; default **potion-retrieval-32M**, 512-dim — BEIR SciFact 0.795 vs teacher 0.808) | fast lane default, no GPU, no network hop |

Per-platform defaults come from harness measurements, not guesses. TEI is
**not** KServe — it is its own REST/gRPC API; a TEI provider is a new small
client, similar in effort to the DJL one. The router prefers the fastest
*ready certified* endpoint per model rather than hard pinning.

## 6. Embedding SPI

Plain Java, `java.util.ServiceLoader`, blocking JDK-types interface
(`EmbeddingProvider`: `name/supports/dims/embed(model, texts)`), in the
`embeddings/` module group of this repo: `embeddings-spi`,
`model2vec-provider`, `equivalence-harness`. Deliberately duplicates the
six-method `EmbeddingBackend` contract from pipestream-embedder
(Quarkus-shaped there) — consolidate into one plain-Java core later if the
engine graduates; the interface is small enough that consolidation is cheap.

## 7. ProtoMolt completeness (what the integration needs vs what exists)

- **Index SPI is mapping-only** — no engine lifecycle (create/commit/
  refresh), no delete contract, no query SPI. The heavy integration this plan
  wants ("heavier integration points") means designing a generic engine
  lifecycle + query SPI in ProtoMolt with distributed-search as first
  implementation. New API design, not plumbing. Rule: core never imports
  search-specific concepts.
- **Reusable as-is:** `ProtoLuceneMapper`/`LuceneFieldSpecs` (read
  `hnswParams` off `ResolvedFieldHint` — `LuceneFieldSpecs` drops them);
  hint/plan machinery incl. vector dims/similarity/hnsw options.
- **Actions/MCP:** registration is programmatic (one line in
  `ProtoMoltCatalog.full`); MCP exposure is then automatic. REST/gRPC/
  OpenAPI are NOT automatic — verbs need rpc+messages in
  `protomolt_service.proto`. Console views are hand-written Vue.
- **Limits that shape the verbs:** request/response only (no streaming/
  progress/cancel — long jobs need the job-id + poll pattern); `grpc-invoke`
  unary + server-streaming only, 15s/64-response defaults, 16MiB cap;
  client-streaming rejected (so `streamIndex` needs batch envelopes);
  one shared `api_token`, no per-tool authz — mutating orchestration verbs
  go behind a separate token before any LLM drives the cluster.
- **Repo maturity:** ProtoMolt is days old, pre-first-release, single
  release train, auto-publishes everything with BOM gates — anything folded
  in must publish cleanly from day one (another reason the engine stays out
  until snapshots resolve).
- `render-index-mappings` hardcodes its engine switch — new engines need an
  edit there, not just a ServiceLoader entry.

## 8. Staged sequence

1. ✅ Reviews + decision docs (this file, `PROTOMOLT_REVIEW_NOTES.md`).
2. ✅ Fork SNAPSHOT setup (§2) — local m2.
3. ✅ Tier2Bench hardening (audit fixes: gateK default, GT-depth check,
   merge tie-break, arm-E publish comment).
4. ✅ perShardK + globalShare derivation from cluster size (replaces manual
   REST param; formula `SharedFloorKnnCollectorManager.perShardGate`).
5. ✅ Embedding SPI + model2vec provider + equivalence harness (§6, §3).
6. ⬜ gRPC floor-wiring fixes (deferred: local shard blind; reply-only
   shard→coordinator flow) — prerequisite for honest distributed numbers.
7. ⬜ D0–D3 distributed runs with the fixed mechanism (the headline table).
8. ✔ KServe + TEI providers done (live-verified). **First accurate-lane pair
   CERTIFIED: all-MiniLM-L6-v2 on OVMS vs the same model on TEI —
   minCosine = meanCosine = 1.000000, mean top-5 overlap = 1.000, PASS.**
   Negative controls hold: model2vec static vs served transformer FAILs on
   dimension mismatch (256/512 vs 384). Endpoint config via env
   (`KSERVE_TEST_ENDPOINT`, `TEI_TEST_ENDPOINT`); live tests skip cleanly
   when endpoints are down.
9. ⬜ Server reflection on knn-node → MCP-operable search (zero code);
   then `protomolt-search-actions` module (search, index-doc,
   create-collection, cluster-status, compare-engines).
10. ⬜ Collection pin enforcement (§4) + NRT/refresh semantics (the
    "realtime" in near-realtime).
11. ⬜ Re-embed benchmark subset with model2vec; new ground truth; rerun
    k-knee + skew tables before any quality claim on the new stack.
12. ⬜ Console search views; query-compiler plan-awareness
    (PROTOMOLT_INTEGRATION.md §3); rolling-restart tests.

## 9. Remaining to the end-to-end demo

The demo, restated: an external caller reflects on a distributed-search
node's gRPC service, streams documents in, and gets search hits back — the
search executed through the shared-floor Lucene jars
(`ai.pipestream:lucene-sandbox:11.0.0-experimental-SNAPSHOT`). In dependency
order:

1. **Floor wiring fixes** (deferred item B): the coordinating node's local
   shard must receive the floor (today it searches blind), and the
   shard→coordinator floor flow must not be reply-only, or the distributed
   numbers will misrepresent the mechanism.
2. **Write path:** `IndexNodeService` consumes `protomolt-index-lucene`'s
   `ProtoLuceneMapper` in place of `LuceneDocumentConverter`
   (`protomolt-index-lucene` is already m2-resident); the §4 collection pin
   is enforced at collection-create, index, and query time.
3. **NRT/refresh semantics:** a doc indexed via gRPC must be searchable
   within a defined window (refresh policy per collection; today visibility
   is undefined). This is the literal meaning of "near-realtime".
4. **Embedding wiring:** the model2vec provider (fast lane) callable from
   the write and query paths for collections pinned to it; OVMS + TEI
   providers added, with the first harness-certified accurate-lane pair
   recorded in the equivalence registry.
5. **Server reflection** enabled on knn-node's gRPC server — the one-line
   change that makes the node dynamically invocable, so the demo's caller
   needs no stubs (this is also the zero-code MCP path).
6. **Demo script:** register schema → create pinned collection → batch-
   envelope docs via the index RPC → search at k=1000+ → assert hits and
   print per-shard visits from `SearchDebug`. Two nodes suffice.

