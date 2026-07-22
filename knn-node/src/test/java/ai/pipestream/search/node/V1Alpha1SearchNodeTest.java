package ai.pipestream.search.node;

import ai.pipestream.search.v1alpha1.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

@QuarkusTest
@QuarkusTestResource(value = KnnNodeTest.IndexResource.class, restrictToAnnotatedClass = true)
public class V1Alpha1SearchNodeTest {

    @Inject
    @io.quarkus.grpc.GrpcService
    CollectionAdminService adminService;

    @Inject
    @io.quarkus.grpc.GrpcService
    IndexService indexService;

    @Inject
    @io.quarkus.grpc.GrpcService
    SearchService searchService;

    @Test
    public void testV1Alpha1EndToEndPipeline() {
        String collection = "test-v1alpha1-collection";

        // 1. Create collection
        CreateCollectionRequest createReq = CreateCollectionRequest.newBuilder()
                .setName(collection)
                .setNumShards(1)
                .setSchema(CollectionSchema.newBuilder()
                        .addFields(FieldSchema.newBuilder()
                                .setName("doc_id")
                                .setKeyword(KeywordFieldSchema.newBuilder().build())
                                .setStored(true)
                                .build())
                        .addFields(FieldSchema.newBuilder()
                                .setName("title")
                                .setText(TextFieldSchema.newBuilder().build())
                                .setStored(true)
                                .build())
                        .addFields(FieldSchema.newBuilder()
                                .setName("vector")
                                .setDenseVector(DenseVectorFieldSchema.newBuilder()
                                        .setDims(4)
                                        .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE)
                                        .build())
                                .setStored(false)
                                .build())
                        .build())
                .build();

        CreateCollectionResponse createResp = adminService.createCollection(createReq).await().indefinitely();
        Assertions.assertNotNull(createResp);
        Assertions.assertEquals(collection, createResp.getCollection().getName());

        // 2. Index documents via BulkIndex stream
        IndexDocument doc1 = IndexDocument.newBuilder()
                .setClientSeq(1)
                .setCollection(collection)
                .setDocId("doc-1")
                .addFields(DocumentField.newBuilder().setName("title").addValues(FieldValue.newBuilder().setStringValue("Lucene Search Engine").build()).build())
                .addFields(DocumentField.newBuilder().setName("vector").addValues(FieldValue.newBuilder().setVectorValue(Vector.newBuilder().addAllValues(List.of(1.0f, 0.0f, 0.0f, 0.0f)).build()).build()).build())
                .build();

        IndexDocument doc2 = IndexDocument.newBuilder()
                .setClientSeq(2)
                .setCollection(collection)
                .setDocId("doc-2")
                .addFields(DocumentField.newBuilder().setName("title").addValues(FieldValue.newBuilder().setStringValue("Distributed Vector Search").build()).build())
                .addFields(DocumentField.newBuilder().setName("vector").addValues(FieldValue.newBuilder().setVectorValue(Vector.newBuilder().addAllValues(List.of(0.0f, 1.0f, 0.0f, 0.0f)).build()).build()).build())
                .build();

        io.smallrye.mutiny.Multi<BulkIndexRequest> indexStream = io.smallrye.mutiny.Multi.createFrom().items(
                BulkIndexRequest.newBuilder().setDocument(doc1).build(),
                BulkIndexRequest.newBuilder().setDocument(doc2).build(),
                BulkIndexRequest.newBuilder().setFlush(FlushMarker.newBuilder().setClientSeq(2).build()).build()
        );

        List<BulkIndexResponse> acks = indexService.bulkIndex(indexStream).collect().asList().await().indefinitely();
        Assertions.assertNotNull(acks);
        Assertions.assertTrue(acks.size() >= 2);

        // 3. Get document
        GetDocumentResponse getResp = indexService.getDocument(GetDocumentRequest.newBuilder()
                .setCollection(collection)
                .setDocId("doc-1")
                .build()).await().indefinitely();

        Assertions.assertTrue(getResp.getFound());
        Assertions.assertEquals("doc-1", getResp.getDocId());

        // 4. Execute streaming search (KNN query)
        SearchRequest searchReq = SearchRequest.newBuilder()
                .setCollection(collection)
                .setSize(10)
                .setQuery(Query.newBuilder()
                        .setKnn(KnnQuery.newBuilder()
                                .setField("vector")
                                .setVector(Vector.newBuilder().addAllValues(List.of(1.0f, 0.0f, 0.0f, 0.0f)).build())
                                .setK(5)
                                .build())
                        .build())
                .build();

        List<SearchResponse> frames = searchService.search(searchReq).collect().asList().await().indefinitely();
        Assertions.assertFalse(frames.isEmpty());

        // Assert Frame 1 is Context
        Assertions.assertEquals(SearchResponse.FrameCase.CONTEXT, frames.get(0).getFrameCase());
        Assertions.assertFalse(frames.get(0).getContext().getQueryId().isEmpty());

        // Assert Terminal Frame is Summary
        SearchResponse lastFrame = frames.get(frames.size() - 1);
        Assertions.assertEquals(SearchResponse.FrameCase.SUMMARY, lastFrame.getFrameCase());
        Assertions.assertTrue(lastFrame.getSummary().getTotalHits() > 0);

        // 5. Clean up collection
        DropCollectionResponse dropResp = adminService.dropCollection(DropCollectionRequest.newBuilder().setName(collection).build()).await().indefinitely();
        Assertions.assertNotNull(dropResp);
    }
}
