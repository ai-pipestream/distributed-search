package ai.pipestream.search.chunking.sentence;

import ai.pipestream.search.chunking.CharsPerTokenCounter;
import ai.pipestream.search.chunking.Chunk;
import ai.pipestream.search.chunking.ChunkSpec;
import ai.pipestream.search.chunking.TokenCounter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * P6 chunker proofs: structural invariants that must hold for every input
 * (offset/substring integrity, coverage, overlap direction, token caps,
 * determinism), plus exact boundaries for fixed fixtures and the edge cases
 * the plan names: no-terminator text, a giant sentence hard-split, CJK,
 * text ending mid-word, empty input.
 */
class SentencePackedChunkerTest {

    private static final SentencePackedChunker CHUNKER = new SentencePackedChunker();
    private static final TokenCounter COUNTER = new CharsPerTokenCounter();   // chars/4

    private static ChunkSpec spec(int target, int overlap, int min, int max) {
        return new ChunkSpec("", target, overlap, min, max, "", "", 0).resolved();
    }

    private static void assertInvariants(String text, List<Chunk> chunks, ChunkSpec spec) {
        Assertions.assertFalse(chunks.isEmpty());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            Assertions.assertEquals(i, chunk.ordinal());
            Assertions.assertEquals(text.substring(chunk.startOffset(), chunk.endOffset()),
                    chunk.text(), "offsets must reconstruct the chunk exactly");
        }
        Assertions.assertEquals(0, chunks.get(0).startOffset(), "coverage starts at 0");
        Assertions.assertEquals(text.length(), chunks.get(chunks.size() - 1).endOffset(),
                "coverage ends at length");
        for (int i = 1; i < chunks.size(); i++) {
            Assertions.assertTrue(chunks.get(i).startOffset() > chunks.get(i - 1).startOffset(),
                    "starts strictly increase (the loop must always advance)");
            Assertions.assertTrue(chunks.get(i).startOffset() <= chunks.get(i - 1).endOffset(),
                    "no gaps: each chunk starts at or before the previous end");
            Assertions.assertTrue(chunks.get(i).endOffset() > chunks.get(i - 1).endOffset(),
                    "ends strictly increase");
        }
    }

    @Test
    void emptyAndBlankTextProduceNoChunks() {
        Assertions.assertTrue(CHUNKER.chunk("", ChunkSpec.defaults(), COUNTER).isEmpty());
        Assertions.assertTrue(CHUNKER.chunk(null, ChunkSpec.defaults(), COUNTER).isEmpty());
    }

    @Test
    void simpleProseHasExactBoundaries() {
        // 4 sentences, 16 chars each ("Wxyz sentence N. " = 4 tokens with chars/4).
        String text = "First sentence one. Second sentence is. Third sentence go. Tail sentences.";
        // target 10 tokens: s1(5t incl trailing space? "First sentence one. " = 20 chars = 5 tokens)
        List<Chunk> chunks = CHUNKER.chunk(text, spec(10, 1, 1, 100), COUNTER);
        assertInvariants(text, chunks, spec(10, 1, 1, 100));
        // Two sentences fit per chunk (10 tokens); overlap of 1 token backs up
        // less than one sentence, so chunks tile sentence pairs.
        Assertions.assertEquals(2, chunks.size());
        Assertions.assertEquals("First sentence one. Second sentence is. ", chunks.get(0).text());
        Assertions.assertEquals("Third sentence go. Tail sentences.", chunks.get(1).text());
    }

    @Test
    void overlapBacksUpWholeSentences() {
        String text = "Alpha alpha alpha one. Beta beta beta two. Gamma gamma gamma. Delta delta four.";
        // target 11 tokens (~2 sentences), overlap 6 tokens (~1 sentence).
        List<Chunk> chunks = CHUNKER.chunk(text, spec(11, 6, 1, 100), COUNTER);
        assertInvariants(text, chunks, spec(11, 6, 1, 100));
        Assertions.assertTrue(chunks.size() >= 2);
        // The second chunk must start at a sentence boundary INSIDE the first
        // chunk (sentence-granular overlap).
        Chunk first = chunks.get(0);
        Chunk second = chunks.get(1);
        Assertions.assertTrue(second.startOffset() < first.endOffset(),
                "chunks must overlap");
        Assertions.assertTrue(text.startsWith("Beta", second.startOffset())
                        || text.startsWith("Gamma", second.startOffset()),
                "overlap backs up by WHOLE sentences, got: '"
                        + second.text().substring(0, Math.min(20, second.text().length())) + "'");
    }

    @Test
    void noTerminatorTextIsOneChunkOrHardSplit() {
        String text = "a stream of tokens with no sentence terminator at all just words";
        List<Chunk> chunks = CHUNKER.chunk(text, ChunkSpec.defaults(), COUNTER);
        assertInvariants(text, chunks, ChunkSpec.defaults());
        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals(text, chunks.get(0).text());
    }

    @Test
    void giantSentenceHardSplitsAtTokenBoundaries() {
        // One 10k-char "sentence" with no terminators: must split at maxTokens
        // (with chars/4: 32 tokens = 128 chars per piece).
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 10_000) {
            sb.append("word ");
        }
        String text = sb.substring(0, 10_000);
        ChunkSpec spec = spec(32, 1, 1, 32);
        List<Chunk> chunks = CHUNKER.chunk(text, spec, COUNTER);
        assertInvariants(text, chunks, spec);
        Assertions.assertTrue(chunks.size() > 50, "a 10k-char sentence must hard-split");
        for (Chunk chunk : chunks) {
            Assertions.assertTrue(COUNTER.count(chunk.text()) <= 32,
                    "every piece must respect maxTokens, got " + COUNTER.count(chunk.text()));
        }
    }

    @Test
    void cjkTerminatorsAreBoundaries() {
        String text = "これは最初の文です。これは二番目の文です。これは三番目の文です。";
        List<Chunk> chunks = CHUNKER.chunk(text, spec(6, 1, 1, 100), COUNTER);
        assertInvariants(text, chunks, spec(6, 1, 1, 100));
        Assertions.assertTrue(chunks.size() >= 2, "。 must terminate sentences");
        Assertions.assertTrue(chunks.get(0).text().endsWith("。"),
                "the first chunk must end at a CJK sentence boundary");
    }

    @Test
    void blankLineIsAHardBoundary() {
        String text = "heading without terminator\n\nBody sentence here.";
        List<Chunk> chunks = CHUNKER.chunk(text, spec(4, 1, 1, 100), COUNTER);
        assertInvariants(text, chunks, spec(4, 1, 1, 100));
        Assertions.assertTrue(chunks.size() >= 2,
                "a blank line must break the heading from the body");
        Assertions.assertTrue(chunks.get(1).text().startsWith("Body"),
                "the body must start its own chunk");
    }

    @Test
    void textEndingMidWordIsCovered() {
        String text = "A full sentence here. And then it just cuts off mid-wor";
        List<Chunk> chunks = CHUNKER.chunk(text, spec(6, 1, 1, 100), COUNTER);
        assertInvariants(text, chunks, spec(6, 1, 1, 100));
        Assertions.assertTrue(chunks.get(chunks.size() - 1).text().endsWith("mid-wor"),
                "the dangling tail must be covered");
    }

    @Test
    void trailingRuntMergesIntoPreviousChunk() {
        String text = "A first sentence with plenty of words inside it. Tiny tail.";
        // target fits sentence 1; the 3-token tail is under minTokens=5.
        List<Chunk> chunks = CHUNKER.chunk(text, spec(13, 1, 5, 100), COUNTER);
        assertInvariants(text, chunks, spec(13, 1, 5, 100));
        Assertions.assertEquals(1, chunks.size(), "a runt tail merges into the previous chunk");
        Assertions.assertEquals(text, chunks.get(0).text());
    }

    @Test
    void chunkingIsDeterministic() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Sentence number ").append(i).append(" has some words. ");
            if (i % 17 == 0) {
                sb.append("\n\n");
            }
        }
        String text = sb.toString();
        List<Chunk> first = CHUNKER.chunk(text, ChunkSpec.defaults(), COUNTER);
        List<Chunk> second = CHUNKER.chunk(text, ChunkSpec.defaults(), COUNTER);
        Assertions.assertEquals(first, second, "same input, same boundaries, always");
        assertInvariants(text, first, ChunkSpec.defaults());
    }

    @Test
    void unknownBoundaryRuleSetIsRejected() {
        ChunkSpec bad = new ChunkSpec("", 0, 0, 0, 0, "", "opennlp:deadbeef", 0);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CHUNKER.chunk("Some text.", bad, COUNTER),
                "an unpinned boundary rule set must fail, not silently fall back");
    }
}
