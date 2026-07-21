package ai.pipestream.search.node;

import ai.pipestream.search.embeddings.RerankProvider;
import ai.pipestream.search.node.KnnNodeService.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests of the coordinator rerank stage ({@link RerankStage}). No Quarkus boot,
 * no network, no index: the stage is a pure function over the merged candidate list, and the
 * stub provider is passed directly, so no ServiceLoader registration is needed.
 */
public class RerankStageTest {

    /** Scores each document by a fixed text-to-score map; may also be rigged to drop scores. */
    private static final class StubProvider implements RerankProvider {
        private final Map<String, Float> scoresByText;
        private final int dropLast;

        StubProvider(Map<String, Float> scoresByText) {
            this(scoresByText, 0);
        }

        StubProvider(Map<String, Float> scoresByText, int dropLast) {
            this.scoresByText = scoresByText;
            this.dropLast = dropLast;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean supports(String model) {
            return true;
        }

        @Override
        public List<Float> score(String model, String query, List<String> documents) {
            return documents.stream()
                    .limit(documents.size() - dropLast)
                    .map(text -> scoresByText.getOrDefault(text, 0f))
                    .toList();
        }
    }

    private static SearchHit hit(long id, float score, String chunk) {
        return new SearchHit(id, score, chunk);
    }

    private static List<Long> ids(List<SearchHit> hits) {
        return hits.stream().map(h -> h.globalId).toList();
    }

    @Test
    public void nullProviderFallsBackToScoreSort() {
        List<SearchHit> candidates = List.of(
                hit(1, 0.5f, "alpha"), hit(2, 0.9f, "beta"), hit(3, 0.7f, "gamma"));
        List<SearchHit> out = RerankStage.apply(null, "m", "query", candidates, 2);
        assertEquals(List.of(2L, 3L), ids(out));
    }

    @Test
    public void blankQueryTextFallsBackToScoreSort() {
        StubProvider provider = new StubProvider(Map.of("alpha", 100f));
        List<SearchHit> candidates = List.of(
                hit(1, 0.5f, "alpha"), hit(2, 0.9f, "beta"));
        assertEquals(List.of(2L, 1L), ids(RerankStage.apply(provider, "m", "   ", candidates, 10)));
        assertEquals(List.of(2L, 1L), ids(RerankStage.apply(provider, "m", null, candidates, 10)));
    }

    @Test
    public void allBlankChunksFallBackToScoreSort() {
        // External-index mode: hits carry no chunk text, so rerank must skip.
        StubProvider provider = new StubProvider(Map.of());
        List<SearchHit> candidates = List.of(
                hit(1, 0.5f, ""), hit(2, 0.9f, null), hit(3, 0.7f, "  "));
        assertEquals(List.of(2L, 3L, 1L), ids(RerankStage.apply(provider, "m", "query", candidates, 10)));
    }

    @Test
    public void rerankReordersByProviderScoresAndTruncates() {
        StubProvider provider = new StubProvider(Map.of(
                "alpha", 0.1f, "beta", 0.9f, "gamma", 0.5f));
        List<SearchHit> candidates = List.of(
                hit(1, 0.9f, "alpha"), hit(2, 0.5f, "beta"), hit(3, 0.7f, "gamma"));
        List<SearchHit> out = RerankStage.apply(provider, "m", "query", candidates, 2);
        assertEquals(List.of(2L, 3L), ids(out));
    }

    @Test
    public void rerankWinBeatsKnnScoreLoss() {
        // kNN puts doc 1 first by a wide margin, but the cross-encoder ranks doc 4 on topic;
        // the rerank order wins and doc 1 falls out of the truncated top-2 entirely.
        StubProvider provider = new StubProvider(Map.of(
                "off topic but vector close", 0.01f,
                "somewhat related", 0.4f,
                "exactly the answer", 0.99f));
        List<SearchHit> candidates = List.of(
                hit(1, 0.99f, "off topic but vector close"),
                hit(2, 0.80f, "somewhat related"),
                hit(4, 0.10f, "exactly the answer"));
        List<SearchHit> out = RerankStage.apply(provider, "m", "query", candidates, 2);
        assertEquals(List.of(4L, 2L), ids(out));
    }

    @Test
    public void sizeMismatchThrowsNamingProviderAndModel() {
        StubProvider provider = new StubProvider(Map.of(), 1);
        List<SearchHit> candidates = List.of(hit(1, 0.5f, "alpha"), hit(2, 0.9f, "beta"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RerankStage.apply(provider, "bge-reranker", "query", candidates, 10));
        assertTrue(e.getMessage().contains("stub"), "message should name the provider: " + e.getMessage());
        assertTrue(e.getMessage().contains("bge-reranker"), "message should name the model: " + e.getMessage());
    }

    @Test
    public void appliesPredicateMatchesStageBehavior() {
        StubProvider provider = new StubProvider(Map.of());
        List<SearchHit> withText = List.of(hit(1, 0.5f, "alpha"));
        List<SearchHit> noText = List.of(hit(1, 0.5f, ""));
        assertTrue(RerankStage.applies(provider, "query", withText));
        assertTrue(!RerankStage.applies(null, "query", withText));
        assertTrue(!RerankStage.applies(provider, "", withText));
        assertTrue(!RerankStage.applies(provider, "query", noText));
    }
}
