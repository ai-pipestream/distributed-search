# embeddings

Plain-Java embedding modules for distributed search — no Quarkus, no CDI; any
host wires these in as plain jars. See `docs/INTEGRATION_PLAN.md` §3–§6 for the
policy these implement.

- **`embeddings-spi`** — `EmbeddingProvider`
  (`name / supports(model) / dims(model) / embed(model, texts)`) plus
  `EmbeddingProviders` ServiceLoader discovery. Providers register via
  `META-INF/services`; a model no provider supports fails loud.
- **`model2vec-provider`** — in-process provider ("fast lane" default) backed
  by OpenNLP static embeddings
  (`ai.pipestream:opennlp-embeddings:3.x-experimental-SNAPSHOT`, local m2).
  Default model: **potion-retrieval-32M** (512-dim; BEIR SciFact recall@10
  0.795 vs 0.808 for the all-MiniLM-L6-v2 transformer — near-teacher quality
  at static cost). Model registry: properties file of
  `model-id=/path/to/model-dir`, via
  `-Dai.pipestream.search.embeddings.model2vec.config=` or `MODEL2VEC_MODELS`.
- **`kserve-provider`** — KServe v2 gRPC client ("quality lane" transport) for
  OpenVINO Model Server / Triton. Plaintext channel only.
  Endpoint via `-Dai.pipestream.search.embeddings.kserve.endpoint=host:port`
  (or `KSERVE_ENDPOINT`), models via `...kserve.models` (or `KSERVE_MODELS`,
  comma-separated). Also carries `KServeRerankProvider` (see reranking below).
- **`tei-provider`** — clients for Text Embeddings Inference. Two transports:
  `TEIEmbeddingProvider` ("tei", HTTP/JSON `POST /embed`) is the batched-call
  transport — fastest correct option for this SPI's batch contract (TEI
  batches a whole request server-side; note its default 32-text client-batch
  cap). `TEIGrpcEmbeddingProvider` ("tei-grpc", `tei.v1.Embed` gRPC) is the
  per-worker streaming transport for ingestion pipelines; TEI's stream
  carries one text per message and returns responses out of order
  (measured), so batched gRPC must run lockstep and is slower than REST for
  single-client batches. Endpoint via `-Dai.pipestream...tei.endpoint=` (or
  `TEI_ENDPOINT`), models via `...tei.models` (or `TEI_MODELS`). Also carries
  `TEIRerankProvider` (see below).

## reranking

`RerankProvider` (`embeddings-spi`) is the cross-encoder head of the
retrieve-then-rerank pipeline: `score(model, query, documents)` returns one
score per document, same order, blocking, with `RerankProviders` ServiceLoader
discovery. Implementations: `TEIRerankProvider` (TEI `POST /rerank`, maps the
score-sorted response back to input order), `OvmsRerankProvider` (OVMS
Cohere-compatible `POST /v3/rerank`; the rerank graph is REST-first, its
KServe gRPC surface is the same JSON as an opaque payload), and
`KServeRerankProvider` (KServe v2 cross-encoder; query + documents as BYTES
tensors, FP32 scores out, tensor names configurable — check `ModelMetadata`
when wiring a new endpoint).

OVMS rerank note: the OVMS rerank calculator assembles query/document pairs
itself and fills `token_type_ids` with zeros, so the served cross-encoder must
be a two-input model (no `token_type_ids`) — BAAI/bge-reranker-base certifies;
three-input models (e.g. cross-encoder/ms-marco-MiniLM-L-6-v2) score
degenerately there and must stay TEI-only. The tokenizer IR must be converted
with `add_special_tokens=False` and the model config must carry
`bos_token_id`/`eos_token_id`.

The ranked-list certification (`RerankEquivalenceHarness`) uses Kendall tau
(scale-invariant — monotone re-scalings pass by design) plus tie-expanded
top-k overlap (the cutoff absorbs near-ties, so a runtime emitting exact ties
and one emitting the same scores at different precision still agree), with
the same negative-control discipline as the embedding gate. The first
cross-runtime rerank pair is certified: OVMS vs TEI serving
BAAI/bge-reranker-base (tau = 1.0, overlap = 1.0). Live-test env vars:
`TEI_TEST_RERANK_ENDPOINT`, `OVMS_TEST_RERANK_ENDPOINT`,
`KSERVE_RERANK_ENDPOINT` / `TEI_RERANK_ENDPOINT`, `OVMS_RERANK_ENDPOINT`
(+ `*_RERANK_MODELS`).

## testing
- **`equivalence-harness`** — the certification gate of the two-lane policy:
  same model on two providers must show min pairwise cosine ≥ 0.999 *and* mean
  top-5 retrieval overlap ≥ 0.99 before the pair may be mixed ("accurate
  lane"). The differently-seeded-stub negative control runs in CI; a gate that
  stops failing that control is itself broken.

Build and test (Gradle, offline-capable once the m2 snapshots exist):

```shell
gradle test
```

Adding a provider (OpenVINO/KServe, TEI): implement `EmbeddingProvider` as a
plain blocking client, register it in `META-INF/services`, then certify it
against the incumbent provider for the same model with the harness before
routing traffic across both.
