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

    /**
     * P0 proof: Summary.top_doc_ids carries client doc ids (never Lucene
     * ordinals), every entry was previously delivered as a Hit frame, the
     * list is score-descending, and result_position is global 1-based.
     */
    @Test
    public void multiShardTopDocIdsAreClientDocIds() {
        String collection = "test-v1alpha1-topdocids";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection)
                .setNumShards(2)
                .setSchema(vectorOnlySchema(4))
                .build()).await().indefinitely();

        // alpha-0 is most similar to [1,0,0,0]; similarity decreases with i.
        List<BulkIndexRequest> frames = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            frames.add(BulkIndexRequest.newBuilder().setDocument(IndexDocument.newBuilder()
                    .setClientSeq(i + 1)
                    .setCollection(collection)
                    .setDocId("alpha-" + i)
                    .addFields(DocumentField.newBuilder().setName("vector")
                            .addValues(FieldValue.newBuilder().setVectorValue(Vector.newBuilder()
                                    .addValues(1.0f).addValues(i * 0.4f).addValues(0f).addValues(0f)
                                    .build()).build())
                            .build())
                    .build()).build());
        }
        frames.add(BulkIndexRequest.newBuilder()
                .setFlush(FlushMarker.newBuilder().setClientSeq(6).build()).build());

        List<BulkIndexResponse> acks = indexService.bulkIndex(
                io.smallrye.mutiny.Multi.createFrom().iterable(frames)).collect().asList().await().indefinitely();
        long okDocs = acks.stream()
                .filter(a -> a.getFrameCase() == BulkIndexResponse.FrameCase.ACK)
                .filter(a -> a.getAck().getStatus().getCode() == 0)
                .count();
        Assertions.assertEquals(6, okDocs, "all six documents should be accepted");

        List<SearchResponse> responses = searchService.search(SearchRequest.newBuilder()
                .setCollection(collection)
                .setSize(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("vector")
                        .setVector(Vector.newBuilder()
                                .addValues(1.0f).addValues(0f).addValues(0f).addValues(0f).build())
                        .setK(10)
                        .build()).build())
                .build()).collect().asList().await().indefinitely();

        List<Hit> hits = responses.stream()
                .filter(r -> r.getFrameCase() == SearchResponse.FrameCase.HIT)
                .map(SearchResponse::getHit)
                .toList();
        Assertions.assertEquals(6, hits.size());

        // result_position is global 1-based across shards, not per-shard.
        for (int i = 0; i < hits.size(); i++) {
            Assertions.assertEquals(i + 1, hits.get(i).getResultPosition(),
                    "result_position must be global and strictly increasing");
        }

        Summary summary = responses.get(responses.size() - 1).getSummary();
        java.util.Set<String> deliveredIds = hits.stream().map(Hit::getDocId)
                .collect(java.util.stream.Collectors.toSet());

        Assertions.assertFalse(summary.getTopDocIdsList().isEmpty());
        for (String id : summary.getTopDocIdsList()) {
            Assertions.assertTrue(id.startsWith("alpha-"),
                    "top_doc_ids must carry client doc ids, got: " + id);
            Assertions.assertTrue(deliveredIds.contains(id),
                    "every top_doc_id must have been delivered as a Hit frame: " + id);
        }

        // Score-descending: the ranking mirrors hits sorted by score.
        List<String> expected = hits.stream()
                .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                .map(Hit::getDocId)
                .toList();
        Assertions.assertEquals(expected, summary.getTopDocIdsList());
        Assertions.assertEquals("alpha-0", summary.getTopDocIds(0));

        Assertions.assertEquals(6, summary.getTotalHits());
        // `visited` must never be a fabricated number.
        Assertions.assertEquals(0, summary.getVisited());

        adminService.dropCollection(DropCollectionRequest.newBuilder().setName(collection).build())
                .await().indefinitely();
    }

    /**
     * P0 proof: searching a freshly created (never-written) multi-shard
     * collection returns an OK stream with an empty Summary — not a Summary
     * assembled from N internal errors, and not an RPC failure.
     */
    @Test
    public void freshCollectionSearchIsEmptyAndOk() {
        String collection = "test-v1alpha1-fresh";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection)
                .setNumShards(4)
                .setSchema(vectorOnlySchema(4))
                .build()).await().indefinitely();

        List<SearchResponse> responses = searchService.search(SearchRequest.newBuilder()
                .setCollection(collection)
                .setSize(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("vector")
                        .setVector(Vector.newBuilder()
                                .addValues(1.0f).addValues(0f).addValues(0f).addValues(0f).build())
                        .setK(5)
                        .build()).build())
                .build()).collect().asList().await().indefinitely();

        Assertions.assertEquals(SearchResponse.FrameCase.CONTEXT, responses.get(0).getFrameCase());
        Summary summary = responses.get(responses.size() - 1).getSummary();
        Assertions.assertEquals(0, summary.getTotalHits());
        Assertions.assertEquals(TotalHitsRelation.TOTAL_HITS_RELATION_EQ, summary.getTotalHitsRelation());
        // All four shards reported success (empty ≠ failed).
        Assertions.assertEquals(4, summary.getShardSummariesCount());
        for (ShardSummary shard : summary.getShardSummariesList()) {
            Assertions.assertEquals(0, shard.getStatus().getCode());
        }

        adminService.dropCollection(DropCollectionRequest.newBuilder().setName(collection).build())
                .await().indefinitely();
    }

    private static CollectionSchema vectorOnlySchema(int dims) {
        return CollectionSchema.newBuilder()
                .addFields(FieldSchema.newBuilder()
                        .setName("doc_id")
                        .setKeyword(KeywordFieldSchema.newBuilder().build())
                        .setStored(true)
                        .build())
                .addFields(FieldSchema.newBuilder()
                        .setName("vector")
                        .setDenseVector(DenseVectorFieldSchema.newBuilder()
                                .setDims(dims)
                                .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE)
                                .build())
                        .setStored(false)
                        .build())
                .build();
    }
}
