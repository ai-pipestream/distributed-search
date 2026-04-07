package ai.pipestream.search.index;

import ai.pipestream.index.v1.*;
import ai.pipestream.search.grpc.KnnNodeService;
import ai.pipestream.search.grpc.SearchRequest;
import ai.pipestream.search.grpc.SearchResponse;
import ai.pipestream.search.grpc.SearchHit;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@QuarkusTestResource(value = IndexServiceTest.TestResource.class, restrictToAnnotatedClass = true)
public class IndexServiceTest {

    private static final String COLLECTION = "test-collection";
    private static final int DIMENSION = 32;
    private static final int NUM_SHARDS = 2;

    @GrpcClient
    IndexService indexService;

    @GrpcClient
    KnnNodeService knnService;

    @Inject
    CollectionManager collectionManager;

    public static class TestResource implements QuarkusTestResourceLifecycleManager {
        private Path tempDataDir;

        @Override
        public Map<String, String> start() {
            try {
                tempDataDir = Files.createTempDirectory("index-test-data-");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return Map.of(
                    "knn.data.dir", tempDataDir.toAbsolutePath().toString(),
                    "knn.index.path", "NONE",
                    "knn.shard.id", "0",
                    "knn.single.node", "true",
                    "quarkus.http.test-port", "0"
            );
        }

        @Override
        public void stop() {
            // Temp dir cleaned by OS
        }
    }

    // --- Collection CRUD ---

    @Test
    @Order(1)
    void testCreateCollection() {
        CreateCollectionResponse response = indexService.createCollection(
                CreateCollectionRequest.newBuilder()
                        .setName(COLLECTION)
                        .setVectorDimension(DIMENSION)
                        .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE)
                        .setNumShards(NUM_SHARDS)
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        assertNotNull(response.getCollection());
        assertEquals(COLLECTION, response.getCollection().getName());
        assertEquals(DIMENSION, response.getCollection().getVectorDimension());
        assertEquals(NUM_SHARDS, response.getCollection().getNumShards());
        assertEquals(0, response.getCollection().getTotalDocs());
    }

    @Test
    @Order(2)
    void testGetCollection() {
        GetCollectionResponse response = indexService.getCollection(
                GetCollectionRequest.newBuilder().setName(COLLECTION).build()
        ).await().atMost(Duration.ofSeconds(5));

        assertEquals(COLLECTION, response.getCollection().getName());
        assertEquals(DIMENSION, response.getCollection().getVectorDimension());
    }

    @Test
    @Order(3)
    void testListCollections() {
        ListCollectionsResponse response = indexService.listCollections(
                ListCollectionsRequest.newBuilder().build()
        ).await().atMost(Duration.ofSeconds(5));

        assertTrue(response.getCollectionsCount() >= 1);
        assertTrue(response.getCollectionsList().stream()
                .anyMatch(c -> c.getName().equals(COLLECTION)));
    }

    // --- Unary Indexing ---

    @Test
    @Order(10)
    void testIndexDocumentWithVector() {
        float[] vector = randomNormalizedVector(DIMENSION, 42);

        IndexDocumentResponse response = indexService.indexDocument(
                IndexDocumentRequest.newBuilder()
                        .setCollection(COLLECTION)
                        .setDocId("doc-1")
                        .setVector(VectorContent.newBuilder()
                                .addAllValues(toFloatList(vector))
                                .build())
                        .setChunkText("This is the first test document about machine learning.")
                        .putMetadata("source", "test")
                        .putMetadata("category", "ml")
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        assertTrue(response.getSuccess(), "Index should succeed: " + response.getError());
        assertEquals("doc-1", response.getDocId());
        assertTrue(response.getShardId() >= 0 && response.getShardId() < NUM_SHARDS);
    }

    @Test
    @Order(11)
    void testIndexDocumentWithTextAndVector() {
        float[] vector = randomNormalizedVector(DIMENSION, 43);

        IndexDocumentResponse response = indexService.indexDocument(
                IndexDocumentRequest.newBuilder()
                        .setCollection(COLLECTION)
                        .setDocId("doc-2")
                        .setTextAndVector(TextAndVector.newBuilder()
                                .setText("Neural networks are a subset of machine learning.")
                                .addAllVector(toFloatList(vector))
                                .build())
                        .putMetadata("source", "test")
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        assertTrue(response.getSuccess());
        assertEquals("doc-2", response.getDocId());
    }

    @Test
    @Order(12)
    void testIndexDocumentDimensionMismatch() {
        // Wrong dimension — should fail
        float[] wrongVector = randomNormalizedVector(DIMENSION + 10, 99);

        IndexDocumentResponse response = indexService.indexDocument(
                IndexDocumentRequest.newBuilder()
                        .setCollection(COLLECTION)
                        .setDocId("doc-bad")
                        .setVector(VectorContent.newBuilder()
                                .addAllValues(toFloatList(wrongVector))
                                .build())
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        assertFalse(response.getSuccess());
        assertTrue(response.getError().contains("dimension"));
    }

    @Test
    @Order(13)
    void testIndexDocumentNonExistentCollection() {
        float[] vector = randomNormalizedVector(DIMENSION, 100);

        IndexDocumentResponse response = indexService.indexDocument(
                IndexDocumentRequest.newBuilder()
                        .setCollection("nonexistent")
                        .setDocId("doc-x")
                        .setVector(VectorContent.newBuilder()
                                .addAllValues(toFloatList(vector))
                                .build())
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        assertFalse(response.getSuccess());
        assertTrue(response.getError().contains("not found"));
    }

    // --- Streaming Indexing ---

    @Test
    @Order(20)
    void testStreamIndex() {
        int numDocs = 100;
        List<IndexDocumentRequest> requests = new ArrayList<>();
        Random rng = new Random(200);

        for (int i = 0; i < numDocs; i++) {
            float[] vector = randomNormalizedVector(DIMENSION, rng);
            requests.add(IndexDocumentRequest.newBuilder()
                    .setCollection(COLLECTION)
                    .setDocId("stream-doc-" + i)
                    .setVector(VectorContent.newBuilder()
                            .addAllValues(toFloatList(vector))
                            .build())
                    .setChunkText("Streamed document number " + i)
                    .build());
        }

        AssertSubscriber<IndexDocumentResponse> subscriber =
                indexService.streamIndex(Multi.createFrom().iterable(requests))
                        .subscribe().withSubscriber(AssertSubscriber.create(numDocs + 10));

        subscriber.awaitCompletion(Duration.ofSeconds(30));
        List<IndexDocumentResponse> responses = subscriber.getItems();

        assertEquals(numDocs, responses.size(), "Should get one ack per doc");
        long successCount = responses.stream().filter(IndexDocumentResponse::getSuccess).count();
        assertEquals(numDocs, successCount, "All docs should succeed");

        // Verify all doc IDs are present (order may vary with concurrent processing)
        var docIds = responses.stream().map(IndexDocumentResponse::getDocId).collect(java.util.stream.Collectors.toSet());
        for (int i = 0; i < numDocs; i++) {
            assertTrue(docIds.contains("stream-doc-" + i), "Missing doc ID: stream-doc-" + i);
        }
    }

    // --- Shard Routing ---

    @Test
    @Order(25)
    void testShardRoutingDeterministic() {
        // Same doc_id should always route to same shard
        float[] vector = randomNormalizedVector(DIMENSION, 300);

        IndexDocumentResponse first = indexService.indexDocument(
                IndexDocumentRequest.newBuilder()
                        .setCollection(COLLECTION)
                        .setDocId("route-test-doc")
                        .setVector(VectorContent.newBuilder()
                                .addAllValues(toFloatList(vector))
                                .build())
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        IndexDocumentResponse second = indexService.indexDocument(
                IndexDocumentRequest.newBuilder()
                        .setCollection(COLLECTION)
                        .setDocId("route-test-doc")
                        .setVector(VectorContent.newBuilder()
                                .addAllValues(toFloatList(vector))
                                .build())
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        assertTrue(first.getSuccess());
        assertTrue(second.getSuccess());
        assertEquals(first.getShardId(), second.getShardId(), "Same doc_id should always route to same shard");
    }

    // --- Document Deletion ---

    @Test
    @Order(30)
    void testDeleteDocument() {
        // First index a doc
        float[] vector = randomNormalizedVector(DIMENSION, 400);
        indexService.indexDocument(
                IndexDocumentRequest.newBuilder()
                        .setCollection(COLLECTION)
                        .setDocId("to-delete")
                        .setVector(VectorContent.newBuilder()
                                .addAllValues(toFloatList(vector))
                                .build())
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        // Then delete it
        DeleteDocumentResponse deleteResp = indexService.deleteDocument(
                DeleteDocumentRequest.newBuilder()
                        .setCollection(COLLECTION)
                        .setDocId("to-delete")
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        assertTrue(deleteResp.getFound());
    }

    @Test
    @Order(31)
    void testDeleteDocumentNonExistentCollection() {
        DeleteDocumentResponse deleteResp = indexService.deleteDocument(
                DeleteDocumentRequest.newBuilder()
                        .setCollection("nonexistent")
                        .setDocId("whatever")
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        assertFalse(deleteResp.getFound());
    }

    // --- Doc Count Verification ---

    @Test
    @Order(40)
    void testDocCountAfterIndexing() {
        // Commit first so counts are visible
        collectionManager.periodicCommit();

        GetCollectionResponse response = indexService.getCollection(
                GetCollectionRequest.newBuilder().setName(COLLECTION).build()
        ).await().atMost(Duration.ofSeconds(5));

        // We indexed doc-1, doc-2, 100 stream docs, route-test-doc (upserted twice counts as 1),
        // and to-delete (which was deleted). Total should be 103+.
        assertTrue(response.getCollection().getTotalDocs() >= 100,
                "Expected at least 100 docs, got " + response.getCollection().getTotalDocs());
    }

    // --- Index → Search E2E ---

    @Test
    @Order(50)
    void testIndexThenSearchViaCollection() {
        String e2eCollection = "e2e-search-test";
        int e2eDim = 32;

        // 1. Create a 1-shard collection for single-node search
        CreateCollectionResponse createResp = indexService.createCollection(
                CreateCollectionRequest.newBuilder()
                        .setName(e2eCollection)
                        .setVectorDimension(e2eDim)
                        .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE)
                        .setNumShards(1)
                        .build()
        ).await().atMost(Duration.ofSeconds(10));
        assertNotNull(createResp.getCollection());

        // 2. Create a known "target" vector and 9 random docs
        float[] targetVector = new float[e2eDim];
        // Target: all positive values in first half, zeros elsewhere
        for (int i = 0; i < e2eDim / 2; i++) {
            targetVector[i] = 1.0f;
        }
        normalize(targetVector);

        IndexDocumentResponse targetResp = indexService.indexDocument(
                IndexDocumentRequest.newBuilder()
                        .setCollection(e2eCollection)
                        .setDocId("target-doc")
                        .setVector(VectorContent.newBuilder().addAllValues(toFloatList(targetVector)).build())
                        .setChunkText("This is the target document we are looking for.")
                        .build()
        ).await().atMost(Duration.ofSeconds(10));
        assertTrue(targetResp.getSuccess(), "Target doc should index successfully");

        Random rng = new Random(999);
        for (int i = 0; i < 9; i++) {
            float[] randVec = randomNormalizedVector(e2eDim, rng);
            indexService.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setCollection(e2eCollection)
                            .setDocId("noise-doc-" + i)
                            .setVector(VectorContent.newBuilder().addAllValues(toFloatList(randVec)).build())
                            .setChunkText("Random noise document " + i)
                            .build()
            ).await().atMost(Duration.ofSeconds(10));
        }

        // 3. Commit and verify doc count so the NRT reader can see the docs
        collectionManager.periodicCommit();

        // Force merge + commit to ensure HNSW graph is fully built
        try {
            var writer = collectionManager.getWriter(e2eCollection, 0);
            writer.forceMerge(1);
            writer.commit();
        } catch (Exception e) {
            fail("Failed to merge/commit: " + e.getMessage());
        }

        long docCount = collectionManager.getDocCount(e2eCollection, 0);
        assertTrue(docCount >= 10, "Expected at least 10 docs in shard 0 of E2E collection, got " + docCount);

        // 4. Search for the target vector within the collection
        List<Float> queryVector = toFloatList(targetVector);

        AssertSubscriber<SearchResponse> subscriber = knnService.search(
                SearchRequest.newBuilder()
                        .setQueryId("e2e-test-query")
                        .addAllVector(queryVector)
                        .setK(20)
                        .setCollaborative(false)
                        .setCollection(e2eCollection)
                        .build()
        ).subscribe().withSubscriber(AssertSubscriber.create(100));

        subscriber.awaitCompletion(Duration.ofSeconds(15));
        List<SearchResponse> responses = subscriber.getItems();

        // Only hit payloads (exclude debug/terminal)
        List<SearchHit> realHits = responses.stream()
                .filter(r -> r.getPayloadCase() == SearchResponse.PayloadCase.HIT)
                .map(SearchResponse::getHit)
                .filter(h -> h.getScore() > 0)
                .toList();

        assertFalse(realHits.isEmpty(), "Should have at least one real hit");

        // The top hit (by score) should be the target doc (identical query vector → score ~1.0)
        SearchHit topHit = realHits.stream()
                .max((a, b) -> Float.compare(a.getScore(), b.getScore()))
                .orElseThrow();
        assertTrue(topHit.getChunk().contains("target document"),
                "Top hit should be the target doc, got chunk: " + topHit.getChunk()
                        + " (score=" + topHit.getScore() + ")");

        // 5. Cleanup: delete the E2E collection
        DeleteCollectionResponse delResp = indexService.deleteCollection(
                DeleteCollectionRequest.newBuilder().setName(e2eCollection).build()
        ).await().atMost(Duration.ofSeconds(10));
        assertTrue(delResp.getSuccess());
    }

    // --- Delete Collection ---

    @Test
    @Order(100)
    void testDeleteCollection() {
        // Create a throwaway collection
        indexService.createCollection(
                CreateCollectionRequest.newBuilder()
                        .setName("throwaway")
                        .setVectorDimension(16)
                        .setNumShards(1)
                        .build()
        ).await().atMost(Duration.ofSeconds(10));

        DeleteCollectionResponse response = indexService.deleteCollection(
                DeleteCollectionRequest.newBuilder().setName("throwaway").build()
        ).await().atMost(Duration.ofSeconds(10));

        assertTrue(response.getSuccess());

        // Verify it's gone
        ListCollectionsResponse list = indexService.listCollections(
                ListCollectionsRequest.newBuilder().build()
        ).await().atMost(Duration.ofSeconds(5));

        assertFalse(list.getCollectionsList().stream()
                .anyMatch(c -> c.getName().equals("throwaway")));
    }

    // --- Helpers ---

    private static void normalize(float[] vec) {
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < vec.length; i++) vec[i] /= norm;
    }

    private static float[] randomNormalizedVector(int dim, int seed) {
        return randomNormalizedVector(dim, new Random(seed));
    }

    private static float[] randomNormalizedVector(int dim, Random rng) {
        float[] vec = new float[dim];
        float norm = 0;
        for (int i = 0; i < dim; i++) {
            vec[i] = rng.nextFloat() - 0.5f;
            norm += vec[i] * vec[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dim; i++) {
            vec[i] /= norm;
        }
        return vec;
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }
}
