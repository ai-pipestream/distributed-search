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

    @Override
    public Multi<SearchResponse> search(SearchRequest request) {
        return Multi.createFrom().<SearchResponse>emitter(emitter -> {
            String queryId = UUID.randomUUID().toString();
            long startTime = System.currentTimeMillis();

            if (meterRegistry.isResolvable()) {
                meterRegistry.get().counter("knn.v1alpha1.search.requests", "collection", request.getCollection()).increment();
            }

            // Frame 1: SearchContext (always emitted first)
            SearchContext context = SearchContext.newBuilder()
                    .setQueryId(queryId)
                    .build();
            emitter.emit(SearchResponse.newBuilder().setContext(context).build());

            String collectionName = request.getCollection();
            if (collectionName.isEmpty()) {
                emitter.fail(new IllegalArgumentException("Collection name must not be empty"));
                return;
            }

            CollectionConfig config = collectionManager.getConfig(collectionName);
            if (config == null) {
                emitter.fail(new IllegalArgumentException("Collection not found: " + collectionName));
                return;
            }

            CollectionSchema schema = toProtoSchema(config);
            int targetSize = request.getSize() > 0 ? request.getSize() : 10;

            try {
                // Compile the v1alpha1 query AST
                QueryPlan plan = queryCompiler.compile(request.getQuery(), schema);

                List<ShardSummary> shardSummaries = new ArrayList<>();
                List<ScoreDoc> globalHits = new ArrayList<>();
                long totalVisited = 0;
                float kthBestFloor = Float.NEGATIVE_INFINITY;

                for (int shardId = 0; shardId < config.numShards(); shardId++) {
                    if (emitter.isCancelled()) {
                        LOG.debugf("Query %s cancelled by client", queryId);
                        return;
                    }

                    long shardStart = System.currentTimeMillis();
                    DirectoryReader reader = null;
                    try {
                        reader = collectionManager.getReader(collectionName, shardId);
                        IndexSearcher searcher = new IndexSearcher(reader);
                        TopDocs topDocs = hybridExecutor.execute(plan, searcher, targetSize);

                        StoredFields storedFields = reader.storedFields();
                        int position = 1;
                        for (ScoreDoc sd : topDocs.scoreDocs) {
                            if (emitter.isCancelled()) break;
                            globalHits.add(sd);

                            Document doc = storedFields.document(sd.doc);
                            String docId = doc.get("doc_id");
                            if (docId == null) {
                                docId = String.valueOf(sd.doc);
                            }

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
                                .setVisited(topDocs.scoreDocs.length)
                                .setTookMs(System.currentTimeMillis() - shardStart)
                                .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
                                .build());

                    } catch (Exception e) {
                        LOG.errorf(e, "Error searching shard %d for collection %s", shardId, collectionName);
                        shardSummaries.add(ShardSummary.newBuilder()
                                .setShardId(shardId)
                                .setTookMs(System.currentTimeMillis() - shardStart)
                                .setStatus(com.google.rpc.Status.newBuilder().setCode(13).setMessage(e.getMessage()).build())
                                .build());
                    } finally {
                        if (reader != null) {
                            collectionManager.releaseReader(reader);
                        }
                    }
                }

                // Rank top hits globally
                globalHits.sort((a, b) -> Float.compare(b.score, a.score));

                List<String> topDocIds = new ArrayList<>();
                for (int i = 0; i < Math.min(targetSize, globalHits.size()); i++) {
                    topDocIds.add(String.valueOf(globalHits.get(i).doc));
                }

                long tookMs = System.currentTimeMillis() - startTime;

                // Final frame: Summary
                Summary summary = Summary.newBuilder()
                        .setQueryId(queryId)
                        .addAllTopDocIds(topDocIds)
                        .setTotalHits(globalHits.size())
                        .setTotalHitsRelation(TotalHitsRelation.TOTAL_HITS_RELATION_EQ)
                        .setVisited(totalVisited > 0 ? totalVisited : globalHits.size())
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
                throw new IllegalArgumentException("Collection not found: " + collectionName);
            }

            CollectionSchema schema = toProtoSchema(config);
            QueryPlan plan = queryCompiler.compile(request.getQuery(), schema);

            if (!(plan instanceof QueryPlan.Single single)) {
                throw new IllegalArgumentException("Explain currently supports single queries");
            }

            int shardId = collectionManager.routeToShard(request.getDocId(), config.numShards());
            DirectoryReader reader = null;
            try {
                reader = collectionManager.getReader(collectionName, shardId);
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
