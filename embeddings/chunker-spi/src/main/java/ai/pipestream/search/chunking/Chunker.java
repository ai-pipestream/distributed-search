package ai.pipestream.search.chunking;

import java.util.List;

/**
 * A deterministic text chunker, discovered via {@link java.util.ServiceLoader}
 * (plain Java, no framework types — same house style as embeddings-spi).
 *
 * <p>{@link #chunk} MUST be a pure function of
 * {@code (text, spec, counter)}: same inputs, same boundaries, every JVM,
 * forever. Every produced {@link Chunk} satisfies
 * {@code text.substring(c.startOffset(), c.endOffset()).equals(c.text())}.
 */
public interface Chunker {

    /** Stable strategy name, matched against {@link ChunkSpec#strategy()}. */
    String name();

    List<Chunk> chunk(String text, ChunkSpec spec, TokenCounter counter);
}
