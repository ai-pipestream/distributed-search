package ai.pipestream.search.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** ServiceLoader entry points for chunker discovery. */
public final class Chunkers {

    private Chunkers() {
    }

    public static List<Chunker> load() {
        List<Chunker> chunkers = new ArrayList<>();
        for (Chunker chunker : ServiceLoader.load(Chunker.class, Chunker.class.getClassLoader())) {
            chunkers.add(chunker);
        }
        return chunkers;
    }

    /**
     * The chunker registered under {@code name}.
     *
     * @throws IllegalArgumentException when none is registered
     */
    public static Chunker byName(String name) {
        List<String> seen = new ArrayList<>();
        for (Chunker chunker : ServiceLoader.load(Chunker.class, Chunker.class.getClassLoader())) {
            if (chunker.name().equals(name)) {
                return chunker;
            }
            seen.add(chunker.name());
        }
        throw new IllegalArgumentException(
                "No chunker named '" + name + "'; registered: " + seen);
    }
}
