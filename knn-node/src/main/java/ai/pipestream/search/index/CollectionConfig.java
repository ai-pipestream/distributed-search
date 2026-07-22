package ai.pipestream.search.index;

import org.apache.lucene.index.VectorSimilarityFunction;

/**
 * Immutable configuration for a collection.
 *
 * <p>{@code documentCentric} is a create-time decision: it drives
 * {@code IndexWriterConfig.setParentField}, which Lucene refuses to add to an
 * existing index — a flat collection can never be retrofitted into a
 * document-centric one (and vice versa).
 */
public record CollectionConfig(
        String name,
        int vectorDimension,
        VectorSimilarityFunction similarity,
        int numShards,
        String embeddingModel,
        boolean documentCentric,
        String chunkMessage,
        PlacementMode placement,
        int maxChunksPerDocument
) {
    /** How a parent document's chunks are placed across shards. */
    public enum PlacementMode {
        /** Flat routing: whole document on hash(doc_id) mod S. */
        HASH_BY_DOC_ID,
        /** Balance-constrained similarity clustering across shards. */
        BALANCED_SIMILARITY
    }

    public static final int DEFAULT_MAX_CHUNKS_PER_DOCUMENT = 4096;

    public CollectionConfig {
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(
                    "Collection name must match [a-z0-9][a-z0-9-]{0,62}");
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
        if (chunkMessage == null) {
            chunkMessage = "";
        }
        if (placement == null) {
            placement = documentCentric ? PlacementMode.BALANCED_SIMILARITY : PlacementMode.HASH_BY_DOC_ID;
        }
        if (maxChunksPerDocument <= 0) {
            maxChunksPerDocument = DEFAULT_MAX_CHUNKS_PER_DOCUMENT;
        }
        if (!documentCentric && placement == PlacementMode.BALANCED_SIMILARITY) {
            throw new IllegalArgumentException(
                    "BALANCED_SIMILARITY placement requires a document-centric collection");
        }
    }

    /** Flat-collection constructor (pre-document-centric call sites). */
    public CollectionConfig(String name, int vectorDimension, VectorSimilarityFunction similarity,
                            int numShards, String embeddingModel) {
        this(name, vectorDimension, similarity, numShards, embeddingModel,
                false, "", PlacementMode.HASH_BY_DOC_ID, DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
    }
}
