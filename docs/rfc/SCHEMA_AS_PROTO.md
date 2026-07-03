# RFC Annex: Schema-as-Proto

- **Status:** Draft (annex to [SEARCH_API_V1.md](SEARCH_API_V1.md))
- **Proto files:** `knn-node/src/main/proto/v1alpha1/schema_options.proto`,
  additions to `admin_service.proto` (`RegisterSchema`, `ValidateSchema`)
  and `index_service.proto` (`IndexDocument.typed_document`)

Users author their document schema as an ordinary `.proto` file, annotate
fields with our custom options, and register the compiled
`FileDescriptorSet`. The server compiles the annotated descriptor into the
collection's `CollectionSchema` (the typed schema from
[SEARCH_API_V1 §1.4](SEARCH_API_V1.md#14-collection-admin-with-typed-schemas)).
The proto file becomes the single source of truth for both the index
mapping and the application's document types.

---

## 1. The two-layer model

There are exactly two layers, and they do not blur:

**Layer 1 — authoring + codegen artifact.** The user's annotated `.proto`
is (a) the schema definition the server compiles into `CollectionSchema`,
and (b) ordinary protobuf source the user runs through `protoc` to get
typed document classes in their language. One file, two outputs, no drift
between "what the app builds" and "what the index expects".

**Layer 2 — the ingest wire format, unchanged.** The flattened
`DocumentField` list remains *the* wire format for `BulkIndex`. Registering
a proto schema does not change what travels on the wire; it changes what
the server will accept and how it maps names/types to Lucene. Generated
**converters** (emitted by our codegen alongside the message classes)
flatten a typed document into `repeated DocumentField` on the client:

```
Product p = ...;                       // user's generated class
List<DocumentField> fields = ProductConverter.flatten(p);  // generated
// -> BulkIndex IndexDocument{fields}
```

Flattening rules: leaf fields keep their proto name; `FLATTEN` message
fields contribute dotted paths (`vendor.name`); fan-out representations
are addressed as `<field>#<rep>` (`title#raw`, `title#vec`);
`BLOCK_JOIN` fields serialize children as separate child-document groups.

**DynamicMessage is a convenience path only.** `IndexDocument.
typed_document` (a `google.protobuf.Any`) lets a client send the root
message directly; the server unpacks it reflectively via `DynamicMessage`
against the registered descriptor set and flattens server-side.

> **Perf caveat (documented, deliberate):** the `Any` path costs an extra
> serialization, a descriptor-pool lookup, and reflective per-field access
> — expect several times the CPU of pre-flattened `fields` and zero reuse
> of generated code. It exists for scripting, debugging, and low-volume
> writers. Bulk loads must use generated converters and `fields`.

The server itself never needs generated classes for user schemas: schema
compilation walks descriptors, and the `Any` path uses `DynamicMessage`.
Only clients enjoy codegen.

## 2. Authoring surface

See `schema_options.proto` for the full reference and a worked example.
Summary:

- Field option `(ai.pipestream.search.v1alpha1.field)`:
  `type` (KEYWORD | TEXT | LONG | DOUBLE | DATE | BOOL | VECTOR |
  STORED_ONLY), `analyzer` + optional `search_analyzer` (registry names:
  built-ins or pluggable), `stored`, `doc_values`, `index_options`
  granularity (docs/freqs/positions/offsets), `vector {dims, similarity,
  hnsw {max_conn, beam_width}}`, `nested` semantics for message fields
  (FLATTEN | BLOCK_JOIN — **no default**), and repeated `representations`
  fan-out blocks.
- Message option `(ai.pipestream.search.v1alpha1.collection_defaults)`:
  `default_analyzer`, `dynamic_fields` policy (**STRICT only in v1**:
  unknown `DocumentField` names at ingest are rejected).
- Fields with no option are neither indexed nor stored.
- HNSW params use Lucene vocabulary and map to `CollectionSchema`'s
  `HnswParams` as `max_conn -> m`, `beam_width -> ef_construction`.

Users produce the registration payload with:

```
protoc -I . -I <vendored pipestream protos> \
  --descriptor_set_out=schema.fdset --include_imports product.proto
```

and call `RegisterSchema{collection, source: {descriptor_set, root_message:
"acme.catalog.Product"}}`. `ValidateSchema` is the identical dry-run.

## 3. Type mapping

| Proto source                       | Allowed `type`                    | Notes |
|------------------------------------|-----------------------------------|-------|
| `optional string`                 | KEYWORD, TEXT, STORED_ONLY        | **String is ambiguous by design** — there is no default; you must pick KEYWORD or TEXT (or both, via a representation). |
| `optional int32/64, uint, sint, fixed` | LONG, DATE (epoch millis), STORED_ONLY | |
| `optional float/double`           | DOUBLE, STORED_ONLY               | |
| `optional bool`                   | BOOL, STORED_ONLY                 | |
| `google.protobuf.Timestamp`       | DATE, STORED_ONLY                 | message type: has presence, no `optional` needed |
| enum                               | KEYWORD (value name), STORED_ONLY | |
| `repeated float`                  | VECTOR (requires `vector{dims,...}`) | length must equal `dims` at ingest |
| `repeated <scalar>`               | as scalar (multi-valued)          | empty list = absent; exempt from the presence rule |
| `optional bytes`                  | STORED_ONLY only                  | no indexable bytes type in v1 |
| message field                      | — (`nested` instead of `type`)    | `nested` is mandatory: FLATTEN or BLOCK_JOIN, **no default** |
| `repeated` message                 | — (`nested` mandatory)            | BLOCK_JOIN preserves per-element correlation; FLATTEN does not |
| `map<K,V>`                        | **REJECTED in v1**                | no stable field naming / no per-key schema; model as a repeated message with BLOCK_JOIN, or wait for a dedicated flattened-map type |
| `oneof` members                    | as their type                     | members have presence; at most one is indexed per document |
| `google.protobuf.Any` (as a field) | **REJECTED**                      | unschematizable |

**The presence rule (loud, on purpose).** An indexable proto3 scalar
**must** be declared `optional`. Implicit-presence scalars cannot
distinguish "absent" from `0` / `""` / `false`, so every unset field would
silently index a zero value — poisoning range queries, term statistics,
and sort orders. `RegisterSchema`/`ValidateSchema` classify such fields
`REJECTED` with code `IMPLICIT_PRESENCE`. `repeated` fields and message
types carry usable presence and are exempt. This rule exists because it is
exactly the class of bug users never notice until production.

**Fan-out.** One source field may declare `representations` — extra
indexed forms named `<field>#<rep>` (e.g. `title` as TEXT plus `title#raw`
KEYWORD plus `title#vec` VECTOR). Representations derivable from the
source value (string→KEYWORD, granularity variants) are produced by the
converter/server automatically. Representations that are *not* derivable —
above all VECTOR from a string source, since v1 has no server-side
embedding — must be supplied by the client as an explicit
`<field>#<rep>` `DocumentField`; the generated converter exposes a typed
setter for each such representation. Missing non-derivable representation
values index the document without that representation (it simply won't
match vector queries).

**Vector constraints.** `dims` is required, must be positive, and every
ingested vector must match exactly. `similarity` defaults to COSINE.
`max_conn`/`beam_width` of 0 mean server defaults. One VECTOR type per
field or representation; no multi-vector fields in v1.

## 4. The evolution referee

Schema evolution answers to **two different rulebooks**, and people
habitually confuse them:

- **Proto wire compatibility** cares about *tags and types on the wire*:
  renames are free (names don't travel), adding fields is free, reusing a
  tag is catastrophic.
- **Lucene index compatibility** cares about *field names and index-time
  decisions*: names are identity (a rename orphans every existing
  posting), and analyzers, types, doc_values, index options, and nested
  layout are baked into segments at write time.

The referee applies both rulebooks to every diff between the registered
descriptor and the submission; **the stricter verdict wins**. That is why
a field rename — the canonical "safe" proto change — is `REQUIRES_REINDEX`
here, and why a search-analyzer change — invisible to protobuf entirely —
is the rare index-side change that is live-safe.

### Classification matrix

| Change | Proto wire verdict | Lucene verdict | Classification |
|---|---|---|---|
| Add field (new tag, `optional`/repeated/message) | safe | new field, old docs simply lack it | **WIRE_SAFE_LIVE** (`NEW_FIELD`) |
| Add representation to existing field | n/a (option-only) | new Lucene field; only new docs carry it | **WIRE_SAFE_LIVE** (`NEW_REPRESENTATION`; reindex optional for backfill) |
| Remove field / representation (tag `reserved`) | safe | stale postings linger until merge; queries on it rejected | **WIRE_SAFE_LIVE** (`FIELD_REMOVED`) |
| Change `search_analyzer` only | invisible | query-time only, segments untouched | **WIRE_SAFE_LIVE** (`SEARCH_ANALYZER_CHANGED`) |
| Change HNSW `max_conn`/`beam_width` | invisible | construction params; apply to new segments | **WIRE_SAFE_LIVE** (`HNSW_PARAMS_CHANGED`) |
| Change `stored` flag | invisible | stored values exist only for docs written after the change | **REQUIRES_REINDEX** (`STORED_CHANGED`) |
| Change `doc_values` flag | invisible | Lucene forbids mixed per-field docvalues across segments | **REQUIRES_REINDEX** (`DOC_VALUES_CHANGED`) |
| Change `index_options` granularity (either direction) | invisible | per-field IndexOptions fixed across segments | **REQUIRES_REINDEX** (`INDEX_OPTIONS_CHANGED`) |
| Rename field (same tag) | **safe** | **field identity lost** — old docs invisible under new name | **REQUIRES_REINDEX** (`FIELD_RENAMED`) |
| Change field type / `type` option (same tag) | usually breaking | postings/points/docvalues formats incompatible | **REQUIRES_REINDEX** (`TYPE_CHANGED`) |
| Change index-time `analyzer` | invisible | old tokens produced by old analyzer; term dictionaries diverge | **REQUIRES_REINDEX** (`ANALYZER_CHANGED`) |
| Change `nested` FLATTEN ↔ BLOCK_JOIN | invisible | document layout (block structure) differs per segment | **REQUIRES_REINDEX** (`NESTED_SEMANTICS_CHANGED`) |
| Change vector `dims` / `similarity` | invisible | graphs + stored vectors incompatible | **REQUIRES_REINDEX** (`VECTOR_PARAMS_CHANGED`) |
| Reuse a previously-used tag (new name/type) | **catastrophic** — old payloads decode as garbage | n/a | **REJECTED** (`TAG_REUSED`) |
| Indexable scalar without `optional` | n/a | absent-vs-zero indistinguishable | **REJECTED** (`IMPLICIT_PRESENCE`) |
| `map` field | n/a | no stable naming | **REJECTED** (`MAP_FIELD`) |
| Message field without explicit `nested` | n/a | semantics ambiguous | **REJECTED** (`NESTED_UNSPECIFIED`) |
| `Any`-typed field, unknown analyzer name, dims ≤ 0, duplicate representation name, VECTOR without `vector` params | — | — | **REJECTED** (respective codes) |

Semantics of each class:

- **WIRE_SAFE_LIVE** — `RegisterSchema` applies it immediately; the
  collection revision bumps and a `CollectionEvent` PUT is emitted.
- **REQUIRES_REINDEX** — legal schema, but v1 has no online reindex, so
  `RegisterSchema` fails with `FAILED_PRECONDITION` listing the changes.
  The workflow is: create a new collection with the new schema, reindex
  into it, switch aliases client-side. (Server-driven reindex is future
  work.)
- **REJECTED** — never legal; `RegisterSchema` fails `INVALID_ARGUMENT`.
  `ValidateSchema` reports all three classes without failing, so CI can
  gate schema PRs on `ValidateSchema` output.

## 5. Build/wiring notes (proto3 extension mechanics)

- Custom options are proto2-style **extensions** of
  `google.protobuf.FieldOptions` / `MessageOptions` — the single place
  proto3 still permits `extend`. `schema_options.proto` therefore imports
  `google/protobuf/descriptor.proto`.
- **How the import resolves in our build:** the Quarkus gRPC codegen
  extracts importable protos from the dependencies named in
  `quarkus.generate-code.grpc.scan-for-imports`
  (`knn-node/src/main/resources/application.properties`). We had already
  extended that list to
  `com.google.protobuf:protobuf-java,com.google.api.grpc:proto-google-common-protos`
  for `google/rpc/status.proto`; `descriptor.proto` ships in
  protobuf-java's bundled well-known types, which are in the *default*
  scan set — so no additional wiring was needed beyond the existing
  property. `admin_service.proto` (for `FileDescriptorSet`) and
  `index_service.proto` (for `Any`) resolve the same way.
- Extension numbers `56700`/`56701` sit in the 50000–99999 range protobuf
  reserves for organization-internal use; if these protos are ever
  published for third-party composition alongside other option-extending
  schemas, numbers should be registered in the protobuf global extension
  registry.
- Java: extensions surface as `SchemaOptionsProto.field` /
  `SchemaOptionsProto.collectionDefaults`; server-side reads must use an
  `ExtensionRegistry` with `SchemaOptionsProto.registerAllExtensions(...)`
  when parsing the submitted `FileDescriptorSet`, otherwise the options
  arrive as unknown fields (a classic silent failure — the compiler must
  treat "no recognized options anywhere" as a probable registry bug and
  say so in its error).
- Users authoring schemas vendor exactly one file
  (`v1alpha1/schema_options.proto`); it is deliberately self-contained
  (imports only `descriptor.proto`) so authoring does not drag in the
  rest of the API surface.

## 6. Open questions (deferred)

- **Create-from-proto.** `RegisterSchema` requires an existing collection;
  whether `CreateCollectionRequest` should accept a `SchemaSource`
  directly (avoiding the empty-schema window) is open.
- **Converter codegen distribution.** Whether the flattening converters
  ship as a protoc plugin (`protoc-gen-pipestream`) or a build-time
  library is unresolved; the wire contract does not depend on the answer.
- **Representation backfill.** A later reindex primitive could backfill
  `NEW_REPRESENTATION` fields for old documents; classification already
  anticipates this.
- **Schema registry surface.** Fetching the registered descriptor set back
  (`GetSchema`) and watching schema revisions are natural additions;
  omitted from v1alpha1 until the storage story for descriptor sets is
  settled.
