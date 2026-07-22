package ai.pipestream.search.chunking.opennlp;

import ai.pipestream.search.chunking.CharsPerTokenCounter;
import ai.pipestream.search.chunking.Chunk;
import ai.pipestream.search.chunking.ChunkSpec;
import ai.pipestream.search.chunking.Chunkers;
import ai.pipestream.search.chunking.TokenCounter;
import opennlp.tools.sentdetect.SentenceDetectorFactory;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.sentdetect.SentenceSample;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.ObjectStreamUtils;
import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Proofs for the OpenNLP-boundary chunker: the model-pin contract (correct
 * pin chunks, wrong pin refuses, non-OpenNLP boundary refuses), packing
 * invariants over model boundaries, ServiceLoader registration, and
 * determinism. The model is trained in-test from a fixed synthetic corpus,
 * so the test carries no binary fixtures and the pin is computed from the
 * exact bytes the chunker will load.
 */
class OpenNlpSentenceChunkerTest {

    private static final OpenNlpSentenceChunker CHUNKER = new OpenNlpSentenceChunker();
    private static final TokenCounter COUNTER = new CharsPerTokenCounter();   // chars/4

    @TempDir
    static Path tempDir;

    private static String pin;

    @BeforeAll
    static void trainAndPinModel() throws Exception {
        List<SentenceSample> samples = new ArrayList<>();
        String[] words = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
                "golf", "hotel", "india", "juliet", "kilo", "lima"};
        int w = 0;
        for (int doc = 0; doc < 60; doc++) {
            StringBuilder textBuilder = new StringBuilder();
            List<Span> spans = new ArrayList<>();
            int sentencesInDoc = 3 + doc % 3;
            for (int s = 0; s < sentencesInDoc; s++) {
                int start = textBuilder.length();
                // Mid-sentence abbreviation periods give the trainer its
                // second outcome: a '.' that does NOT split.
                if ((doc + s) % 2 == 0) {
                    textBuilder.append("mr. ");
                }
                int wordsInSentence = 4 + (doc + s) % 4;
                for (int i = 0; i < wordsInSentence; i++) {
                    if (i > 0) {
                        textBuilder.append(' ');
                    }
                    textBuilder.append(words[w++ % words.length]);
                }
                textBuilder.append('.');
                spans.add(new Span(start, textBuilder.length()));
                if (s + 1 < sentencesInDoc) {
                    textBuilder.append(' ');
                }
            }
            samples.add(new SentenceSample(textBuilder.toString(), spans.toArray(Span[]::new)));
        }

        TrainingParameters params = TrainingParameters.defaultParams();
        params.put(TrainingParameters.ITERATIONS_PARAM, 100);
        params.put(TrainingParameters.CUTOFF_PARAM, 0);
        ObjectStream<SentenceSample> stream = ObjectStreamUtils.createObjectStream(samples);
        SentenceModel model = SentenceDetectorME.train("eng", stream,
                new SentenceDetectorFactory("eng", true, null, null), params);

        Path modelFile = tempDir.resolve("sentdetect.bin");
        try (OutputStream out = Files.newOutputStream(modelFile)) {
            model.serialize(out);
        }
        byte[] bytes = Files.readAllBytes(modelFile);
        pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        System.setProperty(OpenNlpSentenceChunker.MODEL_PATH_PROPERTY, modelFile.toString());
    }

    private static ChunkSpec spec(int target, int overlap, int min, int max) {
        return new ChunkSpec(OpenNlpSentenceChunker.NAME, target, overlap, min, max,
                "", OpenNlpSentenceChunker.BOUNDARY_PREFIX + pin, 0).resolved();
    }

    private static void assertInvariants(String text, List<Chunk> chunks) {
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
            Assertions.assertTrue(chunks.get(i).startOffset() > chunks.get(i - 1).startOffset());
            Assertions.assertTrue(chunks.get(i).startOffset() <= chunks.get(i - 1).endOffset(),
                    "no gaps between chunks");
            Assertions.assertTrue(chunks.get(i).endOffset() > chunks.get(i - 1).endOffset());
        }
    }

    private static String prose(int sentences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append("alpha bravo charlie delta echo number ").append(i).append('.');
        }
        return sb.toString();
    }

    @Test
    void pinnedModelChunksWithPackingInvariants() {
        String text = prose(12);
        List<Chunk> chunks = CHUNKER.chunk(text, spec(20, 4, 2, 100), COUNTER);
        assertInvariants(text, chunks);
        Assertions.assertTrue(chunks.size() >= 2,
                "12 sentences at ~10 tokens each must not fit one 20-token chunk");
    }

    @Test
    void emptyTextProducesNoChunks() {
        Assertions.assertTrue(CHUNKER.chunk("", spec(20, 4, 2, 100), COUNTER).isEmpty());
        Assertions.assertTrue(CHUNKER.chunk(null, spec(20, 4, 2, 100), COUNTER).isEmpty());
    }

    @Test
    void wrongPinRefusesToChunk() {
        ChunkSpec wrong = new ChunkSpec(OpenNlpSentenceChunker.NAME, 20, 4, 2, 100,
                "", OpenNlpSentenceChunker.BOUNDARY_PREFIX + "0".repeat(64), 0).resolved();
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> CHUNKER.chunk(prose(3), wrong, COUNTER),
                "a mismatched model digest must fail, not silently re-chunk");
        Assertions.assertTrue(e.getMessage().contains("digest mismatch"), e.getMessage());
    }

    @Test
    void nonOpennlpBoundaryIsRejected() {
        ChunkSpec rules = new ChunkSpec(OpenNlpSentenceChunker.NAME, 20, 4, 2, 100,
                "", "rules-v1", 0).resolved();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CHUNKER.chunk(prose(3), rules, COUNTER),
                "this chunker serves opennlp:<digest> pins only");

        ChunkSpec shortPin = new ChunkSpec(OpenNlpSentenceChunker.NAME, 20, 4, 2, 100,
                "", "opennlp:abc", 0).resolved();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CHUNKER.chunk(prose(3), shortPin, COUNTER),
                "a pin below the minimum prefix length is not a pin");
    }

    @Test
    void giantSentenceStillHardSplits() {
        String text = "word ".repeat(2000).trim();   // no terminators at all
        List<Chunk> chunks = CHUNKER.chunk(text, spec(32, 1, 1, 32), COUNTER);
        assertInvariants(text, chunks);
        for (Chunk chunk : chunks) {
            Assertions.assertTrue(COUNTER.count(chunk.text()) <= 32,
                    "shared packing must hard-split over-long sentences");
        }
    }

    @Test
    void chunkingIsDeterministic() {
        String text = prose(40);
        List<Chunk> first = CHUNKER.chunk(text, spec(30, 6, 2, 100), COUNTER);
        List<Chunk> second = CHUNKER.chunk(text, spec(30, 6, 2, 100), COUNTER);
        Assertions.assertEquals(first, second);
        assertInvariants(text, first);
    }

    @Test
    void registeredWithServiceLoaderUnderItsName() {
        Assertions.assertEquals(OpenNlpSentenceChunker.class,
                Chunkers.byName(OpenNlpSentenceChunker.NAME).getClass());
    }
}
