package ai.pipestream.search.query.knn;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.sandbox.search.knn.SharedFloorKnnCollectorManager;
import org.apache.lucene.search.AcceptDocs;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TimeLimitingKnnCollectorManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.DiversifyingChildrenFloatKnnVectorQuery;
import org.apache.lucene.search.knn.KnnCollectorManager;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;

import java.io.IOException;
import java.util.Objects;

/**
 * Document-centric kNN with a shared parent-score floor: a
 * DiversifyingChildren block-join query whose segment searches prune against
 * a {@code GlobalKnnFloor} shared by every searcher of the query (all local
 * shards today; remote shards once the floor is transported).
 *
 * <p>Two overrides:
 * <ul>
 *   <li>{@link #getKnnCollectorManager} swaps in the fork's
 *       SharedFloorDiversifyingKnnCollectorManager (built by
 *       {@link DocumentCentricKnnFactory}).</li>
 *   <li>{@link #searchLeaf} re-states the exact-search fallback predicate in
 *       PARENT units. Core compares {@code scoreDocs.length} (a parent
 *       count, one per parent after diversification) against a chunk-derived
 *       {@code perLeafTopK} quota, so whenever k exceeds the parents in a
 *       leaf every FILTERED search silently falls back to a full exact scan
 *       — erasing the optimization in exactly the RAG shape this engine
 *       targets. A leaf can never return more parents than it has: the
 *       achievable quota is {@code min(perLeafTopK, parentsInLeaf)}.</li>
 * </ul>
 */
public class SharedFloorParentJoinQuery extends DiversifyingChildrenFloatKnnVectorQuery {

    private final BitSetProducer parents;
    private final KnnCollectorManager collectorManager;
    /** Shadowed: the superclass keeps its k private. */
    private final int topK;

    public SharedFloorParentJoinQuery(String field, float[] target, Query childFilter, int k,
                                      BitSetProducer parentsFilter,
                                      KnnCollectorManager collectorManager) {
        super(field, target, childFilter, k, parentsFilter);
        this.parents = parentsFilter;
        this.topK = k;
        this.collectorManager = Objects.requireNonNull(collectorManager, "collectorManager");
    }

    @Override
    protected KnnCollectorManager getKnnCollectorManager(int k, IndexSearcher searcher) {
        return collectorManager;
    }

    @Override
    protected TopDocs searchLeaf(LeafReaderContext ctx, Weight filterWeight,
                                 TimeLimitingKnnCollectorManager knnCollectorManager)
            throws IOException {
        TopDocs results = leafResults(ctx, filterWeight, knnCollectorManager);
        if (ctx.docBase > 0) {
            for (org.apache.lucene.search.ScoreDoc scoreDoc : results.scoreDocs) {
                scoreDoc.doc += ctx.docBase;
            }
        }
        return results;
    }

    /**
     * Core's {@code getLeafResults} with one change: the post-approximate
     * fallback predicate compares against the achievable parent count.
     */
    private TopDocs leafResults(LeafReaderContext ctx, Weight filterWeight,
                                TimeLimitingKnnCollectorManager knnCollectorManager)
            throws IOException {
        Bits liveDocs = ctx.reader().getLiveDocs();

        if (filterWeight == null) {
            AcceptDocs acceptDocs = AcceptDocs.fromLiveDocs(liveDocs, ctx.reader().maxDoc());
            return approximateSearch(ctx, acceptDocs, Integer.MAX_VALUE, knnCollectorManager);
        }

        AcceptDocs acceptDocs = AcceptDocs.fromIteratorSupplier(
                () -> {
                    Scorer scorer = filterWeight.scorer(ctx);
                    return scorer == null ? DocIdSetIterator.empty() : scorer.iterator();
                },
                liveDocs,
                ctx.reader().maxDoc());
        int cost = acceptDocs.cost();
        org.apache.lucene.index.QueryTimeout queryTimeout = knnCollectorManager.getQueryTimeout();

        int perLeafTopK;
        if (ctx.parent != null) {
            // perShardGate mirrors core's perLeafTopKCalculation.
            float leafProportion = ctx.reader().maxDoc() / (float) ctx.parent.reader().maxDoc();
            perLeafTopK = SharedFloorKnnCollectorManager.perShardGate(topK, leafProportion);
        } else {
            perLeafTopK = topK;
        }

        if (cost <= perLeafTopK) {
            // Fewer accepted children than HNSW would have to visit anyway.
            return exactSearch(ctx, acceptDocs.iterator(), queryTimeout);
        }

        TopDocs results = approximateSearch(ctx, acceptDocs, cost + 1, knnCollectorManager);

        // PARENT units: this leaf cannot return more parents than it holds.
        BitSet parentBits = parents.getBitSet(ctx);
        int parentsInLeaf = parentBits == null ? 0 : parentBits.cardinality();
        int achievable = Math.min(perLeafTopK, parentsInLeaf);

        if ((results.totalHits.relation() == TotalHits.Relation.EQUAL_TO
                && results.scoreDocs.length >= achievable)
                || (queryTimeout != null && queryTimeout.shouldExit())) {
            return results;
        }
        return exactSearch(ctx, acceptDocs.iterator(), queryTimeout);
    }
}
