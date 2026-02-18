package ai.pipestream.search.index;

import org.apache.lucene.index.VectorSimilarityFunction;

/**
 * Immutable configuration for a collection.
 */
public record CollectionConfig(
        String name,
        int vectorDimension,
        VectorSimilarityFunction similarity,
        int numShards,
        String embeddingModel
) {
    public CollectionConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Collection name must not be blank");
        }
        if (vectorDimension <= 0) {
            throw new IllegalArgumentException("Vector dimension must be positive");
        }
        if (numShards <= 0) {
            numShards = 1;
        }
        if (similarity == null) {
            similarity = VectorSimilarityFunction.COSINE;
        }
        if (embeddingModel == null) {
            embeddingModel = "";
        }
    }
}
