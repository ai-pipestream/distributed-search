package ai.pipestream.search.query;

import ai.pipestream.search.v1alpha1.BoolQuery;
import ai.pipestream.search.v1alpha1.Operator;
import ai.pipestream.search.v1alpha1.Query;
import org.apache.lucene.search.BoostQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit compiler tests (no Quarkus boot): each AST node type is compiled
 * against {@link IndexFixture#SCHEMA} and executed on the 6-doc in-memory
 * index; assertions check the returned doc ids.
 */
class QueryCompilerTest {

    private static IndexFixture fixture;

    @BeforeAll
    static void setUp() throws IOException {
        fixture = new IndexFixture();
    }

    @AfterAll
    static void tearDown() throws Exception {
        fixture.close();
    }

    // -- term ---------------------------------------------------------------

    @Test
    void termOnKeywordField() throws IOException {
        assertEquals(Set.of("doc1", "doc2", "doc3"),
                Set.copyOf(fixture.search(Ast.term("category", Ast.str("fruit")), 10)));
    }

    @Test
    void termOnLongField() throws IOException {
        assertEquals(List.of("doc4"), fixture.search(Ast.term("price", Ast.int64(40)), 10));
    }

    @Test
    void termOnDoubleField() throws IOException {
        assertEquals(List.of("doc5"), fixture.search(Ast.term("rating", Ast.dbl(5.0)), 10));
    }

    @Test
    void termOnBooleanField() throws IOException {
        assertEquals(Set.of("doc1", "doc3", "doc5", "doc6"),
                Set.copyOf(fixture.search(Ast.term("active", Ast.flag(true)), 10)));
        assertEquals(Set.of("doc2", "doc4"),
                Set.copyOf(fixture.search(Ast.term("active", Ast.flag(false)), 10)));
    }

    @Test
    void termOnDateField() throws IOException {
        assertEquals(List.of("doc1"),
                fixture.search(Ast.term("created", Ast.ts("2026-01-01T00:00:00Z")), 10));
    }

    // -- match --------------------------------------------------------------

    @Test
    void matchCombinesTokensWithOr() throws IOException {
        assertEquals(Set.of("doc1", "doc2", "doc3", "doc4"),
                Set.copyOf(fixture.search(Ast.match("title", "apple banana"), 10)));
    }

    @Test
    void matchCombinesTokensWithAnd() throws IOException {
        assertEquals(Set.of("doc2", "doc3"),
                Set.copyOf(fixture.search(Ast.match("title", "apple banana", Operator.OPERATOR_AND), 10)));
    }

    @Test
    void matchUsesFieldAnalyzer() throws IOException {
        // "runs" stems to "run" via the body field's english analyzer, so it
        // matches "running shoes" (doc1) and "run fast" (doc2).
        assertEquals(Set.of("doc1", "doc2"),
                Set.copyOf(fixture.search(Ast.match("body", "runs"), 10)));
    }

    @Test
    void matchMinimumShouldMatch() throws IOException {
        // Only doc2 and doc3 contain at least two of {apple, banana, fox}.
        assertEquals(Set.of("doc2", "doc3"),
                Set.copyOf(fixture.search(Ast.matchMsm("title", "apple banana fox", 2), 10)));
    }

    // -- phrase ---------------------------------------------------------------

    @Test
    void phraseMatchesExactSequence() throws IOException {
        assertEquals(List.of("doc5"), fixture.search(Ast.phrase("title", "quick brown fox", 0), 10));
        assertEquals(List.of(), fixture.search(Ast.phrase("title", "brown quick fox", 0), 10));
    }

    @Test
    void phraseRespectsSlop() throws IOException {
        assertEquals(List.of(), fixture.search(Ast.phrase("title", "quick fox", 0), 10));
        assertEquals(List.of("doc5"), fixture.search(Ast.phrase("title", "quick fox", 1), 10));
    }

    // -- range ----------------------------------------------------------------

    @Test
    void rangeOnLongField() throws IOException {
        // price gt 20, lte 50
        assertEquals(Set.of("doc3", "doc4", "doc5"),
                Set.copyOf(fixture.search(Ast.range("price", Ast.int64(20), null, null, Ast.int64(50)), 10)));
    }

    @Test
    void rangeOnDoubleField() throws IOException {
        // rating gte 4.0, lt 6.0
        assertEquals(Set.of("doc4", "doc5"),
                Set.copyOf(fixture.search(Ast.range("rating", null, Ast.dbl(4.0), Ast.dbl(6.0), null), 10)));
    }

    @Test
    void rangeOnDateField() throws IOException {
        // created gte 2026-03-01, lt 2026-05-01
        assertEquals(Set.of("doc3", "doc4"),
                Set.copyOf(fixture.search(Ast.range("created",
                        null, Ast.ts("2026-03-01T00:00:00Z"), Ast.ts("2026-05-01T00:00:00Z"), null), 10)));
    }

    @Test
    void rangeOnKeywordField() throws IOException {
        // category gte "g" — lexicographically above "fruit", below "veggie"
        assertEquals(Set.of("doc4", "doc5", "doc6"),
                Set.copyOf(fixture.search(Ast.range("category", null, Ast.str("g"), null, null), 10)));
    }

    // -- bool -----------------------------------------------------------------

    @Test
    void boolMustFilterMustNot() throws IOException {
        Query query = Ast.bool(BoolQuery.newBuilder()
                .addMust(Ast.match("title", "apple"))
                .addFilter(Ast.term("active", Ast.flag(true)))
                .addMustNot(Ast.term("category", Ast.str("veggie")))
                .build());
        assertEquals(Set.of("doc1", "doc3"), Set.copyOf(fixture.search(query, 10)));
    }

    @Test
    void boolMinimumShouldMatch() throws IOException {
        // Two of: title contains apple / active / price >= 50.
        Query query = Ast.bool(BoolQuery.newBuilder()
                .addShould(Ast.match("title", "apple"))
                .addShould(Ast.term("active", Ast.flag(true)))
                .addShould(Ast.range("price", null, Ast.int64(50), null, null))
                .setMinimumShouldMatch(2)
                .build());
        assertEquals(Set.of("doc1", "doc3", "doc5", "doc6"), Set.copyOf(fixture.search(query, 10)));
    }

    @Test
    void boolPureNegationMatchesEverythingElse() throws IOException {
        Query query = Ast.bool(BoolQuery.newBuilder()
                .addMustNot(Ast.term("category", Ast.str("fruit")))
                .build());
        assertEquals(Set.of("doc4", "doc5", "doc6"), Set.copyOf(fixture.search(query, 10)));
    }

    // -- knn ------------------------------------------------------------------

    @Test
    void knnReturnsNearestNeighbors() throws IOException {
        assertEquals(List.of("doc1", "doc4", "doc6"),
                fixture.search(Ast.knn(Ast.knnBuilder("embedding", 3, 1.0f, 0.0f, 0.0f)), 3));
    }

    @Test
    void knnPreFilterRestrictsCandidates() throws IOException {
        // Unfiltered, doc1 is the nearest neighbor of [1, 0, 0]...
        assertEquals(List.of("doc1"),
                fixture.search(Ast.knn(Ast.knnBuilder("embedding", 1, 1.0f, 0.0f, 0.0f)), 1));
        // ...with a category=veggie pre-filter it never enters the candidate set.
        List<String> filtered = fixture.search(Ast.knn(
                Ast.knnBuilder("embedding", 2, 1.0f, 0.0f, 0.0f)
                        .setFilter(Ast.term("category", Ast.str("veggie")))), 2);
        assertEquals(List.of("doc4", "doc6"), filtered);
        assertFalse(filtered.contains("doc1"));
    }

    @Test
    void knnHintsCarriedOnPlan() {
        QueryPlan plan = fixture.compile(Ast.knn(
                Ast.knnBuilder("embedding", 3, 1.0f, 0.0f, 0.0f)
                        .setCollaborative(true)
                        .setVisitBudget(1234)));
        QueryPlan.Single single = assertInstanceOf(QueryPlan.Single.class, plan);
        assertEquals(List.of(new QueryPlan.KnnHints("embedding", true, 1234, false, 3)), single.knnHints());
    }

    @Test
    void knnHintsCollectedInsideBool() {
        Query query = Ast.bool(BoolQuery.newBuilder()
                .addMust(Ast.match("title", "apple"))
                .addMust(Ast.knn(Ast.knnBuilder("embedding", 2, 1.0f, 0.0f, 0.0f).setVisitBudget(99)))
                .build());
        QueryPlan plan = fixture.compile(query);
        assertEquals(List.of(new QueryPlan.KnnHints("embedding", false, 99, false, 2)), plan.knnHints());
    }

    // -- match_all / query_string / boost --------------------------------------

    @Test
    void matchAllMatchesEveryDocument() throws IOException {
        assertEquals(6, fixture.search(Ast.matchAll(), 10).size());
    }

    @Test
    void queryStringParsesLuceneSyntax() throws IOException {
        Query query = Ast.queryString("apple AND banana", "title", Operator.OPERATOR_UNSPECIFIED);
        assertEquals(Set.of("doc2", "doc3"), Set.copyOf(fixture.search(query, 10)));
    }

    @Test
    void queryStringHonorsDefaultOperator() throws IOException {
        Query query = Ast.queryString("apple banana", "title", Operator.OPERATOR_AND);
        assertEquals(Set.of("doc2", "doc3"), Set.copyOf(fixture.search(query, 10)));
    }

    @Test
    void boostWrapsCompiledQuery() {
        QueryPlan plan = fixture.compile(Ast.boosted(Ast.term("category", Ast.str("fruit")), 2.5f));
        QueryPlan.Single single = assertInstanceOf(QueryPlan.Single.class, plan);
        BoostQuery boosted = assertInstanceOf(BoostQuery.class, single.query());
        assertEquals(2.5f, boosted.getBoost());
    }

    // -- invalid queries --------------------------------------------------------

    @Test
    void unknownFieldRejected() {
        InvalidQueryException e = assertThrows(InvalidQueryException.class,
                () -> fixture.compile(Ast.term("nope", Ast.str("x"))));
        assertTrue(e.getMessage().contains("Unknown field 'nope'"));
    }

    @Test
    void termOnTextFieldRejected() {
        assertThrows(InvalidQueryException.class,
                () -> fixture.compile(Ast.term("title", Ast.str("apple"))));
    }

    @Test
    void termValueTypeMismatchRejected() {
        assertThrows(InvalidQueryException.class,
                () -> fixture.compile(Ast.term("category", Ast.int64(7))));
    }

    @Test
    void unknownAnalyzerRejected() {
        InvalidQueryException e = assertThrows(InvalidQueryException.class,
                () -> fixture.compile(Ast.matchWithAnalyzer("title", "apple", "bogus")));
        assertTrue(e.getMessage().contains("Unknown analyzer 'bogus'"));
    }

    @Test
    void hybridInsideBoolRejected() {
        Query nested = Ast.bool(BoolQuery.newBuilder()
                .addMust(Ast.hybrid(Ast.rrf(0), Ast.matchAll()))
                .build());
        assertThrows(InvalidQueryException.class, () -> fixture.compile(nested));
    }

    @Test
    void knnDimensionMismatchRejected() {
        assertThrows(InvalidQueryException.class,
                () -> fixture.compile(Ast.knn(Ast.knnBuilder("embedding", 3, 1.0f, 0.0f))));
    }

    @Test
    void minimumShouldMatchOutOfRangeRejected() {
        Query query = Ast.bool(BoolQuery.newBuilder()
                .addShould(Ast.matchAll())
                .setMinimumShouldMatch(2)
                .build());
        assertThrows(InvalidQueryException.class, () -> fixture.compile(query));
    }

    @Test
    void queryStringUnparsableRejected() {
        assertThrows(InvalidQueryException.class,
                () -> fixture.compile(Ast.queryString("title:(apple", "title", Operator.OPERATOR_UNSPECIFIED)));
    }
}
