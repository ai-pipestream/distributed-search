# knn-node

`knn-node` is the Quarkus runtime for Distributed Search. It hosts the current
indexing gRPC service, shard-local collaborative kNN service, development HTTP
coordinator, ScaleCube membership, and Lucene collection storage.

For capabilities, status, build prerequisites, and operating instructions, see
the [repository README](../README.md). For component boundaries, see
[Architecture](../docs/ARCHITECTURE.md).

## Development

The build requires explicit artifact directories from the companion Lucene
shared-floor branch. From this directory:

```shell
./gradlew test \
  -PluceneCoreJarDir=/path/to/lucene/lucene/core/build/libs \
  -PluceneSandboxJarDir=/path/to/lucene/lucene/sandbox/build/libs \
  -PluceneModuleJarDirs=/path/to/lucene/lucene/analysis/common/build/libs,/path/to/lucene/lucene/queryparser/build/libs
```

Run locally with the same properties and `quarkusDev`. HTTP and gRPC share port
`48100`; Quarkus health is available at `/q/health`.

Useful runtime configuration:

| Property | Purpose |
|---|---|
| `knn.index.path` | Legacy single-index path; use `NONE` for collections only |
| `knn.data.dir` | Persistent collection root |
| `knn.shard.id` | Local primary shard identity |
| `knn.single.node` | Disable peer fan-out and remote routing |
| `knn.scalecube.seeds` | Opt into cluster membership with `host:port` seeds |
| `knn.scalecube.port` | Gossip transport port |
| `knn.grpc.plaintext` | Development-only peer transport override |
| `knn.pure-mode` | Disable internal k scaling and visit limits for fair comparisons |
| `knn.external-index-path` | Read-only benchmark index |
| `knn.rerank.enabled` | Enable the coordinator rerank head (default false) |
| `knn.rerank.provider` | Rerank provider name, e.g. `tei`; unset picks by model |
| `knn.rerank.model` | Reranker model id passed to the provider |
| `djl-api/mp-rest/url` | Server-side embedding endpoint |

## Rerank head

Text searches (`GET /search/text`) can optionally rescore the merged shard
candidates with a cross-encoder before the final top-k truncation. The
retrieve stage still runs on kNN score; the reranker only reorders the
candidate pool the shards returned, so `perShardK` controls how deep that
pool is. The response carries `reranked: true` when the head was applied.

The head is off by default and configured with three keys:
`knn.rerank.enabled` (default `false`), `knn.rerank.provider` (provider name
such as `tei`; when unset the first provider supporting the model is used),
and `knn.rerank.model` (the reranker model id). Providers are discovered via
`ServiceLoader` from the `embeddings-spi` SPI; the TEI provider is on the
runtime classpath. Enabling rerank with no matching provider fails the query
with an `IllegalStateException` instead of silently skipping.

Rerank applies only when there is something to score. The raw-vector
`POST /search` endpoint has no query text, and external-index benchmarks
store no chunk text on hits, so both fall back to plain kNN score order.


Do not expose a multi-node deployment to an untrusted network while peer
plaintext mode is enabled.
