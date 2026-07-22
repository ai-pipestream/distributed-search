package ai.pipestream.search.node;

import ai.pipestream.search.v1alpha1.*;
import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * BulkIndex wire-protocol conformance and typed-field fidelity tests
 * (P0 proofs for the doc-centric groundwork).
 */
@QuarkusTest
@QuarkusTestResource(value = KnnNodeTest.IndexResource.class, restrictToAnnotatedClass = true)
public class V1Alpha1IndexProtocolTest {

    @Inject
    @io.quarkus.grpc.GrpcService
    CollectionAdminService adminService;

    @Inject
    @io.quarkus.grpc.GrpcService
    IndexService indexService;

    private void createCollection(String name, int shards) {
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(name)
                .setNumShards(shards)
                .setSchema(CollectionSchema.newBuilder()
                        .addFields(FieldSchema.newBuilder()
                                .setName("vector")
                                .setDenseVector(DenseVectorFieldSchema.newBuilder()
                                        .setDims(4)
                                        .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE)
                                        .build())
                                .build())
                        .build())
                .build()).await().indefinitely();
    }

    private void drop(String name) {
        adminService.dropCollection(DropCollectionRequest.newBuilder().setName(name).build())
                .await().indefinitely();
    }

    private static BulkIndexRequest doc(IndexDocument document) {
        return BulkIndexRequest.newBuilder().setDocument(document).build();
    }

    private static FieldValue str(String v) {
        return FieldValue.newBuilder().setStringValue(v).build();
    }

    private static FieldValue vec(float... values) {
        Vector.Builder b = Vector.newBuilder();
        for (float f : values) {
            b.addValues(f);
        }
        return FieldValue.newBuilder().setVectorValue(b.build()).build();
    }

    /**
     * The proto mandates: "Server sends: a FlowControl frame first (initial
     * credit grant)" — unconditionally, even when the client never sends
     * BulkOptions. A conforming client that waits for the grant must not
     * deadlock.
     */
    @Test
    public void initialFlowControlIsSentWithoutBulkOptions() {
        String collection = "proto-initial-grant";
        createCollection(collection, 1);
        try {
            List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().item(
                    doc(IndexDocument.newBuilder()
                            .setClientSeq(1)
                            .setCollection(collection)
                            .setDocId("d1")
                            .addFields(DocumentField.newBuilder().setName("vector")
                                    .addValues(vec(1f, 0f, 0f, 0f)))
                            .build())
            )).collect().asList().await().indefinitely();

            Assertions.assertFalse(responses.isEmpty());
            BulkIndexResponse first = responses.get(0);
            Assertions.assertEquals(BulkIndexResponse.FrameCase.FLOW_CONTROL, first.getFrameCase(),
                    "the server's FIRST frame must be the initial credit grant");
            Assertions.assertEquals(FlowControl.State.STATE_READY, first.getFlowControl().getState());
            Assertions.assertTrue(first.getFlowControl().getWindow() > 0);

            BulkIndexResponse second = responses.get(1);
            Assertions.assertEquals(BulkIndexResponse.FrameCase.ACK, second.getFrameCase());
            Assertions.assertEquals(0, second.getAck().getStatus().getCode());
        } finally {
            drop(collection);
        }
    }

    /** A client field named doc_id must never become a second indexed doc_id term. */
    @Test
    public void reservedDocIdFieldIsRejected() {
        String collection = "proto-reserved-docid";
        createCollection(collection, 1);
        try {
            List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().item(
                    doc(IndexDocument.newBuilder()
                            .setClientSeq(1)
                            .setCollection(collection)
                            .setDocId("a")
                            .addFields(DocumentField.newBuilder().setName("doc_id").addValues(str("b")))
                            .build())
            )).collect().asList().await().indefinitely();

            DocAck ack = responses.stream()
                    .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.ACK)
                    .findFirst().orElseThrow().getAck();
            Assertions.assertEquals(3, ack.getStatus().getCode(), "INVALID_ARGUMENT expected");
        } finally {
            drop(collection);
        }
    }

    /** One wrong-dim document must never pin the shard's vector dimension. */
    @Test
    public void wrongVectorDimensionIsRejected() {
        String collection = "proto-wrong-dims";
        createCollection(collection, 1);
        try {
            List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().items(
                    doc(IndexDocument.newBuilder()
                            .setClientSeq(1).setCollection(collection).setDocId("bad")
                            .addFields(DocumentField.newBuilder().setName("vector")
                                    .addValues(vec(1f, 0f, 0f)))   // 3 dims, schema says 4
                            .build()),
                    doc(IndexDocument.newBuilder()
                            .setClientSeq(2).setCollection(collection).setDocId("good")
                            .addFields(DocumentField.newBuilder().setName("vector")
                                    .addValues(vec(1f, 0f, 0f, 0f)))
                            .build())
            )).collect().asList().await().indefinitely();

            List<DocAck> acks = responses.stream()
                    .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.ACK)
                    .map(BulkIndexResponse::getAck)
                    .toList();
            Assertions.assertEquals(2, acks.size());
            Assertions.assertEquals(3, acks.get(0).getStatus().getCode(),
                    "wrong dims must be INVALID_ARGUMENT, not accepted or INTERNAL");
            Assertions.assertEquals(0, acks.get(1).getStatus().getCode(),
                    "the shard must still accept correct documents afterwards");
        } finally {
            drop(collection);
        }
    }

    /** Typed values must survive the write path and come back typed. */
    @Test
    public void typedAndMultiValuedFieldsRoundTrip() {
        String collection = "proto-typed-values";
        createCollection(collection, 1);
        try {
            List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().items(
                    doc(IndexDocument.newBuilder()
                            .setClientSeq(1).setCollection(collection).setDocId("p1")
                            .addFields(DocumentField.newBuilder().setName("vector")
                                    .addValues(vec(1f, 0f, 0f, 0f)))
                            .addFields(DocumentField.newBuilder().setName("price")
                                    .addValues(FieldValue.newBuilder().setInt64Value(1999).build()))
                            .addFields(DocumentField.newBuilder().setName("tags")
                                    .addValues(str("lucene")).addValues(str("vector")))
                            .build())
            )).collect().asList().await().indefinitely();

            DocAck ack = responses.stream()
                    .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.ACK)
                    .findFirst().orElseThrow().getAck();
            Assertions.assertEquals(0, ack.getStatus().getCode());

            GetDocumentResponse got = indexService.getDocument(GetDocumentRequest.newBuilder()
                    .setCollection(collection).setDocId("p1").build()).await().indefinitely();
            Assertions.assertTrue(got.getFound());

            List<FieldValue> price = got.getFieldsList().stream()
                    .filter(f -> f.getName().equals("price"))
                    .flatMap(f -> f.getValuesList().stream())
                    .toList();
            Assertions.assertEquals(1, price.size());
            Assertions.assertEquals(FieldValue.KindCase.INT64_VALUE, price.get(0).getKindCase(),
                    "int64 must not be coerced to an empty string");
            Assertions.assertEquals(1999, price.get(0).getInt64Value());

            List<String> tags = got.getFieldsList().stream()
                    .filter(f -> f.getName().equals("tags"))
                    .flatMap(f -> f.getValuesList().stream())
                    .map(FieldValue::getStringValue)
                    .sorted()
                    .toList();
            Assertions.assertEquals(List.of("lucene", "vector"), tags,
                    "multi-valued fields must not be truncated to values[0]");

            // Field projection: ask for price only.
            GetDocumentResponse projected = indexService.getDocument(GetDocumentRequest.newBuilder()
                    .setCollection(collection).setDocId("p1").addFields("price").build())
                    .await().indefinitely();
            Assertions.assertTrue(projected.getFieldsList().stream()
                            .allMatch(f -> f.getName().equals("price")),
                    "GetDocument must honor the requested field list");
        } finally {
            drop(collection);
        }
    }

    /** typed_document must never be silently discarded with an OK ack. */
    @Test
    public void typedDocumentIsNotSilentlyDropped() {
        String collection = "proto-typed-doc";
        createCollection(collection, 1);
        try {
            Any payload = Any.pack(FlushMarker.newBuilder().setClientSeq(7).build());
            List<BulkIndexResponse> responses = indexService.bulkIndex(Multi.createFrom().items(
                    doc(IndexDocument.newBuilder()
                            .setClientSeq(1).setCollection(collection).setDocId("t1")
                            .setTypedDocument(payload)
                            .build()),
                    doc(IndexDocument.newBuilder()
                            .setClientSeq(2).setCollection(collection).setDocId("t2")
                            .addFields(DocumentField.newBuilder().setName("vector")
                                    .addValues(vec(1f, 0f, 0f, 0f)))
                            .setTypedDocument(payload)
                            .build())
            )).collect().asList().await().indefinitely();

            List<DocAck> acks = responses.stream()
                    .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.ACK)
                    .map(BulkIndexResponse::getAck)
                    .toList();
            Assertions.assertEquals(9, acks.get(0).getStatus().getCode(),
                    "typed_document without a registered proto schema must be FAILED_PRECONDITION, "
                            + "never an OK ack for discarded input");
            Assertions.assertEquals(3, acks.get(1).getStatus().getCode(),
                    "setting both fields and typed_document must be INVALID_ARGUMENT");
        } finally {
            drop(collection);
        }
    }

    /** Unknown collections are NOT_FOUND failures, never found=false. */
    @Test
    public void unknownCollectionIsNotFound() {
        StatusRuntimeException getEx = Assertions.assertThrows(StatusRuntimeException.class,
                () -> indexService.getDocument(GetDocumentRequest.newBuilder()
                        .setCollection("no-such-collection").setDocId("x").build())
                        .await().indefinitely());
        Assertions.assertEquals(io.grpc.Status.Code.NOT_FOUND, getEx.getStatus().getCode());

        StatusRuntimeException delEx = Assertions.assertThrows(StatusRuntimeException.class,
                () -> indexService.deleteDocument(DeleteDocumentRequest.newBuilder()
                        .setCollection("no-such-collection").setDocId("x").build())
                        .await().indefinitely());
        Assertions.assertEquals(io.grpc.Status.Code.NOT_FOUND, delEx.getStatus().getCode());
    }

    // ------------------------------------------------------------------
    // Schema plane (S1/S4/S5): the descriptor set crosses the gRPC boundary
    // stripped of extension registrations; the server must recover them.
    // ------------------------------------------------------------------

    private static FieldOptions searchField(SearchField sf) {
        return FieldOptions.newBuilder().setExtension(SchemaOptionsProto.field, sf).build();
    }

    /** Builds an annotated single-file descriptor set for message t.Doc. */
    private static FileDescriptorSet annotatedDescriptorSet(boolean includeIllegalField) {
        DescriptorProto.Builder docBuilder = DescriptorProto.newBuilder().setName("Doc");

        // proto3 optional string title = 1 [(field) = {type: TEXT, stored: true}]
        docBuilder.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_title"));
        docBuilder.addField(FieldDescriptorProto.newBuilder()
                .setName("title").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setProto3Optional(true).setOneofIndex(0)
                .setOptions(searchField(SearchField.newBuilder()
                        .setType(FieldType.FIELD_TYPE_TEXT).setStored(true).build())));

        // repeated float embedding = 2 [(field) = {type: VECTOR, dims: 4}]
        docBuilder.addField(FieldDescriptorProto.newBuilder()
                .setName("embedding").setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_FLOAT)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .setOptions(searchField(SearchField.newBuilder()
                        .setType(FieldType.FIELD_TYPE_VECTOR)
                        .setVector(VectorOptions.newBuilder().setDims(4)
                                .setSimilarity(VectorOptions.Similarity.SIMILARITY_COSINE))
                        .build())));

        if (includeIllegalField) {
            // int64 without `optional`: IMPLICIT_PRESENCE rejection.
            docBuilder.addField(FieldDescriptorProto.newBuilder()
                    .setName("price").setNumber(3)
                    .setType(FieldDescriptorProto.Type.TYPE_INT64)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setOptions(searchField(SearchField.newBuilder()
                            .setType(FieldType.FIELD_TYPE_LONG).build())));
        }

        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("user_schema.proto").setPackage("t").setSyntax("proto3")
                .addMessageType(docBuilder.build())
                .build();
        return FileDescriptorSet.newBuilder().addFile(file).build();
    }

    /**
     * Simulates the gRPC boundary: serialize the descriptor set and re-parse
     * it WITHOUT the extension registry, exactly as the generated unmarshaller
     * does. The server must recover the annotations itself.
     */
    private static FileDescriptorSet throughWire(FileDescriptorSet set) throws Exception {
        return FileDescriptorSet.parseFrom(set.toByteArray());
    }

    @Test
    public void registerSchemaSurvivesExtensionRegistryLoss() throws Exception {
        String collection = "proto-schema-register";
        createCollection(collection, 1);
        try {
            RegisterSchemaResponse resp = adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                    .setCollection(collection)
                    .setSource(SchemaSource.newBuilder()
                            .setDescriptorSet(throughWire(annotatedDescriptorSet(false)))
                            .setRootMessage("t.Doc")
                            .build())
                    .build()).await().indefinitely();

            Assertions.assertTrue(resp.getCollection().getSchema().getFieldsCount() >= 2,
                    "annotations must survive the extension-registry boundary; got "
                            + resp.getCollection().getSchema().getFieldsCount() + " fields");
        } finally {
            drop(collection);
        }
    }

    @Test
    public void registerSchemaRejectsSchemaWithRejections() throws Exception {
        String collection = "proto-schema-reject";
        createCollection(collection, 1);
        try {
            StatusRuntimeException ex = Assertions.assertThrows(StatusRuntimeException.class,
                    () -> {
                        try {
                            adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                                    .setCollection(collection)
                                    .setSource(SchemaSource.newBuilder()
                                            .setDescriptorSet(throughWire(annotatedDescriptorSet(true)))
                                            .setRootMessage("t.Doc")
                                            .build())
                                    .build()).await().indefinitely();
                        } catch (Exception e) {
                            throw e;
                        }
                    });
            Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode(),
                    "a schema with rejections must fail INVALID_ARGUMENT, not register as WIRE_SAFE_LIVE");
        } finally {
            drop(collection);
        }
    }

    /** ValidateSchema must surface rejections even with no registered schema (CI gate). */
    @Test
    public void validateSchemaAlwaysReportsRejections() throws Exception {
        ValidateSchemaResponse resp = adminService.validateSchema(ValidateSchemaRequest.newBuilder()
                .setCollection("never-registered-collection")
                .setSource(SchemaSource.newBuilder()
                        .setDescriptorSet(throughWire(annotatedDescriptorSet(true)))
                        .setRootMessage("t.Doc")
                        .build())
                .build()).await().indefinitely();

        List<String> codes = new ArrayList<>();
        for (SchemaChange change : resp.getChangesList()) {
            codes.add(change.getCode());
        }
        Assertions.assertTrue(codes.contains("IMPLICIT_PRESENCE"),
                "rejections must never be replaced by a synthetic success entry; got " + codes);
    }
}
