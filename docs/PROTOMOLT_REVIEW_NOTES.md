# ProtoMolt review notes (2026-07-18)

Review of `/work/main/dev-tools/protomolt` (github.com/ai-pipestream/protomolt),
motivated by folding Distributed Search into it for the index-SPI plugin model
and the MCP server. Complements `PROTOMOLT_INTEGRATION.md` (the earlier
decision doc). Sources: repo docs + code read directly; findings verified by
three focused code reviews (index SPI, MCP/actions, build/release).

## 1. What ProtoMolt is today

- 65 Gradle modules (`settings.gradle:15-192`), group `ai.pipestream`, one
  version for everything (axion-release from `v*` tags), published to Maven
  Central in a single aggregated deployment (`build.gradle:30-37`).
- Runtime-protobuf toolkit: descriptor registry + loaders, validation,
  mapping/CEL, indexing projection, JSON/REST + OpenAPI, gRPC reflection +
  dynamic invocation, action catalog, MCP server, Kafka Connect, chains,
  WASM codegen, Vue console.
- **Age: 8 days old at review time** (initial commit 2026-07-10, 167 commits,
  single author). Pre-first-release: no `v*` tags. Conventions are strong but
  still moving (a `cli` module addition was mid-flight in the working tree).

## 2. Engineering gates (what any folded-in module must pass)

- buf lint on all protos; buf breaking on PRs (`ci.yml:43-45`).
- BOM completeness: `checkBomCompleteness` and `checkConsumerCatalog` fail
  `check` if a published module escapes the BOM/catalog
  (`bom/build.gradle:108-145`). Escape hatch exists:
  `deliberatelyUnconstrained` with a reason (precedent:
  `protomolt-protobuf-validation-conformance`).
- Protovalidate conformance held at 100% in a dedicated CI job.
- Javadoc jar mandatory per published module; doclint `all,-missing`.
- Integration tests (`@Tag("integration")`) skip on probe failure locally but
  CI **fails if any of them skipped** (`ci.yml:103-159`).
- No spotless/errorprone/japicmp/coverage gates (binary-compat checking
  explicitly deferred until first release).
- Dependency discipline: single `gradle/libs.versions.toml`; subprojects
  resolve from `mavenCentral()` only; **zero** flatDir/files()/mavenLocal
  precedent anywhere.

## 3. Index SPI — how Lucene/OpenSearch/Solr plug in

Contract (`index/spi`): a backend is a **document mapper**, ServiceLoader-
discovered. `SearchEngineIndexer` = `engineId()` + `map(Message, IndexingPlan)`
(`index/spi/.../SearchEngineIndexer.java:11-17`), plus a
`SearchEngineIndexerProvider` and the one-line `META-INF/services` file.
`IndexerContext` carries **only** a `ProtoFieldMapper`.

- Hints are protobuf FieldOptions (incl. `vector_dims`, `vector_similarity`,
  `vector_element_type`, `hnsw{m, ef_construction}`) baked into descriptors;
  `IndexingPlanFactory` walks descriptors with a catalog → proto-options →
  inference chain. Ships as `protomolt-index-spi`.
- `index-lucene`: `ProtoLuceneMapper` maps DynamicMessage → Lucene `Document`
  (emits `KnnFloatVectorField`/`KnnByteVectorField` with the hinted similarity,
  `:549-568`). Analyzers are *not* instantiated — names ride into
  `LuceneFieldSpecs`, host wires `PerFieldAnalyzerWrapper`.
- `index-opensearch`/`index-solr`: **no client/transport** — same pattern:
  document mapper (`Map<String,Object>`) + schema generator from the plan.
- Lucene dep: official `org.apache.lucene:lucene-core:10.5.0`, `api`-scoped
  (`index/lucene/build.gradle:6`; `libs.versions.toml:16,62`).

What a `distributed-lucene` plugin gets free: the whole hint/plan machinery,
DynamicMessage field extraction, ServiceLoader discovery, and — because the
fork is API-compatible with Lucene main — **verbatim reuse of
`ProtoLuceneMapper`/`LuceneFieldSpecs`** (mapper imports only stable
`o.a.l.document/index/util` APIs, unchanged 9→11).

Friction for a sharded backend (all real, none fatal):

- The SPI is **mapping-only**: no lifecycle (create/open/commit/refresh),
  no delete contract, **no query SPI at all**. Sharding, coordination, and
  merge have no seam to plug into — that would be new API, not an
  implementation of the existing one.
- `IndexerContext` has no config channel (no endpoints/topology/directories).
- Engine-id collision is silent last-wins (`SearchEngineIndexers.java:18-19`).
- `render-index-mappings` action hardcodes a switch over
  opensearch/solr/lucene (`RenderIndexMappingsAction.java:68-83`) — new
  engines need an edit to appear there.
- `LuceneFieldSpecs` drops `hnswParams` (`:83-96`) — read them off
  `ResolvedFieldHint` directly.
- `createAll()` instantiates every provider on the classpath — keep engine
  construction lazy.

## 4. MCP / actions — what "fold in and get an MCP server" actually means

- Action = plain interface impl (`name()`, `description()`, JSON-Schema
  `inputSchema()`, `execute(ObjectNode)`), registered **programmatically**
  into `ActionCatalog` — no annotations, no ServiceLoader. Choke point:
  `ProtoMoltCatalog.full(context, ...)` (`grpc/service/...:43-53`).
- **MCP exposure is automatic for anything registered** (`McpServer.java:
  150-178`: `tools/list` → `catalog.list()`, `tools/call` → `execute`).
  One `.register(new SearchAction())` line = a tool on stdio and `/mcp`.
- **REST/gRPC/OpenAPI are NOT automatic**: the REST mount iterates a
  hand-written `protomolt_service.proto` (one rpc + messages per verb);
  a new verb exists on MCP only until that proto is extended.
- Console: Vue 3/Vuetify SPA with **hardcoded views**; a search frontend =
  new views + new REST RPCs. Framework/serving pattern reusable; nothing
  generated. Console is disabled when `--api-token` is set.
- Limits: request/response only (no streaming/progress/cancellation);
  `grpc-invoke` covers unary + server-streaming only (rejects
  client-streaming — so `streamIndex` doesn't fit), default 15s deadline /
  64 streamed responses; 16 MiB payload cap; no per-tool authz (one shared
  `api_token`); no `readOnlyHint`/`destructiveHint` tool annotations.
- Long-running work (reindex) needs the job-id pattern: start-action +
  poll-action, job machinery is ours to build.
- **Zero-code path that exists today**: `reflect` + `grpc-invoke` make any
  reflection-enabled gRPC service MCP-operable with no registration. Enabling
  gRPC server reflection on knn-node gives agents Search/Coordinate/
  IndexService immediately (subject to the unary/streaming limits).

## 5. The Lucene-fork problem (the hard constraint on physically folding in)

ProtoMolt auto-publishes every included module to Central and gates the BOM;
our engine depends on a custom Lucene fork built from a worktree
(`sandbox/shared-floor-knn`, 11.0.0-SNAPSHOT). Collision course:

- A fork-dependent module would publish a POM whose dependency doesn't
  resolve on Central — or must be publish-excluded AND BOM-exempted
  (`deliberatelyUnconstrained` entry).
- Central-only resolution has no precedent for a non-Central dep; a
  fork-bootstrap step would be needed in **all five** CI workflows; the
  single aggregated nmcp deployment means a fork-resolution failure blocks
  the release train for all 61+ artifacts.
- Coordinate collision: fork reuses `org.apache.lucene` coordinates while
  `protomolt-index-lucene` pins `lucene-core:10.5.0` (`api`-scoped) — one
  classpath cannot carry both lineages.
- Single version train: an experimental module would version/release in
  lockstep with the mature core and appear in release notes from day one.

Options:
a. Publish the fork under a distinct groupId (e.g. `ai.pipestream.lucene`)
   from the fork repo — cleanest, preserves every gate.
b. Publish/BOM exclusion + CI bootstrap hacks — works, erodes the
   Central-only reproducibility guarantee; every entry is a reviewed
   exception.
c. Don't fold the engine: keep Distributed Search as a product consuming
   the ProtoMolt BOM (= PROTOMOLT_INTEGRATION.md §6 option 1).

## 6. The elegant split (review conclusion)

The fork dependency exists **only in the engine node** (search/index serving
over Lucene). The MCP-facing piece — search/index/status *actions* that call
the engine's gRPC — depends only on ProtoMolt + the search proto stubs, not
on the fork. So:

- **Fold in**: the action module (ProtoAction impls + one `register()` line)
  and, later, the `index-distributed-lucene` *mapper* (2 classes + services
  file, reusing `ProtoLuceneMapper`). Both publish cleanly.
- **Keep out (for now)**: the engine node itself (fork-dependent,
  ScaleCube-clustered, Quarkus), consumed as a deployable product.
- **Connector**: gRPC + server reflection (also the zero-code MCP path).

Physical merge of the engine becomes attractive only when the Lucene
dependency is reproducible from Central — i.e. when the shared-floor classes
land in an official Lucene release (the apache/lucene#16357 path), or the
fork is published under a distinct groupId as a deliberate internal artifact.
