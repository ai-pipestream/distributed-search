package ai.pipestream.search.index.placement;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * P5 placement proofs. Placement is pure, so these run as plain JUnit with
 * no index: determinism, the pinned rotation literal (catches a time-seeded
 * hash on the SECOND JVM run), tie-break contiguity, blob cohesion despite
 * ordinal interleaving, and the balance cap.
 */
class BalancedNearestNeighbourChainPlacementTest {

    private static final BalancedNearestNeighbourChainPlacement POLICY =
            new BalancedNearestNeighbourChainPlacement();

    private static List<float[]> randomVectors(int n, int dims, long seed) {
        Random random = new Random(seed);
        List<float[]> vectors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dims];
            float norm = 0;
            for (int j = 0; j < dims; j++) {
                v[j] = random.nextFloat() + 0.01f;
                norm += v[j] * v[j];
            }
            norm = (float) Math.sqrt(norm);
            for (int j = 0; j < dims; j++) {
                v[j] /= norm;
            }
            vectors.add(v);
        }
        return vectors;
    }

    /**
     * T3: the rotation is a pure function of (docId, S) with a PINNED seed.
     * A time-seeded hash (StringHelper.GOOD_FAST_HASH_SEED) passes every
     * same-JVM test and breaks on the second JVM start — only a hardcoded
     * literal catches it.
     */
    @Test
    void rotationOffsetIsPinnedAcrossJvms() {
        Assertions.assertEquals(4,
                BalancedNearestNeighbourChainPlacement.rotationOffset("doc-0001", 8));
        Assertions.assertEquals(1,
                BalancedNearestNeighbourChainPlacement.rotationOffset("doc-0001", 3));
        Assertions.assertEquals(0,
                BalancedNearestNeighbourChainPlacement.rotationOffset("article-1", 2));
        Assertions.assertEquals(2,
                BalancedNearestNeighbourChainPlacement.rotationOffset("alpha", 4));
    }

    @Test
    void placementIsDeterministic() {
        PlacementRequest request = new PlacementRequest(
                "doc-x", randomVectors(50, 8, 42), 4, VectorSimilarityFunction.COSINE);
        ChunkPlacement first = POLICY.place(request);
        ChunkPlacement second = POLICY.place(request);
        Assertions.assertArrayEquals(first.shardOfChunk(), second.shardOfChunk());
    }

    /**
     * T6: identical vectors tie on every comparison; the strict tie-break
     * must produce contiguous ordinal runs (the positional fallback), i.e.
     * shard[i] == (rotation + i/cap) mod S. Catches non-strict comparisons
     * and any PRNG.
     */
    @Test
    void identicalVectorsDegradeToContiguousRuns() {
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            vectors.add(new float[]{1f, 0f, 0f, 0f});
        }
        ChunkPlacement placement = POLICY.place(new PlacementRequest(
                "doc-0001", vectors, 3, VectorSimilarityFunction.COSINE));
        int rotation = BalancedNearestNeighbourChainPlacement.rotationOffset("doc-0001", 3);
        Assertions.assertEquals(4, placement.capacity());
        for (int i = 0; i < 12; i++) {
            Assertions.assertEquals((rotation + i / 4) % 3, placement.shardOfChunk()[i],
                    "identical vectors must fall back to contiguous ordinal runs at chunk " + i);
        }
    }

    /**
     * T10: chunks of the same similarity blob must cluster together even
     * when their ordinals interleave. Catches cluster = ordinal / cap.
     */
    @Test
    void interleavedBlobsClusterBySimilarityNotOrdinal() {
        // 4 well-separated blobs of 4, interleaved by ordinal:
        // ordinal i belongs to blob i % 4.
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            float[] v = new float[4];
            v[i % 4] = 1f;
            v[(i % 4 + 1) % 4] = 0.01f * (i / 4);   // tiny within-blob variation
            vectors.add(v);
        }
        ChunkPlacement placement = POLICY.place(new PlacementRequest(
                "doc-blobs", vectors, 4, VectorSimilarityFunction.COSINE));

        // Every blob must land wholly on one shard.
        for (int blob = 0; blob < 4; blob++) {
            int shard = placement.shardOfChunk()[blob];
            for (int i = blob; i < 16; i += 4) {
                Assertions.assertEquals(shard, placement.shardOfChunk()[i],
                        "blob " + blob + " split across shards (ordinal " + i + "); "
                                + "placement must follow similarity, not ordinal/cap");
            }
        }
        // And a positional split (ordinal / cap) would fail the above by
        // construction: ordinals 0..3 are four DIFFERENT blobs.
    }

    @Test
    void capacityIsNeverExceeded() {
        ChunkPlacement placement = POLICY.place(new PlacementRequest(
                "doc-cap", randomVectors(23, 6, 7), 4, VectorSimilarityFunction.COSINE));
        Assertions.assertEquals(6, placement.capacity(), "ceil(23/4)");
        int total = 0;
        for (int count : placement.histogram()) {
            Assertions.assertTrue(count <= 6, "cap exceeded: " + count);
            total += count;
        }
        Assertions.assertEquals(23, total);
    }

    @Test
    void fewerChunksThanShardsSpreadOnePerShard() {
        ChunkPlacement placement = POLICY.place(new PlacementRequest(
                "doc-few", randomVectors(2, 4, 3), 5, VectorSimilarityFunction.COSINE));
        Assertions.assertEquals(1, placement.capacity());
        Map<Integer, int[]> occupied = placement.occupiedShards();
        Assertions.assertEquals(2, occupied.size(), "each chunk gets its own shard");
        for (int[] ordinals : occupied.values()) {
            Assertions.assertEquals(1, ordinals.length);
        }
    }

    @Test
    void singleShardShortCircuits() {
        ChunkPlacement placement = POLICY.place(new PlacementRequest(
                "doc-one", randomVectors(9, 4, 5), 1, VectorSimilarityFunction.COSINE));
        for (int shard : placement.shardOfChunk()) {
            Assertions.assertEquals(0, shard);
        }
    }

    @Test
    void windowingSharesTheCapacityBudget() {
        BalancedNearestNeighbourChainPlacement windowed =
                new BalancedNearestNeighbourChainPlacement(8);
        ChunkPlacement placement = windowed.place(new PlacementRequest(
                "doc-windows", randomVectors(30, 4, 11), 3, VectorSimilarityFunction.COSINE));
        Assertions.assertEquals(10, placement.capacity());
        for (int count : placement.histogram()) {
            Assertions.assertTrue(count <= 10,
                    "the cap must hold across windows, got " + count);
        }
    }

    @Test
    void requestValidationRejectsBadInput() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PlacementRequest(
                "", List.of(new float[]{1f}), 2, VectorSimilarityFunction.COSINE));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PlacementRequest(
                "d", List.of(new float[]{1f, 0f}, new float[]{1f}), 2,
                VectorSimilarityFunction.COSINE), "ragged dims");
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PlacementRequest(
                "d", List.of(new float[]{0f, 0f}), 2, VectorSimilarityFunction.COSINE),
                "zero-norm under COSINE");
        // Zero-norm is legal under EUCLIDEAN.
        Assertions.assertDoesNotThrow(() -> new PlacementRequest(
                "d", List.of(new float[]{0f, 0f}), 2, VectorSimilarityFunction.EUCLIDEAN));
    }
}
