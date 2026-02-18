package ai.pipestream.search.index;

import ai.pipestream.index.v1.*;
import ai.pipestream.search.node.DjlService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * gRPC IndexService implementation — handles document indexing, deletion, and collection CRUD.
 */
@Singleton
@GrpcService
public class IndexNodeService implements IndexService {

    private static final Logger LOG = Logger.getLogger(IndexNodeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    CollectionManager collections;

    @Inject
    LuceneDocumentConverter converter;

    @Inject
    @RestClient
    DjlService djlService;

    @ConfigProperty(name = "knn.query.prefix", defaultValue = "query: ")
    String queryPrefix;

    // --- Document Indexing ---

    @Override
    public Uni<IndexDocumentResponse> indexDocument(IndexDocumentRequest request) {
        return Uni.createFrom().item(() -> {
            try {
                return indexSingle(request);
            } catch (Exception e) {
                LOG.debugf(e, "Failed to index doc %s: %s", request.getDocId(), e.getMessage());
                return IndexDocumentResponse.newBuilder()
                        .setSuccess(false)
                        .setDocId(request.getDocId())
                        .setError(e.getMessage())
                        .build();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Multi<IndexDocumentResponse> streamIndex(Multi<IndexDocumentRequest> requests) {
        return requests
                .onItem().transformToUniAndMerge(request ->
                        Uni.createFrom().item(() -> {
                            try {
                                return indexSingle(request);
                            } catch (Exception e) {
                                LOG.debugf(e, "Stream index failed for doc %s: %s", request.getDocId(), e.getMessage());
                                return IndexDocumentResponse.newBuilder()
                                        .setSuccess(false)
                                        .setDocId(request.getDocId())
                                        .setError(e.getMessage())
                                        .build();
                            }
                        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                );
    }

    @Override
    public Uni<DeleteDocumentResponse> deleteDocument(DeleteDocumentRequest request) {
        return Uni.createFrom().item(() -> {
            try {
                CollectionConfig config = collections.getConfig(request.getCollection());
                if (config == null) {
                    return DeleteDocumentResponse.newBuilder().setFound(false).build();
                }

                // Delete from all shards (doc could have been re-routed)
                boolean deleted = false;
                int shardId = collections.routeToShard(request.getDocId(), config.numShards());
                IndexWriter writer = collections.getWriter(request.getCollection(), shardId);
                long seqNo = writer.deleteDocuments(new Term("doc_id", request.getDocId()));
                deleted = seqNo >= 0;

                return DeleteDocumentResponse.newBuilder().setFound(deleted).build();
            } catch (Exception e) {
                LOG.errorf(e, "Failed to delete doc %s", request.getDocId());
                return DeleteDocumentResponse.newBuilder().setFound(false).build();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // --- Collection CRUD ---

    @Override
    public Uni<CreateCollectionResponse> createCollection(CreateCollectionRequest request) {
        return Uni.createFrom().item(() -> {
            try {
                var similarity = CollectionManager.toLuceneSimilarity(request.getSimilarity());
                int numShards = request.getNumShards() > 0 ? request.getNumShards() : 1;

                CollectionConfig config = collections.createCollection(
                        request.getName(),
                        request.getVectorDimension(),
                        similarity,
                        numShards,
                        request.getEmbeddingModel()
                );

                return CreateCollectionResponse.newBuilder()
                        .setCollection(toCollectionInfo(config))
                        .build();
            } catch (Exception e) {
                LOG.errorf(e, "Failed to create collection %s", request.getName());
                throw new RuntimeException(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<GetCollectionResponse> getCollection(GetCollectionRequest request) {
        return Uni.createFrom().item(() -> {
            CollectionConfig config = collections.getConfig(request.getName());
            if (config == null) {
                throw new RuntimeException("Collection not found: " + request.getName());
            }
            return GetCollectionResponse.newBuilder()
                    .setCollection(toCollectionInfo(config))
                    .build();
        });
    }

    @Override
    public Uni<ListCollectionsResponse> listCollections(ListCollectionsRequest request) {
        return Uni.createFrom().item(() -> {
            var builder = ListCollectionsResponse.newBuilder();
            for (CollectionConfig config : collections.listCollections()) {
                builder.addCollections(toCollectionInfo(config));
            }
            return builder.build();
        });
    }

    @Override
    public Uni<DeleteCollectionResponse> deleteCollection(DeleteCollectionRequest request) {
        return Uni.createFrom().item(() -> {
            try {
                boolean deleted = collections.deleteCollection(request.getName());
                return DeleteCollectionResponse.newBuilder().setSuccess(deleted).build();
            } catch (Exception e) {
                LOG.errorf(e, "Failed to delete collection %s", request.getName());
                return DeleteCollectionResponse.newBuilder().setSuccess(false).build();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // --- Private helpers ---

    private IndexDocumentResponse indexSingle(IndexDocumentRequest request) throws Exception {
        CollectionConfig config = collections.getConfig(request.getCollection());
        if (config == null) {
            throw new IllegalArgumentException("Collection not found: " + request.getCollection());
        }

        // Resolve vector from content oneof
        float[] vector = resolveVector(request, config);

        // Validate dimension
        if (vector.length != config.vectorDimension()) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch: expected " + config.vectorDimension() + ", got " + vector.length);
        }

        // Convert to Lucene document
        Document doc = converter.convert(request, vector, config);

        // Route to shard
        int shardId = collections.routeToShard(request.getDocId(), config.numShards());
        IndexWriter writer = collections.getWriter(request.getCollection(), shardId);

        // Upsert: delete existing doc with same ID, then add
        writer.updateDocument(new Term("doc_id", request.getDocId()), doc);

        return IndexDocumentResponse.newBuilder()
                .setSuccess(true)
                .setDocId(request.getDocId())
                .setShardId(shardId)
                .build();
    }

    private float[] resolveVector(IndexDocumentRequest request, CollectionConfig config) throws Exception {
        return switch (request.getContentCase()) {
            case VECTOR -> toFloatArray(request.getVector().getValuesList());
            case TEXT_AND_VECTOR -> toFloatArray(request.getTextAndVector().getVectorList());
            case TEXT -> embedText(request.getText(), config);
            case CONTENT_NOT_SET -> throw new IllegalArgumentException("No content provided in request");
        };
    }

    private float[] embedText(String text, CollectionConfig config) throws Exception {
        if (config.embeddingModel().isEmpty()) {
            throw new IllegalArgumentException(
                    "Collection " + config.name() + " has no embedding model configured; provide a vector directly");
        }

        // Use DJL service for embedding
        String raw = djlService.getEmbeddingsRaw(List.of(text)).await().indefinitely();
        List<List<Float>> embeddings = MAPPER.readValue(raw, new TypeReference<>() {});
        if (embeddings.isEmpty() || embeddings.get(0).isEmpty()) {
            throw new IllegalStateException("DJL returned empty embeddings");
        }
        return toFloatArray(embeddings.get(0));
    }

    private static float[] toFloatArray(List<Float> values) {
        float[] arr = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i);
        }
        return arr;
    }

    private CollectionInfo toCollectionInfo(CollectionConfig config) {
        var builder = CollectionInfo.newBuilder()
                .setName(config.name())
                .setVectorDimension(config.vectorDimension())
                .setSimilarity(toLuceneProtoSimilarity(config))
                .setNumShards(config.numShards())
                .setTotalDocs(collections.getTotalDocCount(config.name()))
                .setEmbeddingModel(config.embeddingModel());

        for (int i = 0; i < config.numShards(); i++) {
            builder.addShards(ShardInfo.newBuilder()
                    .setShardId(i)
                    .setNumDocs(collections.getDocCount(config.name(), i))
                    .build());
        }
        return builder.build();
    }

    private static VectorSimilarity toLuceneProtoSimilarity(CollectionConfig config) {
        return switch (config.similarity()) {
            case COSINE -> VectorSimilarity.VECTOR_SIMILARITY_COSINE;
            case DOT_PRODUCT -> VectorSimilarity.VECTOR_SIMILARITY_DOT_PRODUCT;
            case EUCLIDEAN -> VectorSimilarity.VECTOR_SIMILARITY_EUCLIDEAN;
            default -> VectorSimilarity.VECTOR_SIMILARITY_COSINE;
        };
    }
}
