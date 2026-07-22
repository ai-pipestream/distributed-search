package ai.pipestream.search.index;

import java.nio.file.Path;

/** Builds CollectionManager instances for tests outside this package. */
public final class TestCollectionManagers {

    private TestCollectionManagers() {
    }

    public static CollectionManager create(Path dataDir) {
        CollectionManager manager = new CollectionManager();
        manager.dataDir = dataDir.toString();
        manager.init();
        return manager;
    }
}
