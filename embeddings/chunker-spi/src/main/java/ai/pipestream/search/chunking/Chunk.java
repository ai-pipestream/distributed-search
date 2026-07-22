package ai.pipestream.search.chunking;

/**
 * One chunk of a source text. {@code text} is always exactly
 * {@code source.substring(startOffset, endOffset)} — offsets are the durable
 * form (children carry them; text storage is opt-in per representation).
 *
 * @param ordinal     0-based position in the chunk sequence
 * @param startOffset inclusive char offset in the source text
 * @param endOffset   exclusive char offset in the source text
 * @param text        the chunk body
 */
public record Chunk(int ordinal, int startOffset, int endOffset, String text) {

    public Chunk {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "invalid chunk span [" + startOffset + ", " + endOffset + ")");
        }
    }
}
