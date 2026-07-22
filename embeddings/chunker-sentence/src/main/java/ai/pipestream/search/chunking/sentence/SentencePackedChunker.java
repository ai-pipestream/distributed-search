package ai.pipestream.search.chunking.sentence;

import ai.pipestream.search.chunking.Chunk;
import ai.pipestream.search.chunking.ChunkSpec;
import ai.pipestream.search.chunking.Chunker;
import ai.pipestream.search.chunking.SentencePacking;
import ai.pipestream.search.chunking.TokenCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentence-packed chunking with in-repo "rules-v1" boundaries.
 *
 * <p>Chosen because it is the only strategy whose output is a pure function
 * of {@code (text, rules, counter, four integers)}: BreakIterator moves with
 * the JDK's CLDR data and would silently re-chunk a corpus on a JDK bump.
 *
 * <p><b>rules-v1 sentence boundaries.</b> A sentence ends after
 * {@code . ! ? 。 ！ ？} when followed by whitespace, a closing
 * quote/bracket then whitespace, or end of text; a blank line (two or more
 * consecutive newlines) is always a boundary. Trailing whitespace belongs to
 * the sentence it follows, so sentence spans partition {@code [0, length)}.
 *
 * <p><b>Packing.</b> Sentences pack greedily until adding the next would
 * exceed {@code targetTokens}. A single sentence longer than
 * {@code maxTokens} hard-splits at a deterministic char boundary (never
 * inside a surrogate pair). Adjacent chunks overlap by whole sentences from
 * the end of the previous chunk until {@code overlapTokens} is covered. A
 * final chunk under {@code minTokens} merges into the previous chunk.
 *
 * <p>Bump {@link #IMPL_VERSION} on ANY behavior change; it participates in
 * the collection's plan digest.
 */
public final class SentencePackedChunker implements Chunker {

    public static final String NAME = "sentence-packed";
    public static final int IMPL_VERSION = 1;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Chunk> chunk(String text, ChunkSpec spec, TokenCounter counter) {
        ChunkSpec resolved = spec.resolved();
        if (!"rules-v1".equals(resolved.boundary())) {
            throw new IllegalArgumentException(
                    "Unknown boundary rule set '" + resolved.boundary() + "'; this build ships rules-v1");
        }
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<int[]> sentences = split(text, resolved.maxTokens(), counter);
        return SentencePacking.pack(text, sentences, resolved, counter);
    }

    /**
     * rules-v1 segmentation into contiguous [start, end) spans, with
     * over-long sentences hard-split at deterministic char boundaries.
     */
    private static List<int[]> split(String text, int maxTokens, TokenCounter counter) {
        List<int[]> sentences = new ArrayList<>();
        int length = text.length();
        int start = 0;
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            boolean boundary = false;
            if (isTerminator(c)) {
                int j = i + 1;
                while (j < length && isClosing(text.charAt(j))) {
                    j++;
                }
                // Latin terminators need following whitespace (protects "3.14",
                // "e.g."-mid-token); CJK full-width terminators are boundaries
                // unconditionally — CJK text carries no inter-sentence spaces.
                if (isCjkTerminator(c) || j >= length || Character.isWhitespace(text.charAt(j))) {
                    // the trailing whitespace run belongs to this sentence
                    while (j < length && Character.isWhitespace(text.charAt(j))) {
                        j++;
                    }
                    i = j;
                    boundary = true;
                }
            } else if (c == '\n' && i + 1 < length && nextNonSpaceIsNewline(text, i + 1)) {
                // blank line: hard paragraph boundary
                int j = i + 1;
                while (j < length && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                i = j;
                boundary = true;
            }

            if (boundary) {
                if (i > start) {
                    sentences.add(new int[]{start, i});
                }
                start = i;
            } else {
                i++;
            }
        }
        if (start < length) {
            sentences.add(new int[]{start, length});
        }

        return SentencePacking.boundToMaxTokens(text, sentences, maxTokens, counter);
    }

    private static boolean isTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || isCjkTerminator(c);
    }

    private static boolean isCjkTerminator(char c) {
        return c == '。' || c == '！' || c == '？';
    }

    private static boolean isClosing(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']'
                || c == '”' || c == '’' || c == '」' || c == '』';
    }

    private static boolean nextNonSpaceIsNewline(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (!Character.isWhitespace(c) || c == '\r') {
                return c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n';
            }
        }
        return false;
    }
}
