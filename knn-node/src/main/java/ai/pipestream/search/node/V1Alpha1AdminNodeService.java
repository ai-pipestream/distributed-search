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
                registeredSchemas.put(name, schema);
            }

            int numShards = request.getNumShards() > 0 ? request.getNumShards() : 1;

            try {
                CollectionConfig config = collectionManager.createCollection(
                        name, vectorDimension, similarity, numShards, ""
                );
                return CreateCollectionResponse.newBuilder()
                        .setCollection(toProtoCollection(config, registeredSchemas.get(name)))
                        .build();
            } catch (Exception e) {
                LOG.errorf(e, "Failed to create collection %s", name);
                throw new RuntimeException(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<DropCollectionResponse> dropCollection(DropCollectionRequest request) {
        return Uni.createFrom().item(() -> {
            try {
                boolean deleted = collectionManager.deleteCollection(request.getName());
                if (!deleted) {
                    throw new IllegalArgumentException("Collection not found: " + request.getName());
                }
                registeredSchemas.remove(request.getName());
                return DropCollectionResponse.newBuilder().build();
            } catch (Exception e) {
                LOG.errorf(e, "Failed to drop collection %s", request.getName());
                throw new RuntimeException(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<GetCollectionResponse> getCollection(GetCollectionRequest request) {
        return Uni.createFrom().item(() -> {
            CollectionConfig config = collectionManager.getConfig(request.getName());
            if (config == null) {
                throw new IllegalArgumentException("Collection not found: " + request.getName());
            }
            return GetCollectionResponse.newBuilder()
                    .setCollection(toProtoCollection(config, registeredSchemas.get(request.getName())))
                    .build();
        });
    }

    @Override
    public Uni<ListCollectionsResponse> listCollections(ListCollectionsRequest request) {
        return Uni.createFrom().item(() -> {
            ListCollectionsResponse.Builder builder = ListCollectionsResponse.newBuilder();
            for (CollectionConfig config : collectionManager.listCollections()) {
                builder.addCollections(toProtoCollection(config, registeredSchemas.get(config.name())));
            }
            return builder.build();
        });
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

    @Override
    public Uni<RegisterSchemaResponse> registerSchema(RegisterSchemaRequest request) {
        return Uni.createFrom().item(() -> {
            CollectionConfig config = collectionManager.getConfig(request.getCollection());
            if (config == null) {
                throw new IllegalArgumentException("Collection not found: " + request.getCollection());
            }

            try {
                SchemaCompiler.Result result = SchemaCompiler.compile(
                        request.getSource().getDescriptorSet(),
                        request.getSource().getRootMessage()
                );
                CompiledSchema compiled = result.schema();
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
            } catch (Exception e) {
                LOG.errorf(e, "Failed to register schema for %s", request.getCollection());
                throw new RuntimeException(e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<ValidateSchemaResponse> validateSchema(ValidateSchemaRequest request) {
        return Uni.createFrom().item(() -> {
            try {
                SchemaCompiler.Result res = SchemaCompiler.compile(
                        request.getSource().getDescriptorSet(),
                        request.getSource().getRootMessage()
                );
                CompiledSchema proposed = res.schema();
                CollectionSchema protoSchema = proposed.toProto();

                CollectionSchema currentProto = registeredSchemas.get(request.getCollection());
                List<SchemaChange> changes = new ArrayList<>();

                if (currentProto != null) {
                    changes.addAll(res.rejections());
                } else {
                    changes.add(SchemaChange.newBuilder()
                            .setClassification(SchemaChange.Classification.CLASSIFICATION_WIRE_SAFE_LIVE)
                            .setField("")
                            .setCode("NEW_SCHEMA")
                            .setDescription("Dry-run compilation succeeded for new collection schema")
                            .build());
                }

                return ValidateSchemaResponse.newBuilder()
                        .setCompiled(protoSchema)
                        .addAllChanges(changes)
                        .build();
            } catch (Exception e) {
                LOG.errorf(e, "Validation failed for collection %s", request.getCollection());
                throw new RuntimeException(e);
            }
        });
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
                                    .build())
                            .build())
                    .build());
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
