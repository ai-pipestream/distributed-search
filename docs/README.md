# Documentation

Start here:

- [Architecture](ARCHITECTURE.md): what the current code actually does
- [Product readiness](PRODUCT_READINESS.md): release blockers and priorities
- [ProtoMolt integration](PROTOMOLT_INTEGRATION.md): ownership boundary and migration plan
- [Search API v1 RFC](rfc/SEARCH_API_V1.md): proposed public API
- [Schema-as-proto RFC](rfc/SCHEMA_AS_PROTO.md): current experimental schema dialect
- [Document-centric search RFC](rfc/DOC_CENTRIC_SEARCH_V1.md): proposed chunk-grouped document search with shard-local block joins and a shared parent-score floor

## Implementation-backed design

These documents describe work that has at least a corresponding implementation
or experimental code path. They are not all production-ready.

- [Dynamic collaborative k](DYNAMIC_K_SCALING.md)
- [ord-to-doc integration](ORD_TO_DOC_INTEGRATION.md)
- [Document-centric short circuit](DOCUMENT_CENTRIC_SHORT_CIRCUIT.md)

## Research backlog

These are proposals. They do not describe a supported runtime capability.

- [HTTP/3 streaming coordination](HTTP3_STREAMING_COORDINATION.md)
- [Locality-sensitive routing](LOCALITY_SENSITIVE_ROUTING.md)
- [RAG vector highlighting](RAG_VECTOR_HIGHLIGHTING.md)
- [Search and re-search](SEARCH_RESEARCH_STRATEGY.md)
- [Topology-aware coordination](TOPOLOGY_AWARE_COORDINATION.md)
- [Two-pass RAG index](TWO_PASS_RAG_INDEX.md)

Keeping research separate from current behavior is intentional. Promote a
document out of this section only when the implementation, tests, and operator
instructions land together.
