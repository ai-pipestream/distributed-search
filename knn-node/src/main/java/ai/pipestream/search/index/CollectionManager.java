package ai.pipestream.search.index;

import ai.pipestream.index.v1.VectorSimilarity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages collection lifecycle: creation, deletion, shard IndexWriters, and NRT readers.
 * <p>
 * On-disk layout:
 * <pre>
 *   {data_dir}/collections/{name}/collection.json   — CollectionConfig as JSON
 *   {data_dir}/collections/{name}/shard-{i}/         — Lucene index per shard
 * </pre>
 */
@ApplicationScoped
public class CollectionManager {

    private static final Logger LOG = Logger.getLogger(CollectionManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "knn.data.dir", defaultValue = "data")
    String dataDir;

    /** collection name → config */
    private final ConcurrentHashMap<String, CollectionConfig> configs = new ConcurrentHashMap<>();

    /** "collection/shard-N" → IndexWriter */
    private final ConcurrentHashMap<String, IndexWriter> writers = new ConcurrentHashMap<>();

    /** "collection/shard-N" → DirectoryReader (NRT) */
    private final ConcurrentHashMap<String, DirectoryReader> readers = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        Path collectionsDir = Path.of(dataDir, "collections");
        if (!Files.exists(collectionsDir)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(collectionsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path configFile = dir.resolve("collection.json");
                if (Files.exists(configFile)) {
                    try {
                        CollectionConfig config = readConfig(configFile);
                        configs.put(config.name(), config);
                        LOG.infof("Loaded collection: %s (dim=%d, shards=%d)", config.name(), config.vectorDimension(), config.numShards());
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to load collection config from %s", configFile);
                    }
                }
            });
        } catch (IOException e) {
            LOG.errorf(e, "Failed to scan collections directory: %s", collectionsDir);
        }
    }

    /**
     * Create a new collection. Persists config to disk and creates shard directories.
     */
    public CollectionConfig createCollection(String name, int vectorDimension,
                                             VectorSimilarityFunction similarity,
                                             int numShards, String embeddingModel) throws IOException {
        if (configs.containsKey(name)) {
            throw new IllegalArgumentException("Collection already exists: " + name);
        }

        CollectionConfig config = new CollectionConfig(name, vectorDimension, similarity, numShards, embeddingModel);

        // Create directory structure
        Path collectionDir = collectionDir(name);
        Files.createDirectories(collectionDir);

        for (int i = 0; i < numShards; i++) {
            Files.createDirectories(shardDir(name, i));
        }

        // Persist config
        writeConfig(collectionDir.resolve("collection.json"), config);
        configs.put(name, config);

        LOG.infof("Created collection: %s (dim=%d, similarity=%s, shards=%d)", name, vectorDimension, similarity, numShards);
        return config;
    }

    /**
     * Get collection config by name.
     */
    public CollectionConfig getConfig(String name) {
        return configs.get(name);
    }

    /**
     * List all known collections.
     */
    public Collection<CollectionConfig> listCollections() {
        return configs.values();
    }

    /**
     * Delete a collection — closes writers/readers and removes from disk.
     */
    public boolean deleteCollection(String name) throws IOException {
        CollectionConfig config = configs.remove(name);
        if (config == null) {
            return false;
        }

        // Close all writers and readers for this collection
        for (int i = 0; i < config.numShards(); i++) {
            String key = writerKey(name, i);
            closeWriter(key);
            closeReader(key);
        }

        // Delete directory tree
        Path collectionDir = collectionDir(name);
        if (Files.exists(collectionDir)) {
            try (Stream<Path> walk = Files.walk(collectionDir)) {
                walk.sorted((a, b) -> b.compareTo(a)) // reverse order: files before dirs
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                LOG.warnf(e, "Failed to delete: %s", p);
                            }
                        });
            }
        }

        LOG.infof("Deleted collection: %s", name);
        return true;
    }

    /**
     * Get or create an IndexWriter for the given collection shard.
     */
    public IndexWriter getWriter(String collection, int shardId) throws IOException {
        String key = writerKey(collection, shardId);
        IndexWriter existing = writers.get(key);
        if (existing != null && existing.isOpen()) {
            return existing;
        }

        // Double-checked locking with computeIfAbsent
        return writers.compute(key, (k, w) -> {
            if (w != null && w.isOpen()) return w;
            try {
                Path dir = shardDir(collection, shardId);
                Files.createDirectories(dir);
                FSDirectory fsDir = FSDirectory.open(dir);
                IndexWriterConfig iwc = new IndexWriterConfig();
                return new IndexWriter(fsDir, iwc);
            } catch (IOException e) {
                throw new RuntimeException("Failed to open IndexWriter for " + k, e);
            }
        });
    }

    /**
     * Get a NRT DirectoryReader for the given collection shard.
     * Opens from IndexWriter if available (NRT), otherwise opens directly from directory.
     */
    public DirectoryReader getReader(String collection, int shardId) throws IOException {
        String key = writerKey(collection, shardId);

        DirectoryReader currentReader = readers.get(key);
        if (currentReader != null) {
            // Try to refresh for NRT
            DirectoryReader newReader = DirectoryReader.openIfChanged(currentReader);
            if (newReader != null) {
                readers.put(key, newReader);
                currentReader.close();
                return newReader;
            }
            return currentReader;
        }

        // Open new reader
        IndexWriter writer = writers.get(key);
        DirectoryReader reader;
        if (writer != null && writer.isOpen()) {
            reader = DirectoryReader.open(writer);
        } else {
            Path dir = shardDir(collection, shardId);
            if (!Files.exists(dir)) {
                throw new IOException("Shard directory does not exist: " + dir);
            }
            reader = DirectoryReader.open(FSDirectory.open(dir));
        }
        readers.put(key, reader);
        return reader;
    }

    /**
     * Get document count for a specific shard.
     */
    public long getDocCount(String collection, int shardId) {
        String key = writerKey(collection, shardId);
        IndexWriter writer = writers.get(key);
        if (writer != null && writer.isOpen()) {
            return writer.getDocStats().numDocs;
        }
        // Fall back to reader if writer not open
        DirectoryReader reader = readers.get(key);
        if (reader != null) {
            return reader.numDocs();
        }
        return 0;
    }

    /**
     * Total doc count across all shards for a collection.
     */
    public long getTotalDocCount(String collection) {
        CollectionConfig config = configs.get(collection);
        if (config == null) return 0;
        long total = 0;
        for (int i = 0; i < config.numShards(); i++) {
            total += getDocCount(collection, i);
        }
        return total;
    }

    /**
     * Determine which shard a document belongs to.
     */
    public int routeToShard(String docId, int numShards) {
        return Math.floorMod(docId.hashCode(), numShards);
    }

    /**
     * Periodic commit for durability.
     */
    @Scheduled(every = "5s")
    void periodicCommit() {
        writers.forEach((key, writer) -> {
            if (writer.isOpen() && writer.hasUncommittedChanges()) {
                try {
                    writer.commit();
                } catch (IOException e) {
                    LOG.warnf(e, "Periodic commit failed for %s", key);
                }
            }
        });
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        close();
    }

    /**
     * Close all writers and readers.
     */
    public void close() {
        writers.forEach((key, writer) -> closeWriter(key));
        readers.forEach((key, reader) -> closeReader(key));
    }

    // --- Private helpers ---

    private Path collectionDir(String name) {
        return Path.of(dataDir, "collections", name);
    }

    private Path shardDir(String collection, int shardId) {
        return Path.of(dataDir, "collections", collection, "shard-" + shardId);
    }

    private String writerKey(String collection, int shardId) {
        return collection + "/shard-" + shardId;
    }

    private void closeWriter(String key) {
        IndexWriter writer = writers.remove(key);
        if (writer != null) {
            try {
                if (writer.isOpen()) {
                    writer.commit();
                    writer.close();
                }
            } catch (IOException e) {
                LOG.warnf(e, "Error closing writer %s", key);
            }
        }
    }

    private void closeReader(String key) {
        DirectoryReader reader = readers.remove(key);
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                LOG.warnf(e, "Error closing reader %s", key);
            }
        }
    }

    private void writeConfig(Path path, CollectionConfig config) throws IOException {
        MAPPER.writeValue(path.toFile(), new CollectionConfigJson(
                config.name(), config.vectorDimension(),
                config.similarity().name(), config.numShards(),
                config.embeddingModel()
        ));
    }

    private CollectionConfig readConfig(Path path) throws IOException {
        CollectionConfigJson json = MAPPER.readValue(path.toFile(), CollectionConfigJson.class);
        return new CollectionConfig(
                json.name, json.vectorDimension,
                VectorSimilarityFunction.valueOf(json.similarity),
                json.numShards, json.embeddingModel
        );
    }

    /** JSON-serializable form of CollectionConfig. */
    static class CollectionConfigJson {
        public String name;
        public int vectorDimension;
        public String similarity;
        public int numShards;
        public String embeddingModel;

        public CollectionConfigJson() {}

        public CollectionConfigJson(String name, int vectorDimension, String similarity,
                                    int numShards, String embeddingModel) {
            this.name = name;
            this.vectorDimension = vectorDimension;
            this.similarity = similarity;
            this.numShards = numShards;
            this.embeddingModel = embeddingModel;
        }
    }

    /** Convert proto VectorSimilarity to Lucene VectorSimilarityFunction. */
    public static VectorSimilarityFunction toLuceneSimilarity(VectorSimilarity protoSimilarity) {
        return switch (protoSimilarity) {
            case VECTOR_SIMILARITY_COSINE, VECTOR_SIMILARITY_UNSPECIFIED -> VectorSimilarityFunction.COSINE;
            case VECTOR_SIMILARITY_DOT_PRODUCT -> VectorSimilarityFunction.DOT_PRODUCT;
            case VECTOR_SIMILARITY_EUCLIDEAN -> VectorSimilarityFunction.EUCLIDEAN;
            default -> VectorSimilarityFunction.COSINE;
        };
    }
}
