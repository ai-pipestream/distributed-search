package ai.pipestream.search.node;

import ai.pipestream.search.embeddings.EmbeddingProvider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic in-process embedding provider for mode-A tests: a pure
 * function of the text (FNV-1a mixes), 4 dims, never zero-norm. Registered
 * via META-INF/services on the test classpath.
 */
public final class TestEmbeddingProvider implements EmbeddingProvider {

    public static final String MODEL = "test-model-4d";

    @Override
    public String name() {
        return "test-hash";
    }

    @Override
    public boolean supports(String model) {
        return MODEL.equals(model);
    }

    @Override
    public int dims(String model) {
        return 4;
    }

    @Override
    public List<float[]> embed(String model, List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedOne(text));
        }
        return vectors;
    }

    /** Shared with tests that need the expected vector for a known text. */
    public static float[] embedOne(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        float[] vector = new float[]{
                1f + ((hash & 0xff) / 256f),
                ((hash >>> 8) & 0xff) / 256f,
                ((hash >>> 16) & 0xff) / 256f,
                0.5f
        };
        float norm = 0;
        for (float f : vector) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
