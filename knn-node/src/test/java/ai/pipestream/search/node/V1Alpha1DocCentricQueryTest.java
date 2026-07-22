package ai.pipestream.search.node;

import ai.pipestream.search.v1alpha1.*;
import com.google.protobuf.Descriptors;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 proof (the strongest test in the plan): the same chunk set indexed once
 * as blocks and once as flat documents must produce block-join document
 * scores equal, float for float, to max() over the flat per-chunk scores.
 * Plus: chunks[] completeness and ordering, filter semantics, stub
 * exclusion, and the chunks_per_hit cap.
 */
@QuarkusTest
@QuarkusTestResource(value = KnnNodeTest.IndexResource.class, restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class V1Alpha1DocCentricQueryTest {

    private static final String DC = "dcq-blocks";
    private static final String FLAT = "dcq-flat";

    /** parent id -> (chunk id -> 4-dim vector). Insertion order = ordinal. */
    private static final Map<String, Map<String, float[]>> CORPUS = new LinkedHashMap<>();

    static {
        Map<String, float[]> a = new LinkedHashMap<>();
        a.put("a-0", new float[]{0.9f, 0.1f, 0.0f, 0.1f});
        a.put("a-1", new float[]{0.1f, 0.9f, 0.0f, 0.1f});
        CORPUS.put("doc-a", a);
        Map<String, float[]> b = new LinkedHashMap<>();
        b.put("b-0", new float[]{0.7f, 0.7f, 0.0f, 0.1f});
        b.put("b-1", new float[]{0.0f, 0.0f, 1.0f, 0.1f});
        b.put("b-2", new float[]{0.5f, 0.5f, 0.5f, 0.1f});
        CORPUS.put("doc-b", b);
        Map<String, float[]> c = new LinkedHashMap<>();
        c.put("c-0", new float[]{0.0f, 1.0f, 0.0f, 0.1f});
        CORPUS.put("doc-c", c);
    }

    private static final Map<String, String> TITLES = Map.of(
            "doc-a", "alpha parent", "doc-b", "beta parent", "doc-c", "gamma parent");

    @Inject
    @io.quarkus.grpc.GrpcService
    CollectionAdminService adminService;

    @Inject
    @io.quarkus.grpc.GrpcService
    IndexService indexService;

    @Inject
    @io.quarkus.grpc.GrpcService
    SearchService searchService;

    private static Vector vec(float[] values) {
        Vector.Builder v = Vector.newBuilder();
        for (float f : values) {
            v.addValues(f);
        }
        return v.build();
    }

    @BeforeAll
    void indexBothCorpora() throws Exception {
        // Document-centric collection with the registered proto schema.
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(DC).setNumShards(1)
                .setSchema(CollectionSchema.newBuilder()
                        .addFields(FieldSchema.newBuilder().setName("embedding")
                                .setDenseVector(DenseVectorFieldSchema.newBuilder()
                                        .setDims(4)
                                        .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE))))
                .build()).await().indefinitely();
        adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                .setCollection(DC)
                .setSource(SchemaSource.newBuilder()
                        .setDescriptorSet(DocCentricTestSchema.wireDescriptorSet())
                        .setRootMessage("t.Doc")
                        .setChunkMessage("t.DocChunk"))
                .build()).await().indefinitely();

        Descriptors.FileDescriptor file = DocCentricTestSchema.buildFile();
        List<BulkIndexRequest> blockFrames = new ArrayList<>();
        long seq = 1;
        for (Map.Entry<String, Map<String, float[]>> parent : CORPUS.entrySet()) {
            SuppliedChunks.Builder chunks = SuppliedChunks.newBuilder();
            for (Map.Entry<String, float[]> chunk : parent.getValue().entrySet()) {
                chunks.addChunks(Chunk.newBuilder()
                        .setChunkId(chunk.getKey())
                        .setPayload(DocCentricTestSchema.chunkPayload(file, "text of " + chunk.getKey()))
                        .setVector(vec(chunk.getValue())));
            }
            blockFrames.add(BulkIndexRequest.newBuilder().setParentDocument(
                    IndexParentDocument.newBuilder()
                            .setClientSeq(seq++)
                            .setCollection(DC)
                            .setDocId(parent.getKey())
                            .setPayload(DocCentricTestSchema.docPayload(file, TITLES.get(parent.getKey())))
                            .setSuppliedChunks(chunks)
                            .build()).build());
        }
        List<BulkIndexResponse> blockAcks = indexService.bulkIndex(
                Multi.createFrom().iterable(blockFrames)).collect().asList().await().indefinitely();
        long okParents = blockAcks.stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.PARENT_ACK)
                .filter(r -> r.getParentAck().getStatus().getCode() == 0)
                .count();
        Assertions.assertEquals(3, okParents, "all parents must index");

        // Flat baseline: every chunk as its own document.
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(FLAT).setNumShards(1)
                .setSchema(CollectionSchema.newBuilder()
                        .addFields(FieldSchema.newBuilder().setName("vector")
                                .setDenseVector(DenseVectorFieldSchema.newBuilder()
                                        .setDims(4)
                                        .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE))))
                .build()).await().indefinitely();
        List<BulkIndexRequest> flatFrames = new ArrayList<>();
        for (Map.Entry<String, Map<String, float[]>> parent : CORPUS.entrySet()) {
            for (Map.Entry<String, float[]> chunk : parent.getValue().entrySet()) {
                flatFrames.add(BulkIndexRequest.newBuilder().setDocument(IndexDocument.newBuilder()
                        .setClientSeq(seq++)
                        .setCollection(FLAT)
                        .setDocId(chunk.getKey())
                        .addFields(DocumentField.newBuilder().setName("vector")
                                .addValues(FieldValue.newBuilder().setVectorValue(vec(chunk.getValue()))))
                        .build()).build());
            }
        }
        indexService.bulkIndex(Multi.createFrom().iterable(flatFrames))
                .collect().asList().await().indefinitely();
    }

    private List<SearchResponse> search(SearchRequest request) {
        return searchService.search(request).collect().asList().await().indefinitely();
    }

    private static List<Hit> hits(List<SearchResponse> responses) {
        return responses.stream()
                .filter(r -> r.getFrameCase() == SearchResponse.FrameCase.HIT)
                .map(SearchResponse::getHit)
                .toList();
    }

    private static final Vector QUERY_VECTOR = Vector.newBuilder()
            .addValues(1f).addValues(0f).addValues(0f).addValues(0f).build();

    private SearchRequest.Builder docCentricRequest() {
        return SearchRequest.newBuilder()
                .setCollection(DC)
                .setSize(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("embedding")
                        .setVector(QUERY_VECTOR)
                        .setK(3)
                        .setDocumentCentric(true)));
    }

    @Test
    public void blockJoinScoresEqualMaxOverFlatChunkScores() {
        // Flat per-chunk scores, keyed by chunk id.
        List<Hit> flatHits = hits(search(SearchRequest.newBuilder()
                .setCollection(FLAT)
                .setSize(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("vector").setVector(QUERY_VECTOR).setK(10)))
                .build()));
        Assertions.assertEquals(6, flatHits.size());
        Map<String, Float> flatScores = new HashMap<>();
        flatHits.forEach(h -> flatScores.put(h.getDocId(), h.getScore()));

        List<Hit> docHits = hits(search(docCentricRequest().build()));
        Assertions.assertEquals(3, docHits.size(), "top-k DOCUMENTS, not chunks");

        for (Hit hit : docHits) {
            Map<String, float[]> chunkSet = CORPUS.get(hit.getDocId());
            Assertions.assertNotNull(chunkSet, "hit must be a parent doc id: " + hit.getDocId());

            // Document score == max over the flat scores of ITS chunks, float for float.
            float expected = Float.NEGATIVE_INFINITY;
            for (String chunkId : chunkSet.keySet()) {
                expected = Math.max(expected, flatScores.get(chunkId));
            }
            Assertions.assertEquals(expected, hit.getScore(),
                    "block-join max scoring must equal flat max for " + hit.getDocId());

            // chunks[] is COMPLETE (every chunk of the winning parent, under the cap)...
            Assertions.assertEquals(chunkSet.size(), hit.getChunksCount(),
                    "the exact second pass must score EVERY chunk of " + hit.getDocId());
            // ...score-ordered, hit.score is the max, and each chunk score
            // equals the flat baseline for the same vector.
            float previous = Float.POSITIVE_INFINITY;
            for (ChunkHit chunk : hit.getChunksList()) {
                Assertions.assertTrue(chunk.getScore() <= previous, "chunks must be score-descending");
                previous = chunk.getScore();
                Assertions.assertEquals(flatScores.get(chunk.getChunkId()), chunk.getScore(),
                        "exact chunk score must equal the flat baseline for " + chunk.getChunkId());
            }
            Assertions.assertEquals(hit.getChunks(0).getScore(), hit.getScore(),
                    "the document score is the max over its chunk scores");
        }

        // Ranking: doc-a's best chunk (0.9,...) beats doc-b's (0.7,...) beats doc-c's.
        Assertions.assertEquals(List.of("doc-a", "doc-b", "doc-c"),
                docHits.stream().map(Hit::getDocId).toList());

        // Summary.top_doc_ids mirrors the ranking with PARENT ids.
        List<SearchResponse> responses = search(docCentricRequest().build());
        Summary summary = responses.get(responses.size() - 1).getSummary();
        Assertions.assertEquals(List.of("doc-a", "doc-b", "doc-c"), summary.getTopDocIdsList());
    }

    @Test
    public void chunksPerHitCapsTheChunkList() {
        List<Hit> capped = hits(search(docCentricRequest().setChunksPerHit(1).build()));
        for (Hit hit : capped) {
            Assertions.assertEquals(1, hit.getChunksCount());
            Assertions.assertEquals(hit.getScore(), hit.getChunks(0).getScore(),
                    "the surviving chunk must be the best one");
        }
    }

    @Test
    public void parentFilterRestrictsDocuments() {
        // Filter matching one parent's title.
        SearchRequest filtered = SearchRequest.newBuilder()
                .setCollection(DC)
                .setSize(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("embedding")
                        .setVector(QUERY_VECTOR)
                        .setK(3)
                        .setDocumentCentric(true)
                        .setFilter(Query.newBuilder().setMatch(MatchQuery.newBuilder()
                                .setField("title").setText("beta")))))
                .build();
        List<Hit> hits = hits(search(filtered));
        Assertions.assertEquals(1, hits.size());
        Assertions.assertEquals("doc-b", hits.get(0).getDocId());

        // Filter matching nothing returns zero documents.
        SearchRequest none = SearchRequest.newBuilder()
                .setCollection(DC)
                .setSize(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("embedding")
                        .setVector(QUERY_VECTOR)
                        .setK(3)
                        .setDocumentCentric(true)
                        .setFilter(Query.newBuilder().setMatch(MatchQuery.newBuilder()
                                .setField("title").setText("absentterm")))))
                .build();
        Assertions.assertEquals(0, hits(search(none)).size());
    }

    @Test
    public void flatQueriesOnDocCentricCollectionsExcludeStubs() {
        // match_all must return the 6 chunks, never the 3 parent stubs.
        List<Hit> all = hits(search(SearchRequest.newBuilder()
                .setCollection(DC)
                .setSize(20)
                .setQuery(Query.newBuilder().setMatchAll(MatchAllQuery.getDefaultInstance()))
                .build()));
        Assertions.assertEquals(6, all.size(),
                "match_all returns chunks only; parent stubs must never surface");
    }

    @Test
    public void documentCentricRequiresDocCentricCollectionAndTopLevel() {
        // Flat collection: FAILED_PRECONDITION.
        io.grpc.StatusRuntimeException ex = Assertions.assertThrows(
                io.grpc.StatusRuntimeException.class,
                () -> search(SearchRequest.newBuilder()
                        .setCollection(FLAT)
                        .setSize(10)
                        .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                                .setField("vector").setVector(QUERY_VECTOR).setK(3)
                                .setDocumentCentric(true)))
                        .build()));
        Assertions.assertEquals(io.grpc.Status.Code.FAILED_PRECONDITION, ex.getStatus().getCode());

        // Nested under bool: INVALID_ARGUMENT.
        io.grpc.StatusRuntimeException nested = Assertions.assertThrows(
                io.grpc.StatusRuntimeException.class,
                () -> search(SearchRequest.newBuilder()
                        .setCollection(DC)
                        .setSize(10)
                        .setQuery(Query.newBuilder().setBool(BoolQuery.newBuilder()
                                .addMust(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                                        .setField("embedding").setVector(QUERY_VECTOR).setK(3)
                                        .setDocumentCentric(true)))))
                        .build()));
        Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, nested.getStatus().getCode());
    }
}
