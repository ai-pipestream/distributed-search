package ai.pipestream.search.query;

import java.util.List;

/**
 * Result of a document-centric shard execution: top documents, each with the
 * exact per-chunk scores of its shard-local block.
 */
public record DocumentTopDocs(List<DocumentHit> hits) {

    /**
     * One document. {@code score} is the max over the block's chunk scores;
     * {@code chunks} is score-descending and capped at chunks_per_hit.
     */
    public record DocumentHit(String docId, float score, List<ChunkScore> chunks) {}

    /** One exactly-scored chunk of a returned document. */
    public record ChunkScore(String chunkId, int ordinal, int startOffset, int endOffset,
                             float score, String text, byte[] nlp) {

        /** Chunk without stored NLP annotations. */
        public ChunkScore(String chunkId, int ordinal, int startOffset, int endOffset,
                          float score, String text) {
            this(chunkId, ordinal, startOffset, endOffset, score, text, null);
        }
    }
}
