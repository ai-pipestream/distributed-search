package ai.pipestream.search.query.knn;

import org.apache.lucene.sandbox.search.knn.FloorAwareKnnCollector;
import org.apache.lucene.sandbox.search.knn.GlobalKnnFloor;
import org.apache.lucene.sandbox.search.knn.SharedFloorKnnCollectorManager;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.SharedFloorDiversifyingKnnCollectorManager;

/**
 * Builds the fork's shared-floor collector managers with correct tuning.
 *
 * <p>Below {@code globalShare = 1} the greediness MUST be derived from the
 * clamp ({@link FloorAwareKnnCollector#greedinessForClamp}) rather than the
 * constant default: a constant greediness against a below-queue ascent gate
 * degenerates into a fixed per-shard quota — the fork's own benchmarked
 * warning.
 */
public final class DocumentCentricKnnFactory {

    private DocumentCentricKnnFactory() {
    }

    /**
     * @param k               parents the query collects per shard (floor.k() must equal it)
     * @param floor           the query's shared floor (one per query, never reused)
     * @param parents         parent-stub bitset producer
     * @param globalShare     fraction of the corpus this shard holds, in (0, 1]
     * @param floorActivationK smallest k at which floor sharing engages
     */
    public static SharedFloorDiversifyingKnnCollectorManager manager(
            int k, GlobalKnnFloor floor, BitSetProducer parents,
            float globalShare, int floorActivationK) {
        float greediness;
        if (globalShare < 1f) {
            int gate = SharedFloorKnnCollectorManager.perShardGate(k, globalShare);
            greediness = FloorAwareKnnCollector.greedinessForClamp(
                    gate, FloorAwareKnnCollector.DEFAULT_MIN_EXPLORATION_SLOTS);
        } else {
            greediness = FloorAwareKnnCollector.DEFAULT_GREEDINESS;
        }
        return new SharedFloorDiversifyingKnnCollectorManager(
                k, parents, floor, greediness, floorActivationK,
                FloorAwareKnnCollector.DEFAULT_MIN_EXPLORATION_SLOTS,
                FloorAwareKnnCollector.DEFAULT_SYNC_INTERVAL,
                globalShare);
    }
}
