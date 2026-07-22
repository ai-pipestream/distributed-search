package ai.pipestream.search.chunking.opennlp;

import ai.pipestream.search.chunking.Chunk;
import ai.pipestream.search.chunking.ChunkSpec;
import ai.pipestream.search.chunking.Chunker;
import ai.pipestream.search.chunking.SentencePacking;
import ai.pipestream.search.chunking.TokenCounter;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.sentdetect.ThreadSafeSentenceDetectorME;
import opennlp.tools.util.Span;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    public static final String BOUNDARY_PREFIX = "opennlp:";
    public static final String MODEL_PATH_PROPERTY = "pipestream.opennlp.sentdetect.model";
    public static final String MODEL_PATH_ENV = "OPENNLP_SENTDETECT_MODEL";
    public static final String CLASSPATH_MODEL = "/models/opennlp-sentdetect.bin";
    /** Shortest accepted digest pin; full 64-char digests are preferred. */
    public static final int MIN_PIN_HEX = 16;

    /** Loaded detectors keyed by their model's full digest. */
    private static final ConcurrentHashMap<String, ThreadSafeSentenceDetectorME> DETECTORS =
            new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Chunk> chunk(String text, ChunkSpec spec, TokenCounter counter) {
        ChunkSpec resolved = spec.resolved();
        String pin = requirePin(resolved.boundary());
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        ThreadSafeSentenceDetectorME detector = detector(pin);
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

    private static String requirePin(String boundary) {
        if (!boundary.startsWith(BOUNDARY_PREFIX)) {
            throw new IllegalArgumentException(
                    "Boundary '" + boundary + "' is not an OpenNLP model pin; this chunker "
                            + "requires \"opennlp:<sha256-hex>\"");
        }
        String pin = boundary.substring(BOUNDARY_PREFIX.length()).toLowerCase();
        if (pin.length() < MIN_PIN_HEX || pin.length() > 64 || !pin.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(
                    "OpenNLP model pin must be " + MIN_PIN_HEX + "..64 hex chars, got '" + pin + "'");
        }
        return pin;
    }

    private static ThreadSafeSentenceDetectorME detector(String pin) {
        // Fast path: a detector whose full digest matches the pin is loaded.
        for (var entry : DETECTORS.entrySet()) {
            if (entry.getKey().startsWith(pin)) {
                return entry.getValue();
            }
        }
        byte[] bytes = loadModelBytes();
        String digest = sha256(bytes);
        if (!digest.startsWith(pin)) {
            throw new IllegalArgumentException(
                    "OpenNLP sentence model digest mismatch: the resolved model is sha256:"
                            + digest + " but the collection pins " + BOUNDARY_PREFIX + pin
                            + ". Refusing to chunk with an unpinned model.");
        }
        return DETECTORS.computeIfAbsent(digest, key -> {
            try {
                return new ThreadSafeSentenceDetectorME(
                        new SentenceModel(new ByteArrayInputStream(bytes)));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load OpenNLP sentence model", e);
            }
        });
    }

    private static byte[] loadModelBytes() {
        String configured = System.getProperty(MODEL_PATH_PROPERTY, System.getenv(MODEL_PATH_ENV));
        try {
            if (configured != null && !configured.isBlank()) {
                return Files.readAllBytes(Path.of(configured));
            }
            try (InputStream in = OpenNlpSentenceChunker.class.getResourceAsStream(CLASSPATH_MODEL)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the OpenNLP sentence model", e);
        }
        throw new IllegalStateException(
                "No OpenNLP sentence model: set -D" + MODEL_PATH_PROPERTY + " or "
                        + MODEL_PATH_ENV + ", or bundle " + CLASSPATH_MODEL + " on the classpath");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
        }
    }
}
