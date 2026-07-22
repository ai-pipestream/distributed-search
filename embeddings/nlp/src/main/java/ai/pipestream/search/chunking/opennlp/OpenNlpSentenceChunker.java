package ai.pipestream.search.chunking.opennlp;

import ai.pipestream.search.chunking.Chunk;
import ai.pipestream.search.chunking.ChunkSpec;
import ai.pipestream.search.chunking.Chunker;
import ai.pipestream.search.chunking.SentencePacking;
import ai.pipestream.search.chunking.TokenCounter;
import ai.pipestream.search.nlp.OpenNlpModels;
import opennlp.tools.sentdetect.ThreadSafeSentenceDetectorME;
import opennlp.tools.util.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentence-packed chunking with model-based OpenNLP boundaries.
 *
 * <p>Boundary detection is the ONLY thing this chunker does differently from
 * {@code sentence-packed}: packing, overlap, runt merge, and hard splits are
 * the shared {@link SentencePacking} mechanics, so switching boundary
 * strategies never changes packing behavior.
 *
 * <p><b>Model pinning.</b> {@link ChunkSpec#boundary()} must be
 * {@code "opennlp:<sha256-hex>"} (a prefix of at least {@value #MIN_PIN_HEX}
 * hex chars). The model bytes are resolved from the
 * {@value #MODEL_PATH_PROPERTY} system property, the
 * {@value #MODEL_PATH_ENV} environment variable, or the classpath resource
 * {@value #CLASSPATH_MODEL} (in that order), and their SHA-256 must match the
 * pin. There is no floating default: a swapped model file fails loudly
 * instead of silently re-chunking the corpus, which is the same contract
 * rules-v1 enforces through its version pin.
 *
 * <p>Bump {@link #IMPL_VERSION} on ANY behavior change; it participates in
 * the collection's plan digest.
 */
public final class OpenNlpSentenceChunker implements Chunker {

    public static final String NAME = "opennlp-sentence";
    public static final int IMPL_VERSION = 1;

    /** Delegated pin/model constants; see {@link OpenNlpModels}. */
    public static final String BOUNDARY_PREFIX = OpenNlpModels.BOUNDARY_PREFIX;
    public static final String MODEL_PATH_PROPERTY = OpenNlpModels.MODEL_PATH_PROPERTY;
    public static final String MODEL_PATH_ENV = OpenNlpModels.MODEL_PATH_ENV;
    public static final String CLASSPATH_MODEL = OpenNlpModels.CLASSPATH_MODEL;
    public static final int MIN_PIN_HEX = OpenNlpModels.MIN_PIN_HEX;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Chunk> chunk(String text, ChunkSpec spec, TokenCounter counter) {
        ChunkSpec resolved = spec.resolved();
        String pin = OpenNlpModels.requirePin(resolved.boundary());
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        ThreadSafeSentenceDetectorME detector = OpenNlpModels.sentenceDetector(pin);
        Span[] detected = detector.sentPosDetect(text);
        List<int[]> sentences = partition(text, detected);
        List<int[]> bounded = SentencePacking.boundToMaxTokens(
                text, sentences, resolved.maxTokens(), counter);
        return SentencePacking.pack(text, bounded, resolved, counter);
    }

    /**
     * OpenNLP spans exclude inter-sentence whitespace; chunk spans must
     * partition {@code [0, length)} so offsets reconstruct the text exactly.
     * Each sentence extends to the start of the next; the first absorbs any
     * leading whitespace and the last absorbs the tail. No detections at all
     * (e.g. whitespace-only text) yields one span covering everything.
     */
    private static List<int[]> partition(String text, Span[] detected) {
        if (detected.length == 0) {
            return List.of(new int[]{0, text.length()});
        }
        List<int[]> sentences = new ArrayList<>(detected.length);
        for (int i = 0; i < detected.length; i++) {
            int start = i == 0 ? 0 : detected[i].getStart();
            int end = i + 1 < detected.length ? detected[i + 1].getStart() : text.length();
            // Guard against pathological overlapping detections: spans must
            // stay strictly increasing for the packing invariants to hold.
            if (end <= start) {
                continue;
            }
            // Snap the start back to the previous end so no gap can appear
            // between consecutive sentences.
            if (!sentences.isEmpty()) {
                start = sentences.get(sentences.size() - 1)[1];
            }
            sentences.add(new int[]{start, end});
        }
        return sentences;
    }




}
