package ai.pipestream.search.node;

import ai.pipestream.search.index.CollectionConfig;
import ai.pipestream.search.index.CollectionManager;
import ai.pipestream.search.query.HybridExecutor;
import ai.pipestream.search.query.QueryCompiler;
import ai.pipestream.search.query.QueryPlan;
import ai.pipestream.search.v1alpha1.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.*;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.*;

/**
 * gRPC SearchService implementation for v1alpha1 frame-based streaming search.
 */
@Singleton
@GrpcService
public class V1Alpha1SearchNodeService implements SearchService {

    private static final Logger LOG = Logger.getLogger(V1Alpha1SearchNodeService.class);

    @Inject
    CollectionManager collectionManager;

    @Inject
    QueryCompiler queryCompiler;

    @Inject
    HybridExecutor hybridExecutor;

    @Inject
    jakarta.enterprise.inject.Instance<MeterRegistry> meterRegistry;

    @Inject
    ai.pipestream.search.schema.SchemaStore schemaStore;

    /** Upper bound on the per-request result window. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "knn.v1alpha1.max-size", defaultValue = "1000")
    int maxSize;

    /**
     * Smallest k at which shared-floor pruning engages for collaborative
     * document-centric queries; below it the search is exactly stock search
     * (the fork's benchmarked default is 100).
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "knn.v1alpha1.floor-activation-k", defaultValue = "100")
    int floorActivationK;

    /** One merged hit: client doc id + score + owning shard. */
    record GlobalHit(String docId, float score, int shardId) {}

    @Override
    public Multi<SearchResponse> search(SearchRequest request) {
        return Multi.createFrom().<SearchResponse>emitter(emitter -> {
            String queryId = UUID.randomUUID().toString();
            long startTime = System.currentTimeMillis();

            String collectionName = request.getCollection();
            if (collectionName.isEmpty()) {
                emitter.fail(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("Collection name must not be empty")
                        .asRuntimeException());
                return;
            }

            CollectionConfig config = collectionManager.getConfig(collectionName);
            if (config == null) {
                emitter.fail(io.grpc.Status.NOT_FOUND
                        .withDescription("Collection not found: " + collectionName)
                        .asRuntimeException());
                return;
            }

            // Tag with the collection name only after it is known to exist:
            // unbounded caller-supplied tag values are a meter-cardinality leak.
            if (meterRegistry.isResolvable()) {
                meterRegistry.get().counter("knn.v1alpha1.search.requests", "collection", collectionName).increment();
            }

            // Frame 1: SearchContext (always emitted first on the success path)
            emitter.emit(SearchResponse.newBuilder()
                    .setContext(SearchContext.newBuilder().setQueryId(queryId).build())
                    .build());

            CollectionSchema schema = resolveSchema(collectionName, config);
            int targetSize = request.getSize() > 0 ? Math.min(request.getSize(), maxSize) : 10;

            try {
                // Compile the v1alpha1 query AST
                QueryPlan plan = queryCompiler.compile(request.getQuery(), schema);

                boolean documentCentricQuery = plan.knnHints().stream()
                        .anyMatch(QueryPlan.KnnHints::documentCentric);
                if (documentCentricQuery) {
                    if (!config.documentCentric()) {
                        emitter.fail(io.grpc.Status.FAILED_PRECONDITION
                                .withDescription("knn.document_centric requires a document-centric "
                                        + "collection; '" + collectionName + "' is flat")
                                .asRuntimeException());
                        return;
                    }
                    if (!(plan instanceof QueryPlan.Single single)
                            || !(single.query() instanceof ai.pipestream.search.query.DocumentCentricKnnQuery dcq)) {
                        emitter.fail(io.grpc.Status.INVALID_ARGUMENT
                                .withDescription("knn.document_centric is only valid as the "
                                        + "top-level query")
                                .asRuntimeException());
                        return;
                    }
                    searchDocumentCentric(emitter, request, config, collectionName,
                            dcq, queryId, startTime, targetSize);
                    return;
                }
                if (config.documentCentric()) {
                    // Flat (chunk-level) queries on a document-centric
                    // collection must never surface parent stubs as hits.
                    plan = excludeStubs(plan);
                }

                List<ShardSummary> shardSummaries = new ArrayList<>();
                List<GlobalHit> globalHits = new ArrayList<>();
                long totalHits = 0;
                boolean totalHitsExact = true;
                int failedShards = 0;
                List<String> shardErrors = new ArrayList<>();
                float kthBestFloor = Float.NEGATIVE_INFINITY;
                int position = 1;

                for (int shardId = 0; shardId < config.numShards(); shardId++) {
                    if (emitter.isCancelled()) {
                        LOG.debugf("Query %s cancelled by client", queryId);
                        return;
                    }

                    long shardStart = System.currentTimeMillis();
                    DirectoryReader reader = null;
                    try {
                        try {
                            reader = collectionManager.getReader(collectionName, shardId);
                        } catch (org.apache.lucene.index.IndexNotFoundException e) {
                            // Created-but-never-written shard: an empty shard,
                            // not a failure.
                            shardSummaries.add(ShardSummary.newBuilder()
                                    .setShardId(shardId)
                                    .setTookMs(System.currentTimeMillis() - shardStart)
                                    .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
                                    .build());
                            continue;
                        }
                        IndexSearcher searcher = new IndexSearcher(reader);
                        TopDocs topDocs = hybridExecutor.execute(plan, searcher, targetSize);

                        totalHits += topDocs.totalHits.value();
                        if (topDocs.totalHits.relation() != org.apache.lucene.search.TotalHits.Relation.EQUAL_TO) {
                            totalHitsExact = false;
                        }

                        StoredFields storedFields = reader.storedFields();
                        for (ScoreDoc sd : topDocs.scoreDocs) {
                            if (emitter.isCancelled()) break;

                            Document doc = storedFields.document(sd.doc);
                            String docId = doc.get("doc_id");
                            if (docId == null) {
                                docId = String.valueOf(sd.doc);
                            }
                            globalHits.add(new GlobalHit(docId, sd.score, shardId));

                            Hit.Builder hitBuilder = Hit.newBuilder()
                                    .setDocId(docId)
                                    .setScore(sd.score)
                                    .setShardId(shardId)
                                    .setResultPosition(position++);

                            // Requested stored fields
                            if (request.getFieldsCount() > 0) {
                                for (String fieldName : request.getFieldsList()) {
                                    String val = doc.get(fieldName);
                                    if (val != null) {
                                        hitBuilder.addFields(DocumentField.newBuilder()
                                                .setName(fieldName)
                                                .addValues(FieldValue.newBuilder().setStringValue(val).build())
                                                .build());
                                    }
                                }
                            }

                            emitter.emit(SearchResponse.newBuilder().setHit(hitBuilder.build()).build());
                            if (meterRegistry.isResolvable()) {
                                meterRegistry.get().counter("knn.v1alpha1.hits.emitted").increment();
                            }
                        }

                        if (topDocs.scoreDocs.length > 0) {
                            float minScore = topDocs.scoreDocs[topDocs.scoreDocs.length - 1].score;
                            kthBestFloor = Math.max(kthBestFloor, minScore);
                        }

                        shardSummaries.add(ShardSummary.newBuilder()
                                .setShardId(shardId)
                                .setTookMs(System.currentTimeMillis() - shardStart)
                                .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
                                .build());

                    } catch (Exception e) {
                        LOG.errorf(e, "Error searching shard %d for collection %s", shardId, collectionName);
                        failedShards++;
                        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        shardErrors.add("shard " + shardId + ": " + message);
                        shardSummaries.add(ShardSummary.newBuilder()
                                .setShardId(shardId)
                                .setTookMs(System.currentTimeMillis() - shardStart)
                                .setStatus(com.google.rpc.Status.newBuilder().setCode(13).setMessage(message).build())
                                .build());
                    } finally {
                        if (reader != null) {
                            collectionManager.releaseReader(reader);
                        }
                    }
                }

                // Every shard failed: that is an RPC-level failure, never an
                // OK stream with an empty Summary.
                if (failedShards > 0 && failedShards == config.numShards()) {
                    emitter.fail(io.grpc.Status.UNAVAILABLE
                            .withDescription("All " + failedShards + " shards failed: "
                                    + String.join("; ", shardErrors))
                            .asRuntimeException());
                    return;
                }

                // Rank top hits globally
                globalHits.sort((a, b) -> Float.compare(b.score(), a.score()));

                List<String> topDocIds = new ArrayList<>();
                for (int i = 0; i < Math.min(targetSize, globalHits.size()); i++) {
                    topDocIds.add(globalHits.get(i).docId());
                }

                long tookMs = System.currentTimeMillis() - startTime;

                // Final frame: Summary. `visited` stays 0 (unknown) until the
                // collector managers thread real visit counts through — a
                // plausible-looking wrong number is worse than an honest zero.
                Summary summary = Summary.newBuilder()
                        .setQueryId(queryId)
                        .addAllTopDocIds(topDocIds)
                        .setTotalHits(totalHits)
                        .setTotalHitsRelation(totalHitsExact && failedShards == 0
                                ? TotalHitsRelation.TOTAL_HITS_RELATION_EQ
                                : TotalHitsRelation.TOTAL_HITS_RELATION_GTE)
                        .setTookMs(tookMs)
                        .setKthBestFloor(kthBestFloor)
                        .setTerminatedBy(TerminationReason.TERMINATION_REASON_COMPLETE)
                        .addAllShardSummaries(shardSummaries)
                        .build();

                emitter.emit(SearchResponse.newBuilder().setSummary(summary).build());
                emitter.complete();

            } catch (Exception e) {
                LOG.errorf(e, "Search failed for query %s", queryId);
                emitter.fail(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<ExplainResponse> explain(ExplainRequest request) {
        return Uni.createFrom().item(() -> {
            String collectionName = request.getCollection();
            CollectionConfig config = collectionManager.getConfig(collectionName);
            if (config == null) {
                throw io.grpc.Status.NOT_FOUND
                        .withDescription("Collection not found: " + collectionName)
                        .asRuntimeException();
            }

            CollectionSchema schema = resolveSchema(collectionName, config);
            QueryPlan plan = queryCompiler.compile(request.getQuery(), schema);

            if (!(plan instanceof QueryPlan.Single single)) {
                throw io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("Explain currently supports single queries")
                        .asRuntimeException();
            }

            int shardId = collectionManager.routeToShard(request.getDocId(), config.numShards());
            DirectoryReader reader = null;
            try {
                try {
                    reader = collectionManager.getReader(collectionName, shardId);
                } catch (org.apache.lucene.index.IndexNotFoundException e) {
                    // Empty shard: the document does not exist there.
                    return ExplainResponse.newBuilder().setMatched(false).build();
                }
                IndexSearcher searcher = new IndexSearcher(reader);

                TopDocs topDocs = searcher.search(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("doc_id", request.getDocId())), 1);
                if (topDocs.scoreDocs.length == 0) {
                    return ExplainResponse.newBuilder().setMatched(false).build();
                }

                int luceneDocId = topDocs.scoreDocs[0].doc;
                org.apache.lucene.search.Explanation luceneExp = searcher.explain(single.query(), luceneDocId);

                return ExplainResponse.newBuilder()
                        .setMatched(luceneExp.isMatch())
                        .setExplanation(toProtoExplanation(luceneExp))
                        .build();

            } catch (IOException e) {
                throw new RuntimeException("Explain failed: " + e.getMessage(), e);
            } finally {
                if (reader != null) {
                    try { collectionManager.releaseReader(reader); } catch (IOException ignored) {}
                }
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Document-centric execution: per shard, top-D parents via the
     * diversifying block-join query plus an exact per-chunk second pass;
     * Hit frames carry the chunk scores, Summary ranks parent doc ids.
     */
    private void searchDocumentCentric(io.smallrye.mutiny.subscription.MultiEmitter<? super SearchResponse> emitter,
                                       SearchRequest request, CollectionConfig config,
                                       String collectionName,
                                       ai.pipestream.search.query.DocumentCentricKnnQuery query,
                                       String queryId, long startTime, int targetSize) {
        int chunksPerHit = request.getChunksPerHit() > 0 ? request.getChunksPerHit() : 8;
        org.apache.lucene.search.join.BitSetProducer parentsFilter =
                collectionManager.getParentsFilter(collectionName);

        // Collaborative traversal: one shared floor per query. Every shard's
        // collectors prune against the best D parent scores seen so far, so
        // later shards inherit the earlier shards' floor.
        boolean collaborative = request.getQuery().getKnn().getCollaborative();
        org.apache.lucene.sandbox.search.knn.GlobalKnnFloor floor = collaborative
                ? new org.apache.lucene.sandbox.search.knn.GlobalKnnFloor(query.luceneK())
                : null;
        java.util.concurrent.atomic.AtomicLong visitedTotal = new java.util.concurrent.atomic.AtomicLong();

        List<ShardSummary> shardSummaries = new ArrayList<>();
        List<DocumentMerger.ShardDocument> perShard = new ArrayList<>();
        int failedShards = 0;
        List<String> shardErrors = new ArrayList<>();

        for (int shardId = 0; shardId < config.numShards(); shardId++) {
            if (emitter.isCancelled()) {
                LOG.debugf("Query %s cancelled by client", queryId);
                return;
            }
            long shardStart = System.currentTimeMillis();
            long visitedBefore = visitedTotal.get();
            DirectoryReader reader = null;
            try {
                try {
                    reader = collectionManager.getReader(collectionName, shardId);
                } catch (org.apache.lucene.index.IndexNotFoundException e) {
                    shardSummaries.add(ShardSummary.newBuilder()
                            .setShardId(shardId)
                            .setTookMs(System.currentTimeMillis() - shardStart)
                            .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
                            .build());
                    continue;
                }
                IndexSearcher searcher = new IndexSearcher(reader);
                org.apache.lucene.search.knn.KnnCollectorManager manager = null;
                if (floor != null) {
                    manager = new ai.pipestream.search.query.knn.CountingKnnCollectorManager(
                            ai.pipestream.search.query.knn.DocumentCentricKnnFactory.manager(
                                    query.luceneK(), floor, parentsFilter,
                                    1f / config.numShards(), floorActivationK),
                            visitedTotal);
                }
                ai.pipestream.search.query.DocumentTopDocs topDocs =
                        hybridExecutor.executeDocumentCentric(query, searcher, parentsFilter,
                                chunksPerHit, manager);
                for (ai.pipestream.search.query.DocumentTopDocs.DocumentHit hit : topDocs.hits()) {
                    perShard.add(new DocumentMerger.ShardDocument(
                            hit.docId(), shardId, hit.score(), hit.chunks()));
                }
                shardSummaries.add(ShardSummary.newBuilder()
                        .setShardId(shardId)
                        .setVisited(visitedTotal.get() - visitedBefore)
                        .setTookMs(System.currentTimeMillis() - shardStart)
                        .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
                        .build());
            } catch (Exception e) {
                LOG.errorf(e, "Error searching shard %d for collection %s", shardId, collectionName);
                failedShards++;
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                shardErrors.add("shard " + shardId + ": " + message);
                shardSummaries.add(ShardSummary.newBuilder()
                        .setShardId(shardId)
                        .setTookMs(System.currentTimeMillis() - shardStart)
                        .setStatus(com.google.rpc.Status.newBuilder().setCode(13).setMessage(message).build())
                        .build());
            } finally {
                if (reader != null) {
                    try {
                        collectionManager.releaseReader(reader);
                    } catch (IOException ignored) {
                        // release failures are non-fatal
                    }
                }
            }
        }

        if (failedShards > 0 && failedShards == config.numShards()) {
            emitter.fail(io.grpc.Status.UNAVAILABLE
                    .withDescription("All " + failedShards + " shards failed: "
                            + String.join("; ", shardErrors))
                    .asRuntimeException());
            return;
        }

        // Cross-shard collapse by parent id: a balanced-placement parent
        // returns from every shard holding one of its blocks; the merge takes
        // the max score and concatenates the (disjoint) chunk lists.
        List<DocumentMerger.MergedDocument> merged =
                DocumentMerger.merge(perShard, targetSize, chunksPerHit);

        int emitted = 0;
        float kthBestFloor = Float.NEGATIVE_INFINITY;
        List<String> topDocIds = new ArrayList<>();
        for (DocumentMerger.MergedDocument document : merged) {
            if (emitter.isCancelled()) {
                break;
            }
            emitted++;
            topDocIds.add(document.docId());
            kthBestFloor = Math.max(kthBestFloor, document.score());

            Hit.Builder hitBuilder = Hit.newBuilder()
                    .setDocId(document.docId())
                    .setScore(document.score())
                    .setShardId(document.shardId())
                    .setResultPosition(emitted);
            for (DocumentMerger.ShardChunk shardChunk : document.chunks()) {
                ai.pipestream.search.query.DocumentTopDocs.ChunkScore chunk = shardChunk.chunk();
                ChunkHit.Builder chunkHit = ChunkHit.newBuilder()
                        .setChunkId(chunk.chunkId())
                        .setScore(chunk.score())
                        .setText(chunk.text())
                        .setOrdinal(chunk.ordinal())
                        .setShardId(shardChunk.shardId())
                        .setStartOffset(chunk.startOffset())
                        .setEndOffset(chunk.endOffset());
                if (chunk.nlp() != null) {
                    try {
                        chunkHit.addAllNlp(ai.pipestream.search.v1alpha1.NlpSpans
                                .parseFrom(chunk.nlp()).getSpansList());
                    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                        LOG.warnf("Dropping unreadable stored NLP spans for chunk %s: %s",
                                chunk.chunkId(), e.getMessage());
                    }
                }
                hitBuilder.addChunks(chunkHit.build());
            }
            emitter.emit(SearchResponse.newBuilder().setHit(hitBuilder.build()).build());
        }

        Summary summary = Summary.newBuilder()
                .setQueryId(queryId)
                .addAllTopDocIds(topDocIds)
                .setTotalHits(merged.size())
                .setTotalHitsRelation(TotalHitsRelation.TOTAL_HITS_RELATION_GTE)
                .setVisited(visitedTotal.get())
                .setTookMs(System.currentTimeMillis() - startTime)
                .setKthBestFloor(kthBestFloor)
                .setTerminatedBy(TerminationReason.TERMINATION_REASON_COMPLETE)
                .addAllShardSummaries(shardSummaries)
                .build();
        emitter.emit(SearchResponse.newBuilder().setSummary(summary).build());
        emitter.complete();
    }

    /** Rebuilds a plan with parent stubs excluded from every leaf query. */
    private static QueryPlan excludeStubs(QueryPlan plan) {
        return switch (plan) {
            case QueryPlan.Single single -> new QueryPlan.Single(
                    new org.apache.lucene.search.BooleanQuery.Builder()
                            .add(single.query(), org.apache.lucene.search.BooleanClause.Occur.MUST)
                            .add(ai.pipestream.search.index.doc.BlockJoinFields.PARENT_QUERY,
                                    org.apache.lucene.search.BooleanClause.Occur.MUST_NOT)
                            .build(),
                    single.knnHints());
            case QueryPlan.Hybrid hybrid -> new QueryPlan.Hybrid(
                    hybrid.subPlans().stream().map(V1Alpha1SearchNodeService::excludeStubs).toList(),
                    hybrid.fusion());
        };
    }

    /**
     * The registered proto schema when one exists (the schema plane must
     * reach the read path), else the synthesized two-field fallback for
     * collections created without one.
     */
    private CollectionSchema resolveSchema(String collectionName, CollectionConfig config) {
        return schemaStore.get(collectionName)
                .map(stored -> stored.compiled().toProto())
                .orElseGet(() -> toProtoSchema(config));
    }

    private static CollectionSchema toProtoSchema(CollectionConfig config) {
        CollectionSchema.Builder schemaBuilder = CollectionSchema.newBuilder();

        schemaBuilder.addFields(FieldSchema.newBuilder()
                .setName("doc_id")
                .setKeyword(KeywordFieldSchema.newBuilder().build())
                .setStored(true)
                .build());

        schemaBuilder.addFields(FieldSchema.newBuilder()
                .setName("vector")
                .setDenseVector(DenseVectorFieldSchema.newBuilder()
                        .setDims(config.vectorDimension())
                        .setSimilarity(toProtoSimilarity(config.similarity()))
                        .build())
                .setStored(false)
                .build());

        return schemaBuilder.build();
    }

    private static ai.pipestream.search.v1alpha1.VectorSimilarity toProtoSimilarity(org.apache.lucene.index.VectorSimilarityFunction sim) {
        return switch (sim) {
            case COSINE -> ai.pipestream.search.v1alpha1.VectorSimilarity.VECTOR_SIMILARITY_COSINE;
            case DOT_PRODUCT -> ai.pipestream.search.v1alpha1.VectorSimilarity.VECTOR_SIMILARITY_DOT_PRODUCT;
            case EUCLIDEAN -> ai.pipestream.search.v1alpha1.VectorSimilarity.VECTOR_SIMILARITY_EUCLIDEAN;
            case MAXIMUM_INNER_PRODUCT -> ai.pipestream.search.v1alpha1.VectorSimilarity.VECTOR_SIMILARITY_MAX_INNER_PRODUCT;
        };
    }

    private static ai.pipestream.search.v1alpha1.Explanation toProtoExplanation(org.apache.lucene.search.Explanation exp) {
        ai.pipestream.search.v1alpha1.Explanation.Builder builder = ai.pipestream.search.v1alpha1.Explanation.newBuilder()
                .setValue(exp.getValue().floatValue())
                .setDescription(exp.getDescription());

        for (org.apache.lucene.search.Explanation detail : exp.getDetails()) {
            builder.addDetails(toProtoExplanation(detail));
        }

        return builder.build();
    }
}
