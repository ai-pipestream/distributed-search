package ai.pipestream.search.node;

import ai.pipestream.search.embeddings.RerankProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The coordinator's optional rerank stage, applied to the merged shard candidates just
 * before top-k truncation. Pure function over plain JDK types so it stays unit-testable
 * without Quarkus, gRPC, or an index.
 *
 * <p>Fallback rule: with no provider, a blank query text, or no candidate carrying chunk
 * text (external-index hits store no chunk), the stage is exactly the legacy behavior —
 * sort by kNN score descending, truncate to k. Otherwise the provider rescores every
 * candidate's chunk against the query text and the list is reordered by rerank score
 * descending before truncation.
 */
final class RerankStage {

    private RerankStage() {}

    /** Whether {@link #apply} will rerank (true) or pass through with the legacy sort (false). */
    static boolean applies(RerankProvider provider, String queryText, List<KnnNodeService.SearchHit> candidates) {
        return provider != null
                && queryText != null && !queryText.isBlank()
                && candidates.stream().anyMatch(h -> h.chunk != null && !h.chunk.isBlank());
    }

    /**
     * The final hit list for one merged query: reranked when {@link #applies}, legacy
     * score-sorted passthrough otherwise. Always truncated to at most k entries.
     *
     * @throws IllegalStateException if the provider returns a score count that does not
     *         match the candidate count
     */
    static List<KnnNodeService.SearchHit> apply(RerankProvider provider, String model, String queryText,
                                                List<KnnNodeService.SearchHit> candidates, int k) {
        if (!applies(provider, queryText, candidates)) {
            List<KnnNodeService.SearchHit> sorted = new ArrayList<>(candidates);
            sorted.sort((a, b) -> Float.compare(b.score, a.score));
            return truncate(sorted, k);
        }
        List<String> texts = new ArrayList<>(candidates.size());
        for (KnnNodeService.SearchHit hit : candidates) {
            texts.add(hit.chunk == null ? "" : hit.chunk);
        }
        List<Float> scores = provider.score(model, queryText, texts);
        if (scores.size() != candidates.size()) {
            throw new IllegalStateException(
                    "Rerank provider '" + provider.name() + "' (model '" + model + "') returned "
                            + scores.size() + " scores for " + candidates.size() + " candidates");
        }
        Integer[] order = new Integer[candidates.size()];
        Arrays.setAll(order, i -> i);
        // Stable sort: equal rerank scores keep the merged kNN order.
        Arrays.sort(order, (a, b) -> Float.compare(scores.get(b), scores.get(a)));
        List<KnnNodeService.SearchHit> reranked = new ArrayList<>(candidates.size());
        for (int index : order) {
            reranked.add(candidates.get(index));
        }
        return truncate(reranked, k);
    }

    private static List<KnnNodeService.SearchHit> truncate(List<KnnNodeService.SearchHit> sorted, int k) {
        return sorted.size() > k ? new ArrayList<>(sorted.subList(0, k)) : sorted;
    }
}
