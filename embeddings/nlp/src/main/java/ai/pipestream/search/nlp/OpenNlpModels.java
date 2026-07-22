package ai.pipestream.search.nlp;

import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.sentdetect.ThreadSafeSentenceDetectorME;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Digest-pinned OpenNLP model loading, shared by every consumer in this
 * module (chunker, pipeline). A pin is the SHA-256 of the exact model bytes,
 * written {@code "opennlp:<hex>"} (prefix of at least {@value #MIN_PIN_HEX}
 * chars). There is no floating default: a swapped model file fails loudly
 * instead of silently changing boundaries or annotations.
 */
public final class OpenNlpModels {

    public static final String BOUNDARY_PREFIX = "opennlp:";
    public static final String MODEL_PATH_PROPERTY = "pipestream.opennlp.sentdetect.model";
    public static final String MODEL_PATH_ENV = "OPENNLP_SENTDETECT_MODEL";
    public static final String CLASSPATH_MODEL = "/models/opennlp-sentdetect.bin";
    /** Shortest accepted digest pin; full 64-char digests are preferred. */
    public static final int MIN_PIN_HEX = 16;

    /** Loaded detectors keyed by their model's full digest. */
    private static final ConcurrentHashMap<String, ThreadSafeSentenceDetectorME> DETECTORS =
            new ConcurrentHashMap<>();

    private OpenNlpModels() {
    }

    /** Extracts and validates the digest pin from an {@code opennlp:<hex>} boundary. */
    public static String requirePin(String boundary) {
        if (!boundary.startsWith(BOUNDARY_PREFIX)) {
            throw new IllegalArgumentException(
                    "Boundary '" + boundary + "' is not an OpenNLP model pin; expected "
                            + "\"opennlp:<sha256-hex>\"");
        }
        String pin = boundary.substring(BOUNDARY_PREFIX.length()).toLowerCase();
        if (pin.length() < MIN_PIN_HEX || pin.length() > 64 || !pin.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(
                    "OpenNLP model pin must be " + MIN_PIN_HEX + "..64 hex chars, got '" + pin + "'");
        }
        return pin;
    }

    /** The pinned sentence detector; loads and digest-verifies on first use. */
    public static ThreadSafeSentenceDetectorME sentenceDetector(String pin) {
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
                            + digest + " but the pin is " + BOUNDARY_PREFIX + pin
                            + ". Refusing to run with an unpinned model.");
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
            try (InputStream in = OpenNlpModels.class.getResourceAsStream(CLASSPATH_MODEL)) {
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
