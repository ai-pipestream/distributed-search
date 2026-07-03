package ai.pipestream.search.query;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a {@link QueryPlan} against an IndexSearcher. {@link QueryPlan.Single}
 * plans run directly; {@link QueryPlan.Hybrid} plans run each sub-plan
 * independently over the same searcher and fuse the rankings into one TopDocs.
 *
 * <p>Fusion semantics:
 * <ul>
 *   <li><b>RRF</b>: score(d) = sum over sub-queries of 1 / (k + rank(d)),
 *       ranks 1-based within each sub-query's top-{@code k} window.</li>
 *   <li><b>Linear</b>: each sub-query's scores are min-max normalized to
 *       [0, 1] within its result window (a constant-score window normalizes
 *       to 1.0), then combined as a weighted sum. Documents absent from a
 *       sub-query's window contribute 0 for that sub-query.</li>
 * </ul>
 * Score ties break by Lucene doc id ascending, so fused rankings are
 * deterministic.
 *
 * <p>The knn execution hints on the plan ({@code collaborative},
 * {@code visit_budget}) are not interpreted here; the shard execution layer
 * wires the matching collector managers.
 */
@ApplicationScoped
public class HybridExecutor {

    /**
     * Run the plan and return the top {@code k} fused (or plain) results.
     */
    public TopDocs execute(QueryPlan plan, IndexSearcher searcher, int k) throws IOException {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got " + k);
        }
        return switch (plan) {
            case QueryPlan.Single single -> searcher.search(single.query(), k);
            case QueryPlan.Hybrid hybrid -> executeHybrid(hybrid, searcher, k);
        };
    }

    private TopDocs executeHybrid(QueryPlan.Hybrid hybrid, IndexSearcher searcher, int k) throws IOException {
        List<TopDocs> results = new ArrayList<>(hybrid.subPlans().size());
        for (QueryPlan subPlan : hybrid.subPlans()) {
            results.add(execute(subPlan, searcher, k));
        }
        Map<Integer, Float> fused = switch (hybrid.fusion()) {
            case QueryPlan.FusionSpec.Rrf rrf -> fuseRrf(results, rrf.k());
            case QueryPlan.FusionSpec.Linear linear -> fuseLinear(results, linear.weights());
        };
        ScoreDoc[] ranked = fused.entrySet().stream()
                .map(entry -> new ScoreDoc(entry.getKey(), entry.getValue()))
                .sorted(Comparator.<ScoreDoc>comparingDouble(scoreDoc -> scoreDoc.score).reversed()
                        .thenComparingInt(scoreDoc -> scoreDoc.doc))
                .limit(k)
                .toArray(ScoreDoc[]::new);
        return new TopDocs(new TotalHits(fused.size(), TotalHits.Relation.EQUAL_TO), ranked);
    }

    private static Map<Integer, Float> fuseRrf(List<TopDocs> results, int rrfK) {
        Map<Integer, Float> scores = new HashMap<>();
        for (TopDocs result : results) {
            ScoreDoc[] docs = result.scoreDocs;
            for (int rank = 1; rank <= docs.length; rank++) {
                scores.merge(docs[rank - 1].doc, 1.0f / (rrfK + rank), Float::sum);
            }
        }
        return scores;
    }

    private static Map<Integer, Float> fuseLinear(List<TopDocs> results, List<Float> weights) {
        if (weights.size() != results.size()) {
            throw new IllegalArgumentException("linear fusion has " + weights.size()
                    + " weights for " + results.size() + " sub-queries");
        }
        Map<Integer, Float> scores = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            float weight = weights.get(i);
            ScoreDoc[] docs = results.get(i).scoreDocs;
            if (docs.length == 0) {
                continue;
            }
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (ScoreDoc doc : docs) {
                min = Math.min(min, doc.score);
                max = Math.max(max, doc.score);
            }
            for (ScoreDoc doc : docs) {
                float normalized = max > min ? (doc.score - min) / (max - min) : 1.0f;
                scores.merge(doc.doc, weight * normalized, Float::sum);
            }
        }
        return scores;
    }
}
