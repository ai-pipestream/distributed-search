package ai.pipestream.search.node;

import ai.pipestream.search.index.CollectionConfig;
import ai.pipestream.search.index.CollectionManager;
import ai.pipestream.search.schema.CompiledSchema;
import ai.pipestream.search.schema.SchemaCompiler;
import ai.pipestream.search.schema.SchemaValidator;
import ai.pipestream.search.v1alpha1.*;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC CollectionAdminService implementation for v1alpha1 collection and schema management.
 */
@Singleton
@GrpcService
public class V1Alpha1AdminNodeService implements CollectionAdminService {

    private static final Logger LOG = Logger.getLogger(V1Alpha1AdminNodeService.class);

    @Inject
    CollectionManager collectionManager;

    @Inject
    ai.pipestream.search.schema.SchemaStore schemaStore;

    private final Map<String, CollectionSchema> registeredSchemas = new ConcurrentHashMap<>();
    private final Map<String, Experiment> experiments = new ConcurrentHashMap<>();
    private final Map<String, RankingProfile> rankingProfiles = new ConcurrentHashMap<>();

    @Override
    public Uni<CreateCollectionResponse> createCollection(CreateCollectionRequest request) {
        return Uni.createFrom().item(() -> {
            String name = request.getName();
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Collection name must not be empty");
            }

            int vectorDimension = 384;
            VectorSimilarityFunction similarity = VectorSimilarityFunction.COSINE;

            if (request.hasSchema()) {
                CollectionSchema schema = request.getSchema();
                for (FieldSchema field : schema.getFieldsList()) {
                    if (field.getTypeCase() == FieldSchema.TypeCase.DENSE_VECTOR) {
                        vectorDimension = field.getDenseVector().getDims();
                        similarity = CollectionManager.toLuceneSimilarity(field.getDenseVector().getSimilarity());
                        break;
                    }
                }
            }

            int numShards = request.getNumShards() > 0 ? request.getNumShards() : 1;

            try {
                CollectionConfig config = collectionManager.createCollection(
                        name, vectorDimension, similarity, numShards, ""
                );
                // Register the schema only after the create succeeded: a failed
                // create must never replace an existing collection's schema.
                if (request.hasSchema()) {
                    registeredSchemas.putIfAbsent(name, request.getSchema());
                }
                return CreateCollectionResponse.newBuilder()
                        .setCollection(toProtoCollection(config, registeredSchemas.get(name)))
                        .build();
            } catch (IllegalArgumentException e) {
                throw io.grpc.Status.ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException();
            } catch (Exception e) {
                LOG.errorf(e, "Failed to create collection %s", name);
                throw new RuntimeException(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<DropCollectionResponse> dropCollection(DropCollectionRequest request) {
        return Uni.createFrom().item(() -> {
            boolean deleted;
            try {
                deleted = collectionManager.deleteCollection(request.getName());
            } catch (Exception e) {
                LOG.errorf(e, "Failed to drop collection %s", request.getName());
                throw new RuntimeException(e);
            }
            if (!deleted) {
                // Expected outcome, not an ERROR-with-stack-trace event.
                throw io.grpc.Status.NOT_FOUND
                        .withDescription("Collection not found: " + request.getName())
                        .asRuntimeException();
            }
            registeredSchemas.remove(request.getName());
            schemaStore.delete(request.getName());
            return DropCollectionResponse.newBuilder().build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<GetCollectionResponse> getCollection(GetCollectionRequest request) {
        return Uni.createFrom().item(() -> {
            CollectionConfig config = collectionManager.getConfig(request.getName());
            if (config == null) {
                throw io.grpc.Status.NOT_FOUND
                        .withDescription("Collection not found: " + request.getName())
                        .asRuntimeException();
            }
            return GetCollectionResponse.newBuilder()
                    .setCollection(toProtoCollection(config, registeredSchemas.get(request.getName())))
                    .build();
            // getDocStats() behind toProtoCollection can block on merges —
            // keep this off the gRPC event loop.
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<ListCollectionsResponse> listCollections(ListCollectionsRequest request) {
        return Uni.createFrom().item(() -> {
            ListCollectionsResponse.Builder builder = ListCollectionsResponse.newBuilder();
            for (CollectionConfig config : collectionManager.listCollections()) {
                builder.addCollections(toProtoCollection(config, registeredSchemas.get(config.name())));
            }
            return builder.build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Multi<CollectionEvent> watchCollections(WatchCollectionsRequest request) {
        return Multi.createFrom().emitter(emitter -> {
            if (request.getSendInitialState()) {
                for (CollectionConfig config : collectionManager.listCollections()) {
                    emitter.emit(CollectionEvent.newBuilder()
                            .setType(CollectionEvent.Type.TYPE_PUT)
                            .setName(config.name())
                            .setCollection(toProtoCollection(config, registeredSchemas.get(config.name())))
                            .setRevision(1)
                            .build());
                }
            }
            emitter.complete();
        });
    }

    /**
     * Re-parses the submitted descriptor set with the schema-options extension
     * registry. gRPC decodes nested messages with protobuf's EMPTY registry,
     * so without this step every (ai.pipestream.search.v1alpha1.field)
     * annotation arrives as an unknown field and the schema silently compiles
     * to zero fields — the trap documented on SchemaCompiler.parseDescriptorSet.
     */
    private static SchemaCompiler.Result compileSource(SchemaSource source) throws Exception {
        com.google.protobuf.DescriptorProtos.FileDescriptorSet reparsed =
                SchemaCompiler.parseDescriptorSet(source.getDescriptorSet().toByteArray());
        return SchemaCompiler.compile(reparsed, source.getRootMessage());
    }

    @Override
    public Uni<RegisterSchemaResponse> registerSchema(RegisterSchemaRequest request) {
        return Uni.createFrom().item(() -> {
            CollectionConfig config = collectionManager.getConfig(request.getCollection());
            if (config == null) {
                throw io.grpc.Status.NOT_FOUND
                        .withDescription("Collection not found: " + request.getCollection())
                        .asRuntimeException();
            }

            try {
                SchemaCompiler.Result result = compileSource(request.getSource());
                if (!result.rejections().isEmpty()) {
                    // The compiler drops rejected fields rather than throwing;
                    // registering the survivors as a complete success would
                    // green-light a schema the caller thinks is fully live.
                    throw io.grpc.Status.INVALID_ARGUMENT
                            .withDescription("Schema rejected: " + describeChanges(result.rejections()))
                            .asRuntimeException();
                }
                CompiledSchema compiled = result.schema();
                if (compiled.fields().isEmpty()) {
                    throw io.grpc.Status.INVALID_ARGUMENT
                            .withDescription("Schema compiled to zero indexable fields; "
                                    + "check that the descriptor set carries "
                                    + "(ai.pipestream.search.v1alpha1.field) annotations")
                            .asRuntimeException();
                }

                String chunkMessage = request.getSource().getChunkMessage();
                if (!chunkMessage.isEmpty() && !config.documentCentric()) {
                    // A non-empty chunk_message declares a document-centric
                    // collection. The parent field is create-time-only in
                    // Lucene, so the flip is legal only while the collection
                    // is still empty.
                    try {
                        config = collectionManager.replaceConfig(new CollectionConfig(
                                config.name(), config.vectorDimension(), config.similarity(),
                                config.numShards(), config.embeddingModel(),
                                true, chunkMessage,
                                CollectionConfig.PlacementMode.BALANCED_SIMILARITY,
                                config.maxChunksPerDocument()));
                    } catch (IllegalStateException e) {
                        throw io.grpc.Status.FAILED_PRECONDITION
                                .withDescription(e.getMessage())
                                .asRuntimeException();
                    }
                }

                // Persist the descriptor set bytes exactly as received — the
                // digest is only stable over the original bytes.
                schemaStore.register(request.getCollection(),
                        request.getSource().getDescriptorSet().toByteArray(),
                        request.getSource().getRootMessage(),
                        chunkMessage);

                CollectionSchema protoSchema = compiled.toProto();
                registeredSchemas.put(request.getCollection(), protoSchema);

                Collection protoCollection = toProtoCollection(config, protoSchema);
                return RegisterSchemaResponse.newBuilder()
                        .setCollection(protoCollection)
                        .addChanges(SchemaChange.newBuilder()
                                .setClassification(SchemaChange.Classification.CLASSIFICATION_WIRE_SAFE_LIVE)
                                .setField("")
                                .setCode("SCHEMA_REGISTERED")
                                .setDescription("Successfully registered schema from proto source")
                                .build())
                        .build();
            } catch (io.grpc.StatusRuntimeException e) {
                throw e;
            } catch (Exception e) {
                LOG.errorf(e, "Failed to register schema for %s", request.getCollection());
                throw io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("Schema compilation failed: " + e.getMessage())
                        .asRuntimeException();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private static String describeChanges(List<SchemaChange> changes) {
        StringBuilder sb = new StringBuilder();
        for (SchemaChange change : changes) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(change.getField()).append(": ").append(change.getCode());
        }
        return sb.toString();
    }

    @Override
    public Uni<ValidateSchemaResponse> validateSchema(ValidateSchemaRequest request) {
        return Uni.createFrom().item(() -> {
            try {
                SchemaCompiler.Result res = compileSource(request.getSource());
                CompiledSchema proposed = res.schema();
                CollectionSchema protoSchema = proposed.toProto();

                // Rejections are always reported — hiding them for collections
                // without a registered schema inverts the CI gate this RPC
                // exists to provide.
                List<SchemaChange> changes = new ArrayList<>(res.rejections());
                if (changes.isEmpty()) {
                    changes.add(SchemaChange.newBuilder()
                            .setClassification(SchemaChange.Classification.CLASSIFICATION_WIRE_SAFE_LIVE)
                            .setField("")
                            .setCode(registeredSchemas.containsKey(request.getCollection())
                                    ? "SCHEMA_OK" : "NEW_SCHEMA")
                            .setDescription("Dry-run compilation succeeded")
                            .build());
                }

                return ValidateSchemaResponse.newBuilder()
                        .setCompiled(protoSchema)
                        .addAllChanges(changes)
                        .build();
            } catch (Exception e) {
                LOG.errorf(e, "Validation failed for collection %s", request.getCollection());
                throw io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("Schema validation failed: " + e.getMessage())
                        .asRuntimeException();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<PutExperimentResponse> putExperiment(PutExperimentRequest request) {
        return Uni.createFrom().item(() -> {
            Experiment exp = request.getExperiment();
            experiments.put(exp.getName(), exp);
            return PutExperimentResponse.newBuilder().setExperiment(exp).build();
        });
    }

    @Override
    public Uni<DeleteExperimentResponse> deleteExperiment(DeleteExperimentRequest request) {
        return Uni.createFrom().item(() -> {
            experiments.remove(request.getName());
            return DeleteExperimentResponse.newBuilder().build();
        });
    }

    @Override
    public Uni<ListExperimentsResponse> listExperiments(ListExperimentsRequest request) {
        return Uni.createFrom().item(() -> ListExperimentsResponse.newBuilder()
                .addAllExperiments(experiments.values())
                .build());
    }

    @Override
    public Uni<PutRankingProfileResponse> putRankingProfile(PutRankingProfileRequest request) {
        return Uni.createFrom().item(() -> {
            RankingProfile profile = request.getProfile();
            rankingProfiles.put(profile.getName(), profile);
            return PutRankingProfileResponse.newBuilder().setProfile(profile).build();
        });
    }

    @Override
    public Uni<DeleteRankingProfileResponse> deleteRankingProfile(DeleteRankingProfileRequest request) {
        return Uni.createFrom().item(() -> {
            rankingProfiles.remove(request.getName());
            return DeleteRankingProfileResponse.newBuilder().build();
        });
    }

    @Override
    public Uni<ListRankingProfilesResponse> listRankingProfiles(ListRankingProfilesRequest request) {
        return Uni.createFrom().item(() -> ListRankingProfilesResponse.newBuilder()
                .addAllProfiles(rankingProfiles.values())
                .build());
    }

    @Override
    public Multi<ExperimentEvent> watchExperiments(WatchExperimentsRequest request) {
        return Multi.createFrom().emitter(emitter -> {
            if (request.getSendInitialState()) {
                for (Experiment exp : experiments.values()) {
                    emitter.emit(ExperimentEvent.newBuilder()
                            .setType(ExperimentEvent.Type.TYPE_PUT)
                            .setExperiment(exp)
                            .setRevision(1)
                            .build());
                }
            }
            emitter.complete();
        });
    }

    private static VectorSimilarity toProtoSimilarity(VectorSimilarityFunction sim) {
        return switch (sim) {
            case COSINE -> VectorSimilarity.VECTOR_SIMILARITY_COSINE;
            case DOT_PRODUCT -> VectorSimilarity.VECTOR_SIMILARITY_DOT_PRODUCT;
            case EUCLIDEAN -> VectorSimilarity.VECTOR_SIMILARITY_EUCLIDEAN;
            case MAXIMUM_INNER_PRODUCT -> VectorSimilarity.VECTOR_SIMILARITY_MAX_INNER_PRODUCT;
        };
    }

    private Collection toProtoCollection(CollectionConfig config, CollectionSchema schema) {
        Collection.Builder builder = Collection.newBuilder()
                .setName(config.name())
                .setNumShards(config.numShards())
                .setRevision(1)
                .setStats(CollectionStats.newBuilder()
                        .setDocCount(collectionManager.getTotalDocCount(config.name()))
                        .build());

        if (schema != null) {
            builder.setSchema(schema);
        } else {
            builder.setSchema(CollectionSchema.newBuilder()
                    .addFields(FieldSchema.newBuilder()
                            .setName("vector")
                            .setDenseVector(DenseVectorFieldSchema.newBuilder()
                                    .setDims(config.vectorDimension())
                                    .setSimilarity(toProtoSimilarity(config.similarity()))
                                    .build())
                            .build())
                    .build());
        }

        schemaStore.get(config.name()).ifPresent(stored -> builder.setSchemaPin(stored.toPin()));
        if (config.documentCentric()) {
            builder.setChunkMessage(config.chunkMessage());
        }

        for (int i = 0; i < config.numShards(); i++) {
            builder.addShards(ShardInfo.newBuilder()
                    .setShardId(i)
                    .setDocCount(collectionManager.getDocCount(config.name(), i))
                    .build());
        }
        return builder.build();
    }
}
