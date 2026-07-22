package ai.pipestream.search.chunking;

/**
 * Counts tokens for chunk sizing. Implementations MUST be deterministic and
 * side-effect free: the count is part of the chunk-boundary function.
 */
public interface TokenCounter {

    /** Stable id, e.g. "chars/4", "sentencepiece:xyz", "tei:model". */
    String tokenizerId();

    int count(String text);

    /** Provider's max input tokens; 0 = unbounded (mean-pooling models). */
    int maxInputTokens();
}
