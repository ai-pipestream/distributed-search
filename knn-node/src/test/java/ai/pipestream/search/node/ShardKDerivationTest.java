package ai.pipestream.search.node;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests of the coordinator's per-shard k derivation
 * ({@link KnnResource#deriveShardK}). No Quarkus boot: the derivation is a pure function
 * of k, the explicit perShardK override, the collaborative flag, and the number of
 * shards actually searched.
 */
public class ShardKDerivationTest {

    @Test
    public void singleShardKeepsFullK() {
        // One searched shard holds the whole corpus (share 1.0), so the gate is k itself.
        assertEquals(10000, KnnResource.deriveShardK(10000, -1, true, 1));
        assertEquals(10, KnnResource.deriveShardK(10, -1, true, 1));
    }

    @Test
    public void sixteenShardsDeriveSharedFloorGate() {
        // perShardGate(10000, 1/16): 10000/16 plus 16 binomial standard deviations.
        assertEquals(1012, KnnResource.deriveShardK(10000, -1, true, 16));
    }

    @Test
    public void explicitPerShardKWinsOverDerivation() {
        // Validated upstream to be >= k, so it may oversample past the derived value.
        assertEquals(2000, KnnResource.deriveShardK(10000, 2000, true, 16));
        assertEquals(10000, KnnResource.deriveShardK(10000, 10000, true, 1));
    }

    @Test
    public void nonCollaborativeKeepsFullK() {
        assertEquals(10000, KnnResource.deriveShardK(10000, -1, false, 16));
        assertEquals(10, KnnResource.deriveShardK(10, -1, false, 1));
    }

    @Test
    public void derivedShardKNeverExceedsK() {
        for (int k : new int[] {1, 10, 100, 1000, 10000}) {
            for (int s : new int[] {1, 2, 3, 4, 8, 16, 64}) {
                int shardK = KnnResource.deriveShardK(k, -1, true, s);
                assertTrue(shardK <= k, "shardK=" + shardK + " exceeded k=" + k + " at s=" + s);
                assertTrue(shardK >= 1, "shardK=" + shardK + " below 1 at k=" + k + ", s=" + s);
            }
        }
    }
}
