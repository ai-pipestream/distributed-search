package ai.pipestream.search.index.placement;

/**
 * A deterministic chunk-placement algorithm.
 *
 * <p>{@link #place} MUST be a pure function: the same request produces the
 * same placement in every JVM, forever. Replay must reproduce byte-identical
 * blocks; any nondeterminism here silently corrupts generation replacement.
 */
public interface ChunkPlacementPolicy {

    /** Stable id, persisted in collection config; never reused. */
    String id();

    ChunkPlacement place(PlacementRequest request);
}
