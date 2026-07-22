package ai.pipestream.search.node;

import ai.pipestream.search.v1alpha1.*;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
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

import java.util.ArrayList;
import java.util.List;

/**
 * P2 proof, end to end over the gRPC services: document-centric mode B
 * ingest (client-supplied chunks in Any payloads), generation replacement,
 * typed retrieval, and flat typed_document ingest against the registered
 * schema.
 */
@QuarkusTest
@QuarkusTestResource(value = KnnNodeTest.IndexResource.class, restrictToAnnotatedClass = true)
public class V1Alpha1DocCentricIngestTest {

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
    // Schema fixture: t.Doc (root, annotated) + t.DocChunk (chunk message)
    // ------------------------------------------------------------------

    private static FieldOptions searchField(SearchField sf) {
        return FieldOptions.newBuilder().setExtension(SchemaOptionsProto.field, sf).build();
    }

    private static FileDescriptorProto schemaFile() {
        DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
        doc.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_title"));
        doc.addField(FieldDescriptorProto.newBuilder()
                .setName("title").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setProto3Optional(true).setOneofIndex(0)
                .setOptions(searchField(SearchField.newBuilder()
                        .setType(FieldType.FIELD_TYPE_TEXT).setStored(true).build())));
        doc.addField(FieldDescriptorProto.newBuilder()
                .setName("embedding").setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_FLOAT)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .setOptions(searchField(SearchField.newBuilder()
                        .setType(FieldType.FIELD_TYPE_VECTOR)
                        .setVector(VectorOptions.newBuilder().setDims(4)
                                .setSimilarity(VectorOptions.Similarity.SIMILARITY_COSINE))
                        .build())));

        DescriptorProto.Builder chunk = DescriptorProto.newBuilder().setName("DocChunk");
        chunk.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_text"));
        chunk.addField(FieldDescriptorProto.newBuilder()
                .setName("text").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setProto3Optional(true).setOneofIndex(0));

        return FileDescriptorProto.newBuilder()
                .setName("doc_schema.proto").setPackage("t").setSyntax("proto3")
                .addMessageType(doc.build())
                .addMessageType(chunk.build())
                .build();
    }

    /** Serialize + naive re-parse: exactly what the gRPC boundary does. */
    private static FileDescriptorSet wireDescriptorSet() throws Exception {
        byte[] bytes = FileDescriptorSet.newBuilder().addFile(schemaFile()).build().toByteArray();
        return FileDescriptorSet.parseFrom(bytes);
    }

    private static Descriptors.FileDescriptor buildFile() throws Exception {
        return Descriptors.FileDescriptor.buildFrom(schemaFile(), new Descriptors.FileDescriptor[0]);
    }

    private static Any docPayload(Descriptors.FileDescriptor file, String title) {
        Descriptors.Descriptor doc = file.findMessageTypeByName("Doc");
        DynamicMessage message = DynamicMessage.newBuilder(doc)
                .setField(doc.findFieldByName("title"), title)
                .build();
        return Any.newBuilder()
                .setTypeUrl("type.googleapis.com/t.Doc")
                .setValue(message.toByteString())
                .build();
    }

    private static Any chunkPayload(Descriptors.FileDescriptor file, String text) {
        Descriptors.Descriptor chunk = file.findMessageTypeByName("DocChunk");
        DynamicMessage message = DynamicMessage.newBuilder(chunk)
                .setField(chunk.findFieldByName("text"), text)
                .build();
        return Any.newBuilder()
                .setTypeUrl("type.googleapis.com/t.DocChunk")
                .setValue(message.toByteString())
                .build();
    }

    private static Chunk chunk(Any payload, float... vector) {
        Vector.Builder v = Vector.newBuilder();
        for (float f : vector) {
            v.addValues(f);
        }
        return Chunk.newBuilder().setPayload(payload).setVector(v.build()).build();
    }

    private void createAndRegister(String collection, boolean documentCentric) throws Exception {
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection)
                .setNumShards(1)
                .setSchema(CollectionSchema.newBuilder()
                        .addFields(FieldSchema.newBuilder()
                                .setName("embedding")
                                .setDenseVector(DenseVectorFieldSchema.newBuilder()
                                        .setDims(4)
                                        .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE)
                                        .build())
                                .build())
                        .build())
                .build()).await().indefinitely();

        SchemaSource.Builder source = SchemaSource.newBuilder()
                .setDescriptorSet(wireDescriptorSet())
                .setRootMessage("t.Doc");
        if (documentCentric) {
            source.setChunkMessage("t.DocChunk");
        }
        RegisterSchemaResponse registered = adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                .setCollection(collection)
                .setSource(source)
                .build()).await().indefinitely();
        Assertions.assertFalse(registered.getCollection().getSchemaPin()
                .getDescriptorDigest().isEmpty(), "the registered collection must carry a pin");
    }

    private ParentAck sendParent(IndexParentDocument parent) {
        List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().item(
                BulkIndexRequest.newBuilder().setParentDocument(parent).build()
        )).collect().asList().await().indefinitely();
        return responses.stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.PARENT_ACK)
                .findFirst().orElseThrow().getParentAck();
    }

    @Test
    public void modeBSuppliedChunksEndToEnd() throws Exception {
        String collection = "doccentric-modeb";
        createAndRegister(collection, true);
        Descriptors.FileDescriptor file = buildFile();

        // --- first write: server-assigned generation ---
        ParentAck ack = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(1)
                .setCollection(collection)
                .setDocId("article-1")
                .setPayload(docPayload(file, "Block joins in practice"))
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(chunk(chunkPayload(file, "chunk zero"), 1f, 0f, 0f, 0f))
                        .addChunks(chunk(chunkPayload(file, "chunk one"), 0f, 1f, 0f, 0f))
                        .addChunks(chunk(chunkPayload(file, "chunk two"), 0f, 0f, 1f, 0f))
                        .build())
                .build());

        Assertions.assertEquals(0, ack.getStatus().getCode(),
                "mode B write must succeed: " + ack.getStatus().getMessage());
        Assertions.assertEquals(1, ack.getGeneration(), "server assigns generation 1");
        Assertions.assertEquals(3, ack.getChunkCount());
        Assertions.assertEquals(1, ack.getBlocksCount());
        Assertions.assertEquals(3, ack.getChunkCreditsConsumed());
        Assertions.assertFalse(ack.getResolvedSchema().getDescriptorDigest().isEmpty());

        // --- typed retrieval ---
        GetDocumentResponse got = indexService.getDocument(GetDocumentRequest.newBuilder()
                .setCollection(collection)
                .setDocId("article-1")
                .setIncludeChunks(true)
                .build()).await().indefinitely();
        Assertions.assertTrue(got.getFound());
        Assertions.assertEquals("t.Doc",
                got.getTypedDocument().getTypeUrl().substring(
                        got.getTypedDocument().getTypeUrl().lastIndexOf('/') + 1));
        DynamicMessage parent = DynamicMessage.parseFrom(
                file.findMessageTypeByName("Doc"), got.getTypedDocument().getValue());
        Assertions.assertEquals("Block joins in practice",
                parent.getField(file.findMessageTypeByName("Doc").findFieldByName("title")));

        Assertions.assertEquals(3, got.getChunksCount(), "include_chunks returns every chunk");
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(i, got.getChunks(i).getOrdinal(), "chunks in ordinal order");
            DynamicMessage chunkMessage = DynamicMessage.parseFrom(
                    file.findMessageTypeByName("DocChunk"), got.getChunks(i).getPayload().getValue());
            Assertions.assertEquals("chunk " + List.of("zero", "one", "two").get(i),
                    chunkMessage.getField(
                            file.findMessageTypeByName("DocChunk").findFieldByName("text")));
        }

        // --- same-generation replay is a no-op ---
        ParentAck replay = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(2)
                .setCollection(collection)
                .setDocId("article-1")
                .setGeneration(1)
                .setPayload(docPayload(file, "Block joins in practice"))
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(chunk(chunkPayload(file, "chunk zero"), 1f, 0f, 0f, 0f))
                        .build())
                .build());
        Assertions.assertEquals(6, replay.getStatus().getCode(), "ALREADY_EXISTS on replay");

        // --- generation 2 replaces the whole block ---
        ParentAck gen2 = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(3)
                .setCollection(collection)
                .setDocId("article-1")
                .setGeneration(2)
                .setPayload(docPayload(file, "Block joins, revised"))
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(chunk(chunkPayload(file, "revised zero"), 1f, 1f, 0f, 0f))
                        .addChunks(chunk(chunkPayload(file, "revised one"), 0f, 1f, 1f, 0f))
                        .build())
                .build());
        Assertions.assertEquals(0, gen2.getStatus().getCode());
        Assertions.assertEquals(2, gen2.getChunkCount());
        Assertions.assertEquals(4, gen2.getBlocks(0).getPurgedDocs(), "3 chunks + stub purged");

        GetDocumentResponse afterRewrite = indexService.getDocument(GetDocumentRequest.newBuilder()
                .setCollection(collection).setDocId("article-1").setIncludeChunks(true)
                .build()).await().indefinitely();
        Assertions.assertEquals(2, afterRewrite.getChunksCount());

        // --- delete the parent everywhere ---
        DeleteParentDocumentResponse deleted = indexService.deleteParentDocument(
                DeleteParentDocumentRequest.newBuilder()
                        .setCollection(collection).setDocId("article-1").build())
                .await().indefinitely();
        Assertions.assertEquals(1, deleted.getBlocksDeleted());

        GetDocumentResponse gone = indexService.getDocument(GetDocumentRequest.newBuilder()
                .setCollection(collection).setDocId("article-1").build()).await().indefinitely();
        Assertions.assertFalse(gone.getFound());

        adminService.dropCollection(DropCollectionRequest.newBuilder()
                .setName(collection).build()).await().indefinitely();
    }

    @Test
    public void parentIngestValidations() throws Exception {
        String collection = "doccentric-validate";
        createAndRegister(collection, true);
        Descriptors.FileDescriptor file = buildFile();

        // Missing doc_id.
        ParentAck noId = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(1).setCollection(collection)
                .setPayload(docPayload(file, "x"))
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(chunk(chunkPayload(file, "c"), 1f, 0f, 0f, 0f)))
                .build());
        Assertions.assertEquals(3, noId.getStatus().getCode());

        // Chunk without a vector.
        ParentAck noVector = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(2).setCollection(collection).setDocId("d1")
                .setPayload(docPayload(file, "x"))
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(Chunk.newBuilder().setPayload(chunkPayload(file, "c")).build()))
                .build());
        Assertions.assertEquals(3, noVector.getStatus().getCode());

        // Wrong payload type: the type_url confirms, never selects.
        ParentAck wrongType = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(3).setCollection(collection).setDocId("d2")
                .setPayload(Any.newBuilder()
                        .setTypeUrl("type.googleapis.com/t.Imposter")
                        .setValue(ByteString.EMPTY)
                        .build())
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(chunk(chunkPayload(file, "c"), 1f, 0f, 0f, 0f)))
                .build());
        Assertions.assertEquals(3, wrongType.getStatus().getCode());

        // Stale pin assertion.
        ParentAck stalePin = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(4).setCollection(collection).setDocId("d3")
                .setSchema(SchemaPin.newBuilder()
                        .setDescriptorDigest(ByteString.copyFrom(new byte[32]))
                        .build())
                .setPayload(docPayload(file, "x"))
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(chunk(chunkPayload(file, "c"), 1f, 0f, 0f, 0f)))
                .build());
        Assertions.assertEquals(9, stalePin.getStatus().getCode(), "FAILED_PRECONDITION on pin mismatch");

        // Parent frames against a flat collection.
        String flat = "doccentric-flatreject";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(flat).setNumShards(1).build()).await().indefinitely();
        ParentAck flatReject = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(5).setCollection(flat).setDocId("d")
                .setPayload(docPayload(file, "x"))
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(chunk(chunkPayload(file, "c"), 1f, 0f, 0f, 0f)))
                .build());
        Assertions.assertEquals(9, flatReject.getStatus().getCode());

        adminService.dropCollection(DropCollectionRequest.newBuilder().setName(collection).build())
                .await().indefinitely();
        adminService.dropCollection(DropCollectionRequest.newBuilder().setName(flat).build())
                .await().indefinitely();
    }

    /** Flat typed_document ingest + knn search through the registered schema. */
    @Test
    public void flatTypedDocumentIngestAndSearch() throws Exception {
        String collection = "flat-typed-doc";
        createAndRegister(collection, false);
        Descriptors.FileDescriptor file = buildFile();
        Descriptors.Descriptor docType = file.findMessageTypeByName("Doc");

        List<BulkIndexRequest> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DynamicMessage message = DynamicMessage.newBuilder(docType)
                    .setField(docType.findFieldByName("title"), "typed doc " + i)
                    .addRepeatedField(docType.findFieldByName("embedding"), 1.0f)
                    .addRepeatedField(docType.findFieldByName("embedding"), i * 0.5f)
                    .addRepeatedField(docType.findFieldByName("embedding"), 0.0f)
                    .addRepeatedField(docType.findFieldByName("embedding"), 0.0f)
                    .build();
            frames.add(BulkIndexRequest.newBuilder().setDocument(IndexDocument.newBuilder()
                    .setClientSeq(i + 1)
                    .setCollection(collection)
                    .setDocId("typed-" + i)
                    .setTypedDocument(Any.newBuilder()
                            .setTypeUrl("type.googleapis.com/t.Doc")
                            .setValue(message.toByteString())
                            .build())
                    .build()).build());
        }

        List<BulkIndexResponse> acks = indexService.bulkIndex(Multi.createFrom().iterable(frames))
                .collect().asList().await().indefinitely();
        long ok = acks.stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.ACK)
                .filter(r -> r.getAck().getStatus().getCode() == 0)
                .count();
        Assertions.assertEquals(3, ok, "typed_document ingest must be accepted, not discarded");

        // The registered schema reaches the read path: knn on 'embedding'.
        List<SearchResponse> responses = searchService.search(SearchRequest.newBuilder()
                .setCollection(collection)
                .setSize(3)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("embedding")
                        .setVector(Vector.newBuilder()
                                .addValues(1f).addValues(0f).addValues(0f).addValues(0f).build())
                        .setK(3)
                        .build()).build())
                .build()).collect().asList().await().indefinitely();

        List<Hit> hits = responses.stream()
                .filter(r -> r.getFrameCase() == SearchResponse.FrameCase.HIT)
                .map(SearchResponse::getHit)
                .toList();
        Assertions.assertEquals(3, hits.size());
        Assertions.assertEquals("typed-0", hits.get(0).getDocId(),
                "typed-0's embedding is closest to the query vector");

        // Unknown fields are now rejected against the registered schema.
        List<BulkIndexResponse> strict = indexService.bulkIndex(Multi.createFrom().item(
                BulkIndexRequest.newBuilder().setDocument(IndexDocument.newBuilder()
                        .setClientSeq(9)
                        .setCollection(collection)
                        .setDocId("bad-field")
                        .addFields(DocumentField.newBuilder().setName("titel")
                                .addValues(FieldValue.newBuilder().setStringValue("typo").build()))
                        .build()).build()
        )).collect().asList().await().indefinitely();
        DocAck strictAck = strict.stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.ACK)
                .findFirst().orElseThrow().getAck();
        Assertions.assertEquals(3, strictAck.getStatus().getCode(),
                "unknown field names must be rejected under DYNAMIC_FIELDS_STRICT");

        adminService.dropCollection(DropCollectionRequest.newBuilder().setName(collection).build())
                .await().indefinitely();
    }
}
