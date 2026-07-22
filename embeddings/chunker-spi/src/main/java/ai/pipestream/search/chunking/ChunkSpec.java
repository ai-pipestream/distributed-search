package ai.pipestream.search.chunking;

/**
 * The reproducibility contract of a chunking run. Chunking MUST be a pure
 * function of {@code (text, spec, counter)}: the same triple produces the
 * same chunk boundaries on every node, forever — replay depends on it.
 *
 * <p>{@code implVersion} is bumped by ANY behavior change in a chunker
 * implementation and participates in the collection's plan digest, so a
 * silent behavior change cannot masquerade as the pinned chunking.
 *
 * @param strategy      chunker name ("" = "sentence-packed")
 * @param targetTokens  soft target per chunk (0 = 384)
 * @param overlapTokens sentence-granular overlap between chunks (0 = 64)
 * @param minTokens     chunks below this merge into the previous (0 = 32)
 * @param maxTokens     hard cap; longer sentences split at a token boundary
 *                      (0 = 512)
 * @param tokenizerId   token counter id ("" = best available tier)
 * @param boundary      sentence-boundary rule set ("" = "rules-v1")
 * @param implVersion   chunker implementation version (0 = current)
 */
public record ChunkSpec(String strategy, int targetTokens, int overlapTokens,
                        int minTokens, int maxTokens, String tokenizerId,
                        String boundary, int implVersion) {

    public static final int DEFAULT_TARGET_TOKENS = 384;
    public static final int DEFAULT_OVERLAP_TOKENS = 64;
    public static final int DEFAULT_MIN_TOKENS = 32;
    public static final int DEFAULT_MAX_TOKENS = 512;

    /** Applies defaults for unset (zero/empty) values. */
    public ChunkSpec resolved() {
        return new ChunkSpec(
                strategy == null || strategy.isEmpty() ? "sentence-packed" : strategy,
                targetTokens > 0 ? targetTokens : DEFAULT_TARGET_TOKENS,
                overlapTokens > 0 ? overlapTokens : DEFAULT_OVERLAP_TOKENS,
                minTokens > 0 ? minTokens : DEFAULT_MIN_TOKENS,
                maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS,
                tokenizerId == null ? "" : tokenizerId,
                boundary == null || boundary.isEmpty() ? "rules-v1" : boundary,
                implVersion);
    }

    public static ChunkSpec defaults() {
        return new ChunkSpec("", 0, 0, 0, 0, "", "", 0).resolved();
    }
}
