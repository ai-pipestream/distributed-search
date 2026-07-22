package ai.pipestream.search.index.placement;

import org.apache.lucene.index.VectorSimilarityFunction;

import java.util.List;

/**
 * Input to chunk placement: one parent's chunk vectors, in ordinal order.
 */
public record PlacementRequest(String parentDocId, List<float[]> chunkVectors,
                               int numShards, VectorSimilarityFunction similarity) {

    public PlacementRequest {
        if (parentDocId == null || parentDocId.isEmpty()) {
            throw new IllegalArgumentException("parentDocId is required");
        }
        if (chunkVectors == null || chunkVectors.isEmpty()) {
            throw new IllegalArgumentException("chunkVectors must not be empty");
        }
        if (numShards < 1) {
            throw new IllegalArgumentException("numShards must be at least 1");
        }
        if (similarity == null) {
            similarity = VectorSimilarityFunction.COSINE;
        }
        int dims = chunkVectors.get(0).length;
        boolean rejectZeroNorm = similarity == VectorSimilarityFunction.COSINE
                || similarity == VectorSimilarityFunction.DOT_PRODUCT;
        for (int i = 0; i < chunkVectors.size(); i++) {
            float[] vector = chunkVectors.get(i);
            if (vector.length != dims) {
                throw new IllegalArgumentException("Ragged chunk vectors: chunk 0 has "
                        + dims + " dims, chunk " + i + " has " + vector.length);
            }
            boolean allZero = true;
            for (float f : vector) {
                if (!Float.isFinite(f)) {
                    throw new IllegalArgumentException(
                            "Chunk " + i + " has a non-finite vector component");
                }
                if (f != 0f) {
                    allZero = false;
                }
            }
            if (allZero && rejectZeroNorm) {
                // VectorUtil.cosine asserts isFinite; a zero-norm vector would
                // otherwise surface as an AssertionError from inside the
                // clustering loop.
                throw new IllegalArgumentException("Chunk " + i + " has a zero-norm vector, "
                        + "invalid under " + similarity);
            }
        }
        chunkVectors = List.copyOf(chunkVectors);
    }

    public int chunkCount() {
        return chunkVectors.size();
    }
}
