package ai.pipestream.search.query;

import ai.pipestream.search.v1alpha1.Query;
import org.apache.lucene.search.TopDocs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Hybrid execution tests: sub-plans run over the same in-memory index and
 * their rankings are fused with RRF or weighted linear combination.
 */
class HybridExecutorTest {

    private static IndexFixture fixture;

    /** BM25 leg: "apple" ranks doc1 > doc2 > doc3 (term frequency 3/2/1). */
    private static final Query BM25_LEG = Ast.match("title", "apple");

    /** Vector leg: [1,0,0] ranks doc1 > doc4 > doc6 by cosine similarity. */
    private static final Query KNN_LEG = Ast.knn(Ast.knnBuilder("embedding", 3, 1.0f, 0.0f, 0.0f));

    @BeforeAll
    static void setUp() throws IOException {
        fixture = new IndexFixture();
    }

    @AfterAll
    static void tearDown() throws Exception {
        fixture.close();
    }

    @Test
    void rrfFusionOrdersByReciprocalRankSum() throws IOException {
        // The two legs agree on rank 1 but disagree afterwards.
        List<String> bm25Order = fixture.search(BM25_LEG, 3);
        List<String> knnOrder = fixture.search(KNN_LEG, 3);
        assertEquals(List.of("doc1", "doc2", "doc3"), bm25Order);
        assertEquals(List.of("doc1", "doc4", "doc6"), knnOrder);
        assertNotEquals(bm25Order, knnOrder);

        // RRF (k=60): doc1 = 2/61; doc2 = doc4 = 1/62 (tie broken by doc id);
        // doc3 = doc6 = 1/63.
        TopDocs fused = fixture.executor.execute(
                fixture.compile(Ast.hybrid(Ast.rrf(0), BM25_LEG, KNN_LEG)), fixture.searcher, 5);
        assertEquals(List.of("doc1", "doc2", "doc4", "doc3", "doc6"), fixture.ids(fused));
        assertEquals(1.0f / 61 + 1.0f / 61, fused.scoreDocs[0].score, 1e-6f);
        assertEquals(1.0f / 62, fused.scoreDocs[1].score, 1e-6f);
    }

    @Test
    void rrfHonorsCustomRankConstant() throws IOException {
        TopDocs fused = fixture.executor.execute(
                fixture.compile(Ast.hybrid(Ast.rrf(1), BM25_LEG, KNN_LEG)), fixture.searcher, 5);
        assertEquals(1.0f / 2 + 1.0f / 2, fused.scoreDocs[0].score, 1e-6f);
    }

    @Test
    void rrfIsTheDefaultFusion() {
        QueryPlan plan = fixture.compile(Ast.hybrid(null, BM25_LEG, KNN_LEG));
        QueryPlan.Hybrid hybrid = assertInstanceOf(QueryPlan.Hybrid.class, plan);
        assertEquals(new QueryPlan.FusionSpec.Rrf(QueryCompiler.DEFAULT_RRF_K), hybrid.fusion());
    }

    @Test
    void linearFusionWithBm25OnlyWeightFollowsBm25Order() throws IOException {
        List<String> fused = fixture.search(Ast.hybrid(Ast.linear(1.0f, 0.0f), BM25_LEG, KNN_LEG), 3);
        assertEquals(List.of("doc1", "doc2", "doc3"), fused);
    }

    @Test
    void linearFusionFavorsHeavierWeight() throws IOException {
        // 0.9 on the vector leg pushes doc4 (vector rank 2) above doc2 (BM25 rank 2).
        List<String> fused = fixture.search(Ast.hybrid(Ast.linear(0.1f, 0.9f), BM25_LEG, KNN_LEG), 3);
        assertEquals(List.of("doc1", "doc4", "doc2"), fused);
    }

    @Test
    void hybridPlanAggregatesKnnHints() {
        QueryPlan plan = fixture.compile(Ast.hybrid(Ast.rrf(0),
                BM25_LEG,
                Ast.knn(Ast.knnBuilder("embedding", 3, 1.0f, 0.0f, 0.0f)
                        .setCollaborative(true)
                        .setVisitBudget(500))));
        assertEquals(List.of(new QueryPlan.KnnHints("embedding", true, 500, false, 3)), plan.knnHints());
    }

    @Test
    void nestedHybridExecutes() throws IOException {
        // A hybrid leg inside a hybrid: fused recursively, no clause lost.
        Query nested = Ast.hybrid(Ast.rrf(0),
                Ast.hybrid(Ast.rrf(0), BM25_LEG, KNN_LEG),
                Ast.match("title", "banana"));
        QueryPlan.Hybrid hybrid = assertInstanceOf(QueryPlan.Hybrid.class, fixture.compile(nested));
        assertInstanceOf(QueryPlan.Hybrid.class, hybrid.subPlans().get(0));
        // Outer RRF over [doc1,doc2,doc4,doc3,doc6] (inner hybrid) and
        // [doc4,doc3,doc2] ("banana" by tf): doc4 = 1/63+1/61 edges out
        // doc2 = 1/62+1/63 and doc3 = 1/64+1/62; doc1 = 1/61 trails them.
        assertEquals(List.of("doc4", "doc2", "doc3", "doc1", "doc6"), fixture.search(nested, 6));
    }

    @Test
    void singlePlanExecutesDirectly() throws IOException {
        assertEquals(6, fixture.search(Ast.matchAll(), 10).size());
    }

    @Test
    void linearWeightsLengthMismatchRejected() {
        assertThrows(InvalidQueryException.class,
                () -> fixture.compile(Ast.hybrid(Ast.linear(1.0f), BM25_LEG, KNN_LEG)));
    }

    @Test
    void boostOnHybridRejected() {
        assertThrows(InvalidQueryException.class,
                () -> fixture.compile(Ast.boosted(Ast.hybrid(Ast.rrf(0), BM25_LEG, KNN_LEG), 2.0f)));
    }
}
