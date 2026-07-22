package ai.pipestream.search.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared sentence-packing mechanics: greedy packing to {@code targetTokens},
 * sentence-granular overlap, trailing-runt merge, and hard-splitting of
 * over-long sentences. Boundary DETECTION stays in each chunker (rules-v1,
 * OpenNLP models, ...); everything downstream of the sentence spans is
 * identical across chunkers and lives here so two strategies differing only
 * in boundaries cannot drift in packing behavior.
 *
 * <p>Sentence spans must partition a prefix-complete range of the text:
 * contiguous {@code [start, end)} pairs, first starting at 0, each starting
 * where the previous ended. Packing is a pure function of
 * {@code (text, spans, spec, counter)} — the reproducibility contract of
 * {@link ChunkSpec} depends on it.
 */
public final class SentencePacking {

    private SentencePacking() {
    }

    /**
     * Hard-splits any sentence over {@code maxTokens} at deterministic char
     * cuts (never inside a surrogate pair), leaving shorter sentences
     * untouched.
     */
    public static List<int[]> boundToMaxTokens(String text, List<int[]> sentences,
                                               int maxTokens, TokenCounter counter) {
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

    /**
     * Greedy packing with sentence-granular overlap and trailing-runt merge.
     * {@code spec} must already be {@link ChunkSpec#resolved() resolved}.
     */
    public static List<Chunk> pack(String text, List<int[]> sentences,
                                   ChunkSpec spec, TokenCounter counter) {
        if (sentences.isEmpty()) {
            return List.of();
        }
        List<int[]> spans = new ArrayList<>();     // [firstSentence, lastSentence] inclusive
        int first = 0;
        while (first < sentences.size()) {
            int tokens = 0;
            int last = first;
            while (last < sentences.size()) {
                int sentenceTokens = counter.count(slice(text, sentences.get(last)));
                if (last > first && tokens + sentenceTokens > spec.targetTokens()) {
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
            while (nextFirst - 1 > first && overlap < spec.overlapTokens()) {
                overlap += counter.count(slice(text, sentences.get(nextFirst - 1)));
                if (overlap <= spec.overlapTokens()) {
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
            if (tailTokens < spec.minTokens()) {
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
}
