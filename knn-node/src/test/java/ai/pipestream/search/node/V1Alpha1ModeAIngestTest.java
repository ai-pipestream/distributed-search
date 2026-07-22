package ai.pipestream.search.node;

import ai.pipestream.search.v1alpha1.*;
import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * P6 proof: mode A end to end. The server chunks the payload's TEXT field
 * with the pinned sentence-packed spec, embeds every chunk with the pinned
 * model, places the blocks, and the chunks come back with exact offsets and
 * (opt-in) stored text. Register-time gating: a dims mismatch between the
 * model and the representation fails at RegisterSchema, never as garbage at
 * query time.
 */
@QuarkusTest
@QuarkusTestResource(value = KnnNodeTest.IndexResource.class, restrictToAnnotatedClass = true)
public class V1Alpha1ModeAIngestTest {

    private static final String BODY =
            "The quick brown fox jumps over the lazy dog. "
                    + "Pack my box with five dozen liquor jugs. "
                    + "Sphinx of black quartz judge my vow. "
                    + "How vexingly quick daft zebras jump.";

    @Inject
    @io.quarkus.grpc.GrpcService
    CollectionAdminService adminService;

    @Inject
    @io.quarkus.grpc.GrpcService
    IndexService indexService;

    @Inject
    @io.quarkus.grpc.GrpcService
    SearchService searchService;

    // ------------------------------------------------------------------
    // Schema fixture: t.Doc { title TEXT; body TEXT + derived VECTOR rep }
    // ------------------------------------------------------------------

    private static FieldOptions searchField(SearchField sf) {
        return FieldOptions.newBuilder().setExtension(SchemaOptionsProto.field, sf).build();
    }

    private static void addOptionalString(DescriptorProto.Builder message, String name, int tag,
                                          SearchField options) {
        int oneofIndex = message.getOneofDeclCount();
        message.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_" + name));
        message.addField(FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(tag)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setProto3Optional(true).setOneofIndex(oneofIndex)
                .setOptions(searchField(options)));
    }

    private static FileDescriptorProto schemaFile(int declaredDims, String... nlpLayers) {
        SearchField body = SearchField.newBuilder()
                .setType(FieldType.FIELD_TYPE_TEXT).setStored(true)
                .addRepresentations(Representation.newBuilder()
                        .setName("chunks")
                        .setType(FieldType.FIELD_TYPE_VECTOR)
                        .setVector(VectorOptions.newBuilder()
                                .setDims(declaredDims)
                                .setSimilarity(VectorOptions.Similarity.SIMILARITY_COSINE))
                        .setDerive(ChunkAndEmbed.newBuilder()
                                .setModel(TestEmbeddingProvider.MODEL)
                                .setStoreChunkText(true)
                                .addAllNlpLayers(java.util.List.of(nlpLayers))
                                .setSpec(ChunkSpec.newBuilder()
                                        .setTargetTokens(10)
                                        .setOverlapTokens(1)
                                        .setMinTokens(1)
                                        .setMaxTokens(100))))
                .build();

        DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
        addOptionalString(doc, "title", 1,
                SearchField.newBuilder().setType(FieldType.FIELD_TYPE_TEXT).setStored(true).build());
        addOptionalString(doc, "body", 2, body);

        DescriptorProto.Builder chunk = DescriptorProto.newBuilder().setName("DocChunk");
        chunk.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_text"));
        chunk.addField(FieldDescriptorProto.newBuilder()
                .setName("text").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setProto3Optional(true).setOneofIndex(0));

        return FileDescriptorProto.newBuilder()
                .setName("modea_schema.proto").setPackage("t").setSyntax("proto3")
                .addMessageType(doc.build())
                .addMessageType(chunk.build())
                .build();
    }

    private static FileDescriptorSet wireSet(int declaredDims, String... nlpLayers) throws Exception {
        byte[] bytes = FileDescriptorSet.newBuilder()
                .addFile(schemaFile(declaredDims, nlpLayers)).build().toByteArray();
        return FileDescriptorSet.parseFrom(bytes);
    }

    private static Any payload(String title, String body) throws Exception {
        Descriptors.FileDescriptor file = Descriptors.FileDescriptor.buildFrom(
                schemaFile(4), new Descriptors.FileDescriptor[0]);
        Descriptors.Descriptor doc = file.findMessageTypeByName("Doc");
        DynamicMessage message = DynamicMessage.newBuilder(doc)
                .setField(doc.findFieldByName("title"), title)
                .setField(doc.findFieldByName("body"), body)
                .build();
        return Any.newBuilder()
                .setTypeUrl("type.googleapis.com/t.Doc")
                .setValue(message.toByteString())
                .build();
    }

    @Test
    public void dimsMismatchFailsAtRegisterNotAtQuery() throws Exception {
        String collection = "modea-dims-mismatch";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection).setNumShards(1).build()).await().indefinitely();

        io.grpc.StatusRuntimeException ex = Assertions.assertThrows(
                io.grpc.StatusRuntimeException.class,
                () -> adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                        .setCollection(collection)
                        .setSource(SchemaSource.newBuilder()
                                .setDescriptorSet(wireSet(8))   // model produces 4
                                .setRootMessage("t.Doc")
                                .setChunkMessage("t.DocChunk"))
                        .build()).await().indefinitely());
        Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        Assertions.assertTrue(ex.getStatus().getDescription().contains("dims"),
                "the failure must name the dims mismatch: " + ex.getStatus().getDescription());

        adminService.dropCollection(DropCollectionRequest.newBuilder()
                .setName(collection).build()).await().indefinitely();
    }

    @Test
    public void serverChunkingEndToEnd() throws Exception {
        String collection = "modea-e2e";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection).setNumShards(2).build()).await().indefinitely();
        RegisterSchemaResponse registered = adminService.registerSchema(
                RegisterSchemaRequest.newBuilder()
                        .setCollection(collection)
                        .setSource(SchemaSource.newBuilder()
                                .setDescriptorSet(wireSet(4))
                                .setRootMessage("t.Doc")
                                .setChunkMessage("t.DocChunk"))
                        .build()).await().indefinitely();
        Assertions.assertFalse(registered.getCollection().getSchemaPin()
                .getPlanDigest().isEmpty(), "the plan digest must cover the derivation");

        // --- mode A write: the server chunks and embeds ---
        List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().item(
                BulkIndexRequest.newBuilder().setParentDocument(IndexParentDocument.newBuilder()
                        .setClientSeq(1)
                        .setCollection(collection)
                        .setDocId("pangrams")
                        .setPayload(payload("pangram collection", BODY))
                        .setServerChunking(ServerChunking.getDefaultInstance())
                        .build()).build()
        )).collect().asList().await().indefinitely();

        ParentAck ack = responses.stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.PARENT_ACK)
                .findFirst().orElseThrow().getParentAck();
        Assertions.assertEquals(0, ack.getStatus().getCode(),
                "mode A must succeed: " + ack.getStatus().getMessage());
        Assertions.assertEquals(4, ack.getChunkCount(),
                "four pangram sentences, target 10 tokens: one chunk per sentence");
        Assertions.assertEquals(4, ack.getChunkCreditsConsumed());

        // --- retrieval: exact offsets, ordinal order, no payload on chunks ---
        GetDocumentResponse got = indexService.getDocument(GetDocumentRequest.newBuilder()
                .setCollection(collection).setDocId("pangrams").setIncludeChunks(true)
                .build()).await().indefinitely();
        Assertions.assertTrue(got.getFound());
        Assertions.assertEquals(4, got.getChunksCount());
        int previousEnd = 0;
        for (int i = 0; i < 4; i++) {
            Chunk chunk = got.getChunks(i);
            Assertions.assertEquals(i, chunk.getOrdinal());
            Assertions.assertEquals(previousEnd, chunk.getStartOffset(),
                    "chunks tile the body (overlap 1 token < one sentence)");
            Assertions.assertTrue(chunk.getEndOffset() > chunk.getStartOffset());
            previousEnd = chunk.getEndOffset();
        }
        Assertions.assertEquals(BODY.length(), previousEnd, "coverage ends at body length");

        // --- search: query with the exact embedding of one chunk's text ---
        String sphinxSentence = BODY.substring(
                got.getChunks(2).getStartOffset(), got.getChunks(2).getEndOffset());
        float[] queryVector = TestEmbeddingProvider.embedOne(sphinxSentence);
        Vector.Builder queryProto = Vector.newBuilder();
        for (float f : queryVector) {
            queryProto.addValues(f);
        }

        List<SearchResponse> searchResponses = searchService.search(SearchRequest.newBuilder()
                .setCollection(collection)
                .setSize(5)
                .setChunksPerHit(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("body#chunks")
                        .setVector(queryProto)
                        .setK(5)
                        .setDocumentCentric(true)))
                .build()).collect().asList().await().indefinitely();

        List<Hit> hits = searchResponses.stream()
                .filter(r -> r.getFrameCase() == SearchResponse.FrameCase.HIT)
                .map(SearchResponse::getHit)
                .toList();
        Assertions.assertEquals(1, hits.size());
        Hit hit = hits.get(0);
        Assertions.assertEquals("pangrams", hit.getDocId());
        Assertions.assertEquals(4, hit.getChunksCount(), "every chunk exactly scored");

        ChunkHit best = hit.getChunks(0);
        Assertions.assertEquals(2, best.getOrdinal(),
                "the chunk whose embedding IS the query vector must rank first");
        Assertions.assertEquals(1.0f, best.getScore(), 1e-5f,
                "identical vector under COSINE scores 1.0");
        Assertions.assertEquals(sphinxSentence, best.getText(),
                "store_chunk_text: true must surface the chunk text on ChunkHit");
        Assertions.assertEquals(sphinxSentence,
                BODY.substring(best.getStartOffset(), best.getEndOffset()),
                "offsets must reconstruct the chunk from the parent text");

        // --- determinism: replay at generation 2 produces the same chunking ---
        List<BulkIndexResponse> replay = indexService.bulkIndex(Multi.createFrom().item(
                BulkIndexRequest.newBuilder().setParentDocument(IndexParentDocument.newBuilder()
                        .setClientSeq(2)
                        .setCollection(collection)
                        .setDocId("pangrams")
                        .setGeneration(2)
                        .setPayload(payload("pangram collection", BODY))
                        .setServerChunking(ServerChunking.getDefaultInstance())
                        .build()).build()
        )).collect().asList().await().indefinitely();
        ParentAck replayAck = replay.stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.PARENT_ACK)
                .findFirst().orElseThrow().getParentAck();
        Assertions.assertEquals(0, replayAck.getStatus().getCode());
        Assertions.assertEquals(4, replayAck.getChunkCount(),
                "the same text must chunk identically, forever");

        adminService.dropCollection(DropCollectionRequest.newBuilder()
                .setName(collection).build()).await().indefinitely();
    }

    @Test
    public void nlpLayersArePersistedAndReturnedOnHits() throws Exception {
        String collection = "modea-nlp";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection).setNumShards(1).build()).await().indefinitely();
        adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                .setCollection(collection)
                .setSource(SchemaSource.newBuilder()
                        .setDescriptorSet(wireSet(4, "tokens"))
                        .setRootMessage("t.Doc")
                        .setChunkMessage("t.DocChunk"))
                .build()).await().indefinitely();

        List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().item(
                BulkIndexRequest.newBuilder().setParentDocument(IndexParentDocument.newBuilder()
                        .setClientSeq(1)
                        .setCollection(collection)
                        .setDocId("annotated")
                        .setPayload(payload("annotated doc", BODY))
                        .setServerChunking(ServerChunking.getDefaultInstance())
                        .build()).build()
        )).collect().asList().await().indefinitely();
        ParentAck ack = responses.stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.PARENT_ACK)
                .findFirst().orElseThrow().getParentAck();
        Assertions.assertEquals(0, ack.getStatus().getCode(),
                "nlp-annotated mode A must succeed: " + ack.getStatus().getMessage());

        // GetDocument: every chunk carries token spans in PARENT-text offsets.
        GetDocumentResponse got = indexService.getDocument(GetDocumentRequest.newBuilder()
                .setCollection(collection).setDocId("annotated").setIncludeChunks(true)
                .build()).await().indefinitely();
        Assertions.assertTrue(got.getFound());
        for (Chunk chunk : got.getChunksList()) {
            Assertions.assertTrue(chunk.getNlpCount() > 0,
                    "every chunk must carry its token annotations");
            for (NlpSpan span : chunk.getNlpList()) {
                Assertions.assertEquals("tokens", span.getLayer());
                Assertions.assertTrue(span.getStart() < chunk.getEndOffset()
                                && span.getEnd() > chunk.getStartOffset(),
                        "a stored span must overlap its chunk");
                Assertions.assertEquals(BODY.substring(span.getStart(), span.getEnd()),
                        span.getValue(),
                        "offsets are in the ORIGINAL parent text, never chunk-local");
            }
        }

        // Search: the winning ChunkHit surfaces the same annotations.
        String sentence = BODY.substring(
                got.getChunks(1).getStartOffset(), got.getChunks(1).getEndOffset());
        float[] queryVector = TestEmbeddingProvider.embedOne(sentence);
        Vector.Builder queryProto = Vector.newBuilder();
        for (float f : queryVector) {
            queryProto.addValues(f);
        }
        List<SearchResponse> searchResponses = searchService.search(SearchRequest.newBuilder()
                .setCollection(collection)
                .setSize(5)
                .setChunksPerHit(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("body#chunks")
                        .setVector(queryProto)
                        .setK(5)
                        .setDocumentCentric(true)))
                .build()).collect().asList().await().indefinitely();
        Hit hit = searchResponses.stream()
                .filter(r -> r.getFrameCase() == SearchResponse.FrameCase.HIT)
                .map(SearchResponse::getHit)
                .findFirst().orElseThrow();
        ChunkHit best = hit.getChunks(0);
        Assertions.assertTrue(best.getNlpCount() > 0,
                "search hits must carry the persisted annotations");
        for (NlpSpan span : best.getNlpList()) {
            Assertions.assertEquals(BODY.substring(span.getStart(), span.getEnd()),
                    span.getValue(), "hit annotations highlight the original text");
        }

        adminService.dropCollection(DropCollectionRequest.newBuilder()
                .setName(collection).build()).await().indefinitely();
    }

    @Test
    public void nlpLayerValidationFailsAtRegistration() throws Exception {
        String collection = "modea-nlp-invalid";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection).setNumShards(1).build()).await().indefinitely();

        io.grpc.StatusRuntimeException unsupported = Assertions.assertThrows(
                io.grpc.StatusRuntimeException.class,
                () -> adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                        .setCollection(collection)
                        .setSource(SchemaSource.newBuilder()
                                .setDescriptorSet(wireSet(4, "coref"))
                                .setRootMessage("t.Doc")
                                .setChunkMessage("t.DocChunk"))
                        .build()).await().indefinitely());
        Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT,
                unsupported.getStatus().getCode());
        Assertions.assertTrue(unsupported.getStatus().getDescription().contains("coref"));

        io.grpc.StatusRuntimeException unpinned = Assertions.assertThrows(
                io.grpc.StatusRuntimeException.class,
                () -> adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                        .setCollection(collection)
                        .setSource(SchemaSource.newBuilder()
                                .setDescriptorSet(wireSet(4, "sentences"))
                                .setRootMessage("t.Doc")
                                .setChunkMessage("t.DocChunk"))
                        .build()).await().indefinitely());
        Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT,
                unpinned.getStatus().getCode());
        Assertions.assertTrue(unpinned.getStatus().getDescription().contains("boundary pin"),
                "the sentences layer without an opennlp pin must name the missing pin: "
                        + unpinned.getStatus().getDescription());

        adminService.dropCollection(DropCollectionRequest.newBuilder()
                .setName(collection).build()).await().indefinitely();
    }

    @Test
    public void serverChunkingRequiresADerivableRepresentation() throws Exception {
        // A doc-centric schema WITHOUT derive: server_chunking must be
        // FAILED_PRECONDITION, not silently chunk-less.
        String collection = "modea-no-derive";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection).setNumShards(1).build()).await().indefinitely();
        adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                .setCollection(collection)
                .setSource(SchemaSource.newBuilder()
                        .setDescriptorSet(DocCentricTestSchema.wireDescriptorSet())
                        .setRootMessage("t.Doc")
                        .setChunkMessage("t.DocChunk"))
                .build()).await().indefinitely();

        Descriptors.FileDescriptor file = DocCentricTestSchema.buildFile();
        List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().item(
                BulkIndexRequest.newBuilder().setParentDocument(IndexParentDocument.newBuilder()
                        .setClientSeq(1)
                        .setCollection(collection)
                        .setDocId("d1")
                        .setPayload(DocCentricTestSchema.docPayload(file, "a title"))
                        .setServerChunking(ServerChunking.getDefaultInstance())
                        .build()).build()
        )).collect().asList().await().indefinitely();
        ParentAck ack = responses.stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.PARENT_ACK)
                .findFirst().orElseThrow().getParentAck();
        Assertions.assertEquals(9, ack.getStatus().getCode(),
                "FAILED_PRECONDITION when the schema declares no derivation");

        adminService.dropCollection(DropCollectionRequest.newBuilder()
                .setName(collection).build()).await().indefinitely();
    }
}
