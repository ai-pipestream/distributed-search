# Product readiness

The project has a novel search algorithm and useful supporting code, but it is
not yet a complete service offering. This is the recommended order of work.

## P0: establish a trustworthy build and API

1. Publish reproducible Lucene core, sandbox, analysis-common, and queryparser
   artifacts from the shared-floor branch. Pin checksums and source revisions.
2. Wire the v1alpha1 query compiler and schema compiler into real gRPC service
   implementations, then run end-to-end tests through the network boundary.
3. Choose one supported ingestion model. The recommended target is ProtoMolt
   descriptors plus serialized protobuf messages.
4. Add CI for a clean checkout, unit tests, integration tests, container build,
   license checks, protobuf breaking-change checks, and dependency scanning.
5. Remove the checked-in development archive and publish release artifacts from
   CI instead.

## P0: protect data and traffic

1. Add TLS and mutual authentication for peer gRPC. Plaintext must be an
   explicit development profile, not the production default.
2. Add client authentication and collection-level authorization.
3. Replace membership broadcasts as the source of truth for collection and
   shard metadata with a durable, revisioned control plane.
4. Define commit, acknowledgement, and recovery semantics. The current periodic
   commit means a successful acknowledgement is not necessarily durable across
   process or host loss.
5. Implement replicas, primary election or leasing, catch-up, and promotion.

## P1: make it operable

1. Export Micrometer metrics for request rate, latency, errors, active streams,
   candidates visited, floor raises, pruned visits, per-shard skew, refresh lag,
   commit latency, index size, and channel state.
2. Add OpenTelemetry tracing across coordinator, shard search, embedding, and
   indexing calls with query IDs in structured logs.
3. Propagate deadlines and cancellation to every shard and bound coordinator
   fan-out, in-flight searches, indexing queues, and embedding concurrency.
4. Add readiness checks for Lucene readers, control-plane connectivity, schema
   availability, disk watermarks, and required peer security.
5. Provide backup, restore, corruption detection, and Lucene-version migration
   procedures.

## P1: prove the distributed behavior

1. Build a deterministic multi-node integration harness with network delay,
   disconnect, node loss, duplicate messages, and slow shards.
2. Measure recall and visited-node reduction against exact and standard HNSW
   baselines over public datasets.
3. Test duplicate global IDs, replicas, ties, NaN scores, late shard responses,
   and coordinator cancellation.
4. Publish benchmark methodology, hardware, Lucene revision, dataset digest,
   query set, warmup, confidence intervals, and raw output.

## P2: complete the product surface

1. Add bulk ingestion with idempotency keys, per-item status, retry guidance,
   and a dead-letter strategy.
2. Add aliases, schema revisions, reindex jobs, atomic cutover, and rollback.
3. Add collection quotas, retention, disk limits, admission control, and tenant
   isolation.
4. Support rolling upgrades with explicit wire and index compatibility tests.
5. Publish a CLI, runnable distribution, container images, Helm chart, examples,
   and an operator guide.

## Features to defer

HTTP/3 coordination, locality-sensitive sharding, topology sketches, vector
highlighting, and two-pass RAG indexing are promising research directions.
They should remain behind the reliability, security, and benchmark work above.
Adding more algorithms before the service boundary is dependable will make the
offering harder to validate and support.
