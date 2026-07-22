package ai.pipestream.search.index.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The result of placing one parent's chunks: which shard each chunk (by
 * ordinal) lands on. Shards absent from {@link #occupiedShards()} get NO
 * stub for this parent.
 */
public record ChunkPlacement(String parentDocId, int numShards, int capacity, int[] shardOfChunk) {

    public int chunkCount() {
        return shardOfChunk.length;
    }

    /** Ordinals landing on {@code shardId}, ascending. */
    public int[] chunkOrdinalsFor(int shardId) {
        int count = 0;
        for (int shard : shardOfChunk) {
            if (shard == shardId) {
                count++;
            }
        }
        int[] ordinals = new int[count];
        int at = 0;
        for (int ordinal = 0; ordinal < shardOfChunk.length; ordinal++) {
            if (shardOfChunk[ordinal] == shardId) {
                ordinals[at++] = ordinal;
            }
        }
        return ordinals;
    }

    /** Non-empty shard groups only, ascending by shard id. */
    public Map<Integer, int[]> occupiedShards() {
        Map<Integer, List<Integer>> groups = new TreeMap<>();
        for (int ordinal = 0; ordinal < shardOfChunk.length; ordinal++) {
            groups.computeIfAbsent(shardOfChunk[ordinal], k -> new ArrayList<>()).add(ordinal);
        }
        Map<Integer, int[]> result = new TreeMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : groups.entrySet()) {
            int[] ordinals = new int[entry.getValue().size()];
            for (int i = 0; i < ordinals.length; i++) {
                ordinals[i] = entry.getValue().get(i);
            }
            result.put(entry.getKey(), ordinals);
        }
        return result;
    }

    /** Chunks per shard, indexed by shard id. */
    public int[] histogram() {
        int[] histogram = new int[numShards];
        for (int shard : shardOfChunk) {
            histogram[shard]++;
        }
        return histogram;
    }
}
