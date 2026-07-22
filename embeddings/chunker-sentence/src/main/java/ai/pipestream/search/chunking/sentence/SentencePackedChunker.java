package ai.pipestream.search.chunking.sentence;

import ai.pipestream.search.chunking.Chunk;
import ai.pipestream.search.chunking.ChunkSpec;
import ai.pipestream.search.chunking.Chunker;
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
        if (sentences.isEmpty()) {
            return List.of();
        }

        // Greedy packing with sentence-granular overlap.
        List<int[]> spans = new ArrayList<>();     // [firstSentence, lastSentence] inclusive
        int first = 0;
        while (first < sentences.size()) {
            int tokens = 0;
            int last = first;
            while (last < sentences.size()) {
                int sentenceTokens = counter.count(slice(text, sentences.get(last)));
                if (last > first && tokens + sentenceTokens > resolved.targetTokens()) {
                    break;
                }
                tokens += sentenceTokens;
                last++;
            }
            last--;   // inclusive
            spans.add(new int[]{first, last});
            if (last + 1 >= sentences.size()) {
                break;
            }
            // Overlap: back up whole sentences from the chunk end until the
            // overlap budget is covered (at least advancing by one sentence).
            int nextFirst = last + 1;
            int overlap = 0;
            while (nextFirst - 1 > first && overlap < resolved.overlapTokens()) {
                overlap += counter.count(slice(text, sentences.get(nextFirst - 1)));
                if (overlap <= resolved.overlapTokens()) {
                    nextFirst--;
                }
            }
            first = nextFirst;
        }

        // A trailing runt merges into the previous chunk.
        if (spans.size() > 1) {
            int[] tail = spans.get(spans.size() - 1);
            int tailTokens = 0;
            for (int i = tail[0]; i <= tail[1]; i++) {
                tailTokens += counter.count(slice(text, sentences.get(i)));
            }
            if (tailTokens < resolved.minTokens()) {
                spans.remove(spans.size() - 1);
                spans.get(spans.size() - 1)[1] = tail[1];
            }
        }

        List<Chunk> chunks = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            int start = sentences.get(span[0])[0];
            int end = sentences.get(span[1])[1];
            chunks.add(new Chunk(chunks.size(), start, end, text.substring(start, end)));
        }
        return chunks;
    }

    private static String slice(String text, int[] span) {
        return text.substring(span[0], span[1]);
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

        // Hard-split any sentence over maxTokens at deterministic char cuts.
        List<int[]> bounded = new ArrayList<>(sentences.size());
        for (int[] sentence : sentences) {
            if (counter.count(text.substring(sentence[0], sentence[1])) <= maxTokens) {
                bounded.add(sentence);
                continue;
            }
            int from = sentence[0];
            while (from < sentence[1]) {
                int to = sentence[1];
                // Shrink until the piece fits: halve the span by chars, which
                // is deterministic for any deterministic counter.
                while (to > from + 1 && counter.count(text.substring(from, to)) > maxTokens) {
                    int mid = from + Math.max(1, (to - from) / 2);
                    if (Character.isLowSurrogate(text.charAt(mid))) {
                        mid--;   // never split a surrogate pair
                    }
                    to = mid;
                }
                bounded.add(new int[]{from, to});
                from = to;
            }
        }
        return bounded;
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
