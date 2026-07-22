package ai.pipestream.search.index.placement;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.StringHelper;

/**
 * Balance-constrained greedy nearest-neighbour-chain placement.
 *
 * <p>Chunks are grouped into S similarity clusters of at most
 * {@code ceil(n/S)} each: a cluster is seeded with the lowest unassigned
 * ordinal, then repeatedly extended with the unassigned chunk most similar to
 * the LAST added member (ties break on the lower ordinal, strictly). Cluster
 * c lands on shard {@code (rotation + c) mod S}, with the rotation drawn from
 * a pinned-seed murmur3 of the parent id so corpus-wide load spreads evenly.
 *
 * <p>Deterministic by construction: no PRNG, no iteration counts, no
 * cross-step float accumulation (similarity is always between two original
 * chunk vectors, never a centroid). The rotation seed is a fixed literal —
 * NOT {@code StringHelper.GOOD_FAST_HASH_SEED}, which is time-seeded per JVM
 * and would silently re-place every document on the second JVM start.
 *
 * <p>Degenerates gracefully: with near-identical vectors every similarity
 * ties and the tie-break yields contiguous ordinal runs (the RFC's
 * positional fallback); with n &le; S every chunk gets its own shard.
 *
 * <p>Documents beyond {@code maxExactChunks} are processed in positional
 * windows of that size (the chain is O(n²) per window) with the capacity
 * budget shared across windows.
 */
public final class BalancedNearestNeighbourChainPlacement implements ChunkPlacementPolicy {

    public static final String ID = "nn-chain";

    /** Pinned literal; never a time-seeded constant. */
    static final int ROTATION_SEED = 0x5F3AC21D;

    public static final int DEFAULT_MAX_EXACT_CHUNKS = 1024;

    private final int maxExactChunks;

    public BalancedNearestNeighbourChainPlacement() {
        this(DEFAULT_MAX_EXACT_CHUNKS);
    }

    public BalancedNearestNeighbourChainPlacement(int maxExactChunks) {
        if (maxExactChunks < 1) {
            throw new IllegalArgumentException("maxExactChunks must be positive");
        }
        this.maxExactChunks = maxExactChunks;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ChunkPlacement place(PlacementRequest request) {
        int n = request.chunkCount();
        int s = request.numShards();
        int cap = (n + s - 1) / s;
        int[] shardOfChunk = new int[n];

        if (s == 1) {
            return new ChunkPlacement(request.parentDocId(), s, cap, shardOfChunk);
        }

        int rotation = rotationOffset(request.parentDocId(), s);
        int[] clusterOfChunk = new int[n];
        int[] clusterSize = new int[s];   // shared across windows
        boolean[] assigned = new boolean[n];
        int cluster = 0;

        for (int windowStart = 0; windowStart < n; windowStart += maxExactChunks) {
            int windowEnd = Math.min(n, windowStart + maxExactChunks);
            int remaining = windowEnd - windowStart;
            int last = -1;
            while (remaining > 0) {
                while (clusterSize[cluster] >= cap) {
                    cluster++;
                    last = -1;
                    if (cluster >= s) {
                        throw new IllegalStateException(
                                "capacity exhausted; cap=" + cap + " n=" + n + " s=" + s);
                    }
                }
                int pick;
                if (last < 0) {
                    // Seed: the lowest unassigned ordinal in the window.
                    pick = -1;
                    for (int i = windowStart; i < windowEnd; i++) {
                        if (!assigned[i]) {
                            pick = i;
                            break;
                        }
                    }
                } else {
                    // Chain: most similar to the LAST added member; strict
                    // comparison so ties keep the lowest ordinal.
                    pick = -1;
                    float best = Float.NEGATIVE_INFINITY;
                    for (int i = windowStart; i < windowEnd; i++) {
                        if (assigned[i]) {
                            continue;
                        }
                        float similarity = score(request.similarity(),
                                request.chunkVectors().get(last), request.chunkVectors().get(i));
                        if (similarity > best) {
                            best = similarity;
                            pick = i;
                        }
                    }
                }
                assigned[pick] = true;
                clusterOfChunk[pick] = cluster;
                clusterSize[cluster]++;
                last = pick;
                remaining--;
            }
        }

        for (int i = 0; i < n; i++) {
            shardOfChunk[i] = (rotation + clusterOfChunk[i]) % s;
        }
        return new ChunkPlacement(request.parentDocId(), s, cap, shardOfChunk);
    }

    /** Starting shard for a parent: pinned-seed murmur3 mod S. */
    static int rotationOffset(String parentDocId, int numShards) {
        BytesRef bytes = new BytesRef(parentDocId);
        int hash = StringHelper.murmurhash3_x86_32(bytes.bytes, bytes.offset, bytes.length,
                ROTATION_SEED);
        return Math.floorMod(hash, numShards);
    }

    /** Similarity between two chunk vectors; NaN compares as -inf. */
    static float score(VectorSimilarityFunction similarity, float[] a, float[] b) {
        float score = similarity.compare(a, b);
        return Float.isNaN(score) ? Float.NEGATIVE_INFINITY : score;
    }
}
