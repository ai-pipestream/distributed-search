# ProtoMolt integration

## Decision

Use ProtoMolt as the schema and protobuf runtime beneath Distributed Search.
Do not maintain two competing descriptor-option dialects or two independent
protobuf-to-Lucene mappers.

The projects have complementary responsibilities:

| ProtoMolt owns | Distributed Search owns |
|---|---|
| Descriptor loading and registry integration | Collection and shard lifecycle |
| Schema compatibility and validation | Lucene readers, writers, commits, and recovery |
| Mapping, CEL, metadata, and sensitivity policy | Query coordination and peer routing |
| Indexing hints and `IndexingPlan` | Shared-floor HNSW execution |
| `DynamicMessage` to Lucene `Document` projection | Global result merge and search telemetry |
| gRPC reflection and dynamic invocation tooling | Search-specific public APIs |

This boundary lets Distributed Search become a real ProtoMolt backend while
keeping the experimental Lucene fork out of ProtoMolt's general-purpose core.

## Target request boundary

An indexed record should identify all of the following:

- collection;
- document ID;
- protobuf message type;
- immutable schema coordinate or descriptor digest;
- serialized protobuf payload;
- optional precomputed embeddings keyed by field path or representation name.

At ingestion time the server resolves the descriptor through ProtoMolt,
validates the message, creates an `IndexingPlan`, and maps the `DynamicMessage`
with ProtoMolt's Lucene plugin. Distributed Search then routes and writes the
resulting Lucene document.

Collection metadata should pin a schema identity. Never resolve "latest" for
each write, because two shards could otherwise index the same payload under
different schemas during a registry update.

## Migration sequence

### 1. Add a narrow adapter module

Create a `protomolt-search-adapter` module in this repository first. Depend on:

- `protomolt-descriptors`;
- `protomolt-compat` and the required validation artifact;
- `protomolt-index-spi`;
- `protomolt-index-lucene`;
- one or more descriptor sources such as Apicurio, Confluent, Git, or Maven.

The adapter should expose a small service such as:

```java
interface SearchDocumentProjector {
    ProjectedDocument project(
        String schemaCoordinate,
        String messageType,
        byte[] payload);
}
```

`ProjectedDocument` should carry the Lucene document, stable document ID,
vector-field metadata, and descriptor digest used for the projection. Keep
Quarkus and gRPC types out of this interface so it is testable in isolation.

### 2. Replace the write-path converter

Route protobuf writes through the adapter instead of
`LuceneDocumentConverter`. Keep the existing simple vector request temporarily
as a compatibility facade that constructs the same internal projection.

Add contract tests proving that a ProtoMolt-projected document can be searched
through the existing Lucene reader and shared-floor collector.

### 3. Make the query compiler plan-aware

Translate ProtoMolt `IndexingPlan` field kinds, analyzers, vector dimensions,
and similarities into the query compiler's schema view. The query compiler
must reject fields that are stored but not searchable and must validate vector
dimensions before fan-out.

Do not make the query layer parse registry descriptors independently. One
cached collection schema snapshot should feed both indexing and querying.

### 4. Consolidate schema contracts

Deprecate `v1alpha1/schema_options.proto` after a compatibility adapter exists.
Use ProtoMolt indexing hints as the authoring standard. If a capability is
missing from ProtoMolt, add it to ProtoMolt's indexing SPI rather than forking a
search-only dialect.

Persist this collection tuple atomically:

```text
collection -> schema coordinate + descriptor digest + indexing-plan digest
```

Schema updates should run ProtoMolt compatibility checks plus a search-specific
index compatibility check. Changes that alter existing Lucene representation
must create a reindex plan rather than mutating the live collection silently.

### 5. Expose one public service

Implement the `ai.pipestream.search.v1alpha1` RPCs end to end or replace them
before freezing the API. Add server reflection so ProtoMolt can discover and
invoke the service dynamically. Remove the development `/search` contract from
the supported surface once equivalent RPCs exist.

### 6. Decide repository placement after the adapter proves stable

Do not physically merge repositories first. Prove the dependency direction and
API boundary with published artifacts and integration tests. Once stable, either:

- keep Distributed Search as a deployable product built on the ProtoMolt BOM; or
- move the adapter and server into ProtoMolt as optional `search/distributed-*`
  modules while leaving the shared-floor Lucene work independently versioned.

The first option has a smaller release blast radius. The second becomes
attractive only after the Lucene dependency is reproducible and the public API
has compatibility guarantees.

## Required integration tests

- Load a version-pinned descriptor from every supported descriptor source.
- Validate and map a `DynamicMessage` into a Lucene document.
- Index, refresh, query, delete, and restart without schema drift.
- Reject a payload whose descriptor digest differs from the collection pin.
- Classify additive, reindex-required, and rejected schema changes.
- Search text, vector, filtered vector, and hybrid queries against the same plan.
- Prove equivalent top-k results with collaborative search on and off.
- Prove a two-node rolling restart preserves collection metadata and availability.
