# Architecture

Distributed Search is currently one Quarkus application, `knn-node`, that can
act as a request coordinator and as a shard owner. HTTP and gRPC share port
`48100`.

## Runtime components

| Component | Responsibility |
|---|---|
| `KnnResource` | Development HTTP coordinator, peer fan-out, global top-k merge, floor broadcasts |
| `KnnNodeService` | Shard-local streamed kNN search and bidirectional coordination |
| `GlobalKnnFloor` and shared-floor collectors | Monotonic cross-shard pruning signal during HNSW traversal |
| `ScaleCubeClusterBootstrap` | Gossip membership and shard metadata advertisement |
| `ScaleCubeServiceDiscovery` | Resolves search peers through Stork |
| `GrpcChannelCache` | Reuses peer channels by host and port |
| `IndexNodeService` | Current document and collection gRPC API |
| `ShardRouter` | Deterministic document routing to the primary shard owner |
| `CollectionManager` | Local collection metadata, `IndexWriter`, NRT readers, commits, deletion |
| `SchemaCompiler` and `SchemaValidator` | Experimental descriptor-to-index-schema compilation and change classification |
| `QueryCompiler` and `HybridExecutor` | Typed query AST compilation and hybrid ranking fusion |

## Search flow

1. A coordinator receives a vector and selects shard peers.
2. Every shard starts a streaming Lucene kNN query.
3. Shards stream accepted candidates without waiting for the final local top-k.
4. The coordinator maintains a top-k floor over distinct global document IDs.
5. A raised floor is broadcast to active shard searches.
6. Floor-aware collectors use that lower bound to prune paths that cannot enter
   the cluster-wide result set.
7. Each shard drains any final top-k hits not emitted during traversal, then
   emits its visit count and timing.
8. The coordinator merges all hits and returns the global top-k.

The floor is a lower bound on the final cluster-wide cutoff. It is not a shard's
best score and must never decrease.

## Indexing flow

The current `ai.pipestream.index.v1.IndexService` accepts text, a vector, or
both. Text-only writes call the configured DJL embedding endpoint. The document
ID selects a shard with a stable hash; the receiving primary writes a Lucene
document and returns a per-document acknowledgement. Writers commit every five
seconds and readers refresh near-real-time.

The current cluster metadata is membership-based, not a replicated control
plane. Every node must have consistent collection configuration, and the code
does not yet provide consensus, durable placement history, replica promotion,
or resharding.

## API layers

There are two generations in the tree:

- The legacy gRPC APIs and `/search` HTTP resource are live.
- The `ai.pipestream.search.v1alpha1` proto package is a proposed public
  contract. Its query compiler and schema compiler exist, but the service
  implementations that connect them to the runtime do not.

Treating generated proto classes as a live service would overstate the current
offering. Graduation to `v1` should happen only after one end-to-end server
surface implements the contract and compatibility tests protect it.

## Deployment boundary

The current application is suitable for local experiments and controlled
benchmarks. A supported deployment still needs:

- published or reproducibly built Lucene shared-floor artifacts;
- TLS and authentication for public and peer traffic;
- a durable collection and shard control plane;
- replica recovery, relocation, and rolling-upgrade semantics;
- bounded timeouts, cancellation propagation, overload controls, and metrics;
- backup, restore, corruption handling, and index-version policy.
