# Distributed Search

Distributed Search is an experimental Lucene service for streamed, cross-shard
vector search. Its distinguishing feature is collaborative HNSW traversal:
shards exchange a monotonically increasing global score floor while a query is
running, allowing uncompetitive graph paths to stop earlier.

The repository also contains collection indexing, dynamic schema compilation,
a typed query compiler, and hybrid rank fusion. Those pieces are at different
levels of integration, so read [Current status](#current-status) before treating
an RFC as a live endpoint.

> **Status:** developer preview. The core algorithms and focused tests exist,
> but the project is not yet a supported production service. TLS, replication,
> failure recovery, a stable public API, pinned (non-snapshot) Lucene
> artifacts, and production observability remain release blockers.

## What is implemented

- Streaming Lucene kNN search over gRPC and HTTP
- Collaborative cross-shard score-floor propagation
- Single-node and ScaleCube-discovered multi-node execution
- Collection creation, document indexing, deletion, and persisted local shards
- Server-side embeddings through a configurable DJL endpoint
- Optional cross-encoder rerank head on the coordinator (see
  [knn-node](knn-node/README.md#rerank-head))
- Plain-Java embedding and rerank provider SPI with TEI, OpenVINO/KServe, and
  model2vec providers, plus a cross-provider equivalence harness
  ([embeddings](embeddings/README.md))
- Schema-as-proto compilation and compatibility classification
- Typed query AST compilation for text, range, Boolean, vector, and hybrid queries
- Reciprocal-rank and weighted-linear fusion
- Health endpoints and JVM container packaging

## Current status

| Surface | Status | Notes |
|---|---|---|
| `knn.collab.KnnNodeService` | Implemented | Internal shard search and bidirectional floor coordination |
| `ai.pipestream.index.v1.IndexService` | Implemented | Current collection and indexing API |
| `/search` HTTP resource | Implemented | Development and benchmark coordinator surface |
| Schema compiler and validator | Implemented library | Tested, but the v1alpha1 admin RPC is not wired |
| Typed query compiler and hybrid fusion | Implemented library | Tested, but the v1alpha1 search RPC is not wired |
| `ai.pipestream.search.v1alpha1` services | Contract only | Proto and RFC design, not a live server surface |
| Research documents | Proposed | Ideas, not shipping behavior unless identified above |

See [Architecture](docs/ARCHITECTURE.md), [Product readiness](docs/PRODUCT_READINESS.md),
and the [documentation index](docs/README.md) for the full breakdown.

## Build

A fresh clone builds with no local setup: the shared-floor Lucene fork
(`ai.pipestream:lucene-*:11.0.0-experimental-SNAPSHOT`) and the OpenNLP
preview modules (`ai.pipestream:opennlp-*:3.x-preview-SNAPSHOT`) resolve as
module dependencies from Central's snapshot repository, with the
ai.pipestream registry as a fallback and `mavenLocal` first for developer
overrides. `knn-node` needs JDK 25; the embeddings modules target 21.

```shell
cd knn-node
./gradlew compileJava
```

CI runs exactly this plus the plain-JUnit suites on every push and pull
request ([ci.yml](.github/workflows/ci.yml)). The Quarkus-boot test suites
are deliberately excluded; run them locally when the machine is free.

To build against a local Lucene checkout instead (Lucene development), build
the four modules and point the build at their artifact directories. The
overrides are all-or-nothing: set all three, or none to use the snapshots.

```shell
cd /path/to/lucene
./gradlew :lucene:core:jar :lucene:sandbox:jar \
  :lucene:analysis:common:jar :lucene:queryparser:jar

cd /path/to/distributed-search/knn-node
./gradlew test \
  -PluceneCoreJarDir=/path/to/lucene/lucene/core/build/libs \
  -PluceneSandboxJarDir=/path/to/lucene/lucene/sandbox/build/libs \
  -PluceneModuleJarDirs=/path/to/lucene/lucene/analysis/common/build/libs,/path/to/lucene/lucene/queryparser/build/libs
```

The same values can be supplied as `LUCENE_CORE_JAR_DIR`,
`LUCENE_SANDBOX_JAR_DIR`, and comma-separated `LUCENE_MODULE_JAR_DIRS`
environment variables.

When all modules come from one checkout, the repository helper accepts its
root directly:

```shell
LUCENE_ROOT=/path/to/lucene ./run-tests.sh
```

## Run one node

```shell
cd knn-node
./gradlew quarkusDev
```

(For a local Lucene build, pass the same `-P` properties or `LUCENE_*`
environment variables described under [Build](#build).)

HTTP and gRPC share port `48100`. Useful development endpoints are:

- `GET http://localhost:48100/q/health`
- `POST http://localhost:48100/search?k=10&collaborative=true`
- `GET http://localhost:48100/search/smoke?k=10`

The smoke endpoint assumes a compatible 128-dimensional legacy index. For
collections-only operation, set `KNN_INDEX_PATH=NONE`.

After `./gradlew build`, `docker compose up --build` starts a persistent,
single-node collections-only service from the generated Quarkus application.

## ProtoMolt direction

Distributed Search should become a ProtoMolt-backed search runtime, not a
second schema platform. ProtoMolt should own descriptor loading, validation,
mapping, compatibility, and protobuf-to-Lucene document projection. This
project should own shard placement, Lucene lifecycle, distributed execution,
and collaborative search.

The concrete boundary and migration order are documented in
[ProtoMolt integration](docs/PROTOMOLT_INTEGRATION.md).

## License

[Apache License 2.0](LICENSE)
