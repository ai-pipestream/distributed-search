package ai.pipestream.search.index.doc;

import ai.pipestream.search.index.CollectionManager;

/** Builds a {@link BlockWriter} for tests outside this package. */
public final class TestBlockWriters {

    private TestBlockWriters() {
    }

    public static BlockWriter create(CollectionManager manager) {
        BlockWriter writer = new BlockWriter();
        writer.collectionManager = manager;
        return writer;
    }
}
