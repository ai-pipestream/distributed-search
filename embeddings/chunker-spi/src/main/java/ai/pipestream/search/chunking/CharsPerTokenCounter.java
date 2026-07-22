package ai.pipestream.search.chunking;


/**
 * The bottom counter tier: {@code ceil(chars / N)}. Deterministic across
 * every JVM and model; used when no model tokenizer is pinned.
 */
public final class CharsPerTokenCounter implements TokenCounter {

    public static final int DEFAULT_CHARS_PER_TOKEN = 4;

    private final int charsPerToken;

    public CharsPerTokenCounter() {
        this(DEFAULT_CHARS_PER_TOKEN);
    }

    public CharsPerTokenCounter(int charsPerToken) {
        if (charsPerToken < 1) {
            throw new IllegalArgumentException("charsPerToken must be positive");
        }
        this.charsPerToken = charsPerToken;
    }

    @Override
    public String tokenizerId() {
        return "chars/" + charsPerToken;
    }

    @Override
    public int count(String text) {
        return (text.length() + charsPerToken - 1) / charsPerToken;
    }

    @Override
    public int maxInputTokens() {
        return 0;
    }
}
