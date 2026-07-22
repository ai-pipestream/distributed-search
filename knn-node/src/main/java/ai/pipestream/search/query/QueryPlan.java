package ai.pipestream.search.query;

import java.util.List;

/**
 * The compiled form of a v1alpha1 Query: either a single executable Lucene
 * query, or a hybrid node holding sub-plans plus the fusion spec used to
 * merge their rankings.
 *
 * <p>Each node carries the execution hints of any {@code knn} clauses it
 * contains ({@code collaborative}, {@code visit_budget}). The compiler only
 * records them; the execution layer wires the matching collector managers.
 */
public sealed interface QueryPlan permits QueryPlan.Single, QueryPlan.Hybrid {

    /**
     * Execution hints for the knn clauses contained in this node (empty when
     * the node has none). For {@link Hybrid} this aggregates over sub-plans.
     */
    List<KnnHints> knnHints();

    /**
     * Per-knn-clause execution hints, carried verbatim from the AST.
     *
     * @param field           the dense_vector field the clause targets
     * @param collaborative   cross-shard collaborative traversal requested
     * @param visitBudget     max graph nodes this clause may visit (summed
     *                        across shards); 0 = inherit SearchBudget.max_visits
     * @param documentCentric top-k documents with per-chunk scores requested
     * @param k               the clause's requested top-k (execution trims to
     *                        it when num_candidates widened the Lucene k)
     */
    record KnnHints(String field, boolean collaborative, long visitBudget,
                    boolean documentCentric, int k) {}

    /** A plan that compiles to one executable Lucene query. */
    record Single(org.apache.lucene.search.Query query, List<KnnHints> knnHints) implements QueryPlan {

        public Single {
            knnHints = List.copyOf(knnHints);
        }
    }

    /** A hybrid plan: sub-plans executed independently, rankings fused. */
    record Hybrid(List<QueryPlan> subPlans, FusionSpec fusion) implements QueryPlan {

        public Hybrid {
            subPlans = List.copyOf(subPlans);
        }

        @Override
        public List<KnnHints> knnHints() {
            return subPlans.stream().flatMap(plan -> plan.knnHints().stream()).toList();
        }
    }

    /** How hybrid sub-query rankings are fused into one ranking. */
    sealed interface FusionSpec permits FusionSpec.Rrf, FusionSpec.Linear {

        /**
         * Reciprocal Rank Fusion: score(d) = sum over sub-queries of
         * 1 / (k + rank(d)), rank 1-based.
         */
        record Rrf(int k) implements FusionSpec {}

        /**
         * Weighted linear combination of min-max normalized sub-query
         * scores; weights are parallel to the hybrid sub-plans.
         */
        record Linear(List<Float> weights) implements FusionSpec {

            public Linear {
                weights = List.copyOf(weights);
            }
        }
    }
}
