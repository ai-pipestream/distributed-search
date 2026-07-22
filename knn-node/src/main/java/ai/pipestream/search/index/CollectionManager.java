package ai.pipestream.search.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.FSDirectory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages collection lifecycle: creation, deletion, shard IndexWriters, and NRT readers.
 */
@ApplicationScoped
public class CollectionManager {

    private static final Logger LOG = Logger.getLogger(CollectionManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Doc-values field carrying the block-join parent marker (setParentField). */
    public static final String PARENT_FIELD = "_parent";

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

    public CollectionConfig createCollection(String name, int vectorDimension,
                                             VectorSimilarityFunction similarity,
                                             int numShards, String embeddingModel) throws IOException {
        return createCollection(new CollectionConfig(name, vectorDimension, similarity, numShards, embeddingModel));
    }

    public synchronized CollectionConfig createCollection(CollectionConfig config) throws IOException {
        String name = config.name();
        CollectionConfig existing = configs.get(name);
        if (existing != null) {
            if (existing.vectorDimension() == config.vectorDimension()
                    && existing.numShards() == config.numShards()
                    && existing.documentCentric() == config.documentCentric()) {
                LOG.infof("Collection '%s' already exists with matching config — returning existing", name);
                return existing;
            }
            throw new IllegalArgumentException(
                    "Collection already exists with different config: " + name
                            + " (existing dim=" + existing.vectorDimension() + "/shards=" + existing.numShards()
                            + "/documentCentric=" + existing.documentCentric()
                            + ", requested dim=" + config.vectorDimension() + "/shards=" + config.numShards()
                            + "/documentCentric=" + config.documentCentric() + ")");
        }

        Path collectionDir = collectionDir(name);
        Files.createDirectories(collectionDir);

        for (int i = 0; i < config.numShards(); i++) {
            Files.createDirectories(shardDir(name, i));
        }

        writeConfig(collectionDir.resolve("collection.json"), config);
        configs.put(name, config);

        LOG.infof("Created collection: %s (dim=%d, similarity=%s, shards=%d, documentCentric=%s)",
                name, config.vectorDimension(), config.similarity(), config.numShards(),
                config.documentCentric());
        return config;
    }

    public CollectionConfig getConfig(String name) {
        return configs.get(name);
    }

    /**
     * Replaces a collection's config in place. Flipping {@code documentCentric}
     * is only allowed while the collection is empty — Lucene refuses to add a
     * parent field to an existing index with fields — and closes any open
     * writers/readers so the next open picks up the new IndexWriterConfig.
     */
    public synchronized CollectionConfig replaceConfig(CollectionConfig newConfig) throws IOException {
        CollectionConfig existing = configs.get(newConfig.name());
        if (existing == null) {
            throw new IllegalArgumentException("Collection not found: " + newConfig.name());
        }
        if (existing.numShards() != newConfig.numShards()
                || existing.vectorDimension() != newConfig.vectorDimension()) {
            throw new IllegalArgumentException(
                    "numShards and vectorDimension are immutable for " + newConfig.name());
        }
        if (existing.documentCentric() != newConfig.documentCentric()) {
            if (getTotalDocCount(newConfig.name()) > 0) {
                throw new IllegalStateException(
                        "Cannot change document-centric mode of a non-empty collection: "
                                + newConfig.name());
            }
            for (int i = 0; i < existing.numShards(); i++) {
                closeWriter(writerKey(newConfig.name(), i));
                closeReader(writerKey(newConfig.name(), i));
            }
        }
        writeConfig(collectionDir(newConfig.name()).resolve("collection.json"), newConfig);
        configs.put(newConfig.name(), newConfig);
        return newConfig;
    }

    public Collection<CollectionConfig> listCollections() {
        return configs.values();
    }

    public synchronized boolean deleteCollection(String name) throws IOException {
        CollectionConfig config = configs.remove(name);
        if (config == null) {
            return false;
        }

        for (int i = 0; i < config.numShards(); i++) {
            String key = writerKey(name, i);
            closeWriter(key);
            closeReader(key);
        }

        Path collectionDir = collectionDir(name);
        if (Files.exists(collectionDir)) {
            try (Stream<Path> walk = Files.walk(collectionDir)) {
                walk.sorted((a, b) -> b.compareTo(a))
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

    public synchronized IndexWriter getWriter(String collection, int shardId) throws IOException {
        String key = writerKey(collection, shardId);
        IndexWriter existing = writers.get(key);
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        if (!configs.containsKey(collection)) {
            throw new IOException("Unknown collection: " + collection);
        }

        Path dir = shardDir(collection, shardId);
        Files.createDirectories(dir);
        FSDirectory fsDir = FSDirectory.open(dir);
        IndexWriterConfig iwc = new IndexWriterConfig();
        CollectionConfig config = configs.get(collection);
        if (config != null && config.documentCentric()) {
            // Block-join parent marker. Create-time only: Lucene refuses to add
            // a parent field to an index whose segments were written without it,
            // which is why documentCentric is immutable on CollectionConfig.
            iwc.setParentField(PARENT_FIELD);
        }
        IndexWriter writer = new IndexWriter(fsDir, iwc);
        writers.put(key, writer);
        return writer;
    }

    /**
     * Returns an incRef'd NRT reader for the shard. Throws
     * {@link IndexNotFoundException} for a shard that exists but has never
     * been written to (callers treat that as an empty shard, not an error).
     */
    public synchronized DirectoryReader getReader(String collection, int shardId) throws IOException {
        String key = writerKey(collection, shardId);

        DirectoryReader currentReader = readers.get(key);
        if (currentReader != null) {
            try {
                DirectoryReader newReader = DirectoryReader.openIfChanged(currentReader);
                if (newReader != null) {
                    readers.put(key, newReader);
                    currentReader.close();
                    newReader.incRef();
                    return newReader;
                }
                currentReader.incRef();
                return currentReader;
            } catch (AlreadyClosedException | IOException e) {
                // A reader opened from a writer that has since been closed (e.g.
                // by a tragic event) fails refresh forever. Evict and reopen cold
                // instead of poisoning the cache permanently.
                LOG.warnf(e, "Cached reader for %s is stale; evicting and reopening", key);
                readers.remove(key);
                try {
                    currentReader.close();
                } catch (Exception suppressed) {
                    LOG.debugf(suppressed, "Error closing stale reader for %s", key);
                }
            }
        }

        IndexWriter writer = writers.get(key);
        DirectoryReader reader;
        if (writer != null && writer.isOpen()) {
            reader = DirectoryReader.open(writer);
        } else {
            Path dir = shardDir(collection, shardId);
            if (!Files.exists(dir)) {
                throw new IndexNotFoundException("Shard directory does not exist: " + dir);
            }
            FSDirectory fsDir = FSDirectory.open(dir);
            if (!DirectoryReader.indexExists(fsDir)) {
                // Created but never written: an empty shard, not an error state.
                fsDir.close();
                throw new IndexNotFoundException("No commit in shard directory: " + dir);
            }
            reader = DirectoryReader.open(fsDir);
        }
        readers.put(key, reader);
        reader.incRef();
        return reader;
    }

    public void releaseReader(DirectoryReader reader) throws IOException {
        reader.decRef();
    }

    /** parent-stub bitset producers, one per collection (memoized per segment core). */
    private final ConcurrentHashMap<String, org.apache.lucene.search.join.BitSetProducer>
            parentsFilters = new ConcurrentHashMap<>();

    /**
     * The cached parent-stub bitset producer for a document-centric
     * collection. QueryBitSetProducer memoizes on the segment core key, so a
     * per-query instance would rebuild a FixedBitSet over maxDoc every leaf.
     */
    public org.apache.lucene.search.join.BitSetProducer getParentsFilter(String collection) {
        return parentsFilters.computeIfAbsent(collection, key ->
                new org.apache.lucene.search.join.QueryBitSetProducer(
                        ai.pipestream.search.index.doc.BlockJoinFields.PARENT_QUERY));
    }

    public long getDocCount(String collection, int shardId) {
        String key = writerKey(collection, shardId);
        IndexWriter writer = writers.get(key);
        if (writer != null && writer.isOpen()) {
            return writer.getDocStats().numDocs;
        }
        DirectoryReader reader = readers.get(key);
        if (reader != null) {
            return reader.numDocs();
        }
        return 0;
    }

    public long getTotalDocCount(String collection) {
        CollectionConfig config = configs.get(collection);
        if (config == null) return 0;
        long total = 0;
        for (int i = 0; i < config.numShards(); i++) {
            total += getDocCount(collection, i);
        }
        return total;
    }

    public int routeToShard(String docId, int numShards) {
        return Math.floorMod(docId.hashCode(), numShards);
    }

    public void flush(String collection, int shardId) throws IOException {
        String key = writerKey(collection, shardId);
        IndexWriter writer = writers.get(key);
        if (writer != null && writer.isOpen()) {
            writer.flush();
        }
    }

    public void commit(String collection, int shardId) throws IOException {
        String key = writerKey(collection, shardId);
        IndexWriter writer = writers.get(key);
        if (writer != null && writer.isOpen()) {
            writer.commit();
        }
    }

    /**
     * Commits every open writer of one collection, reporting failures instead
     * of swallowing them. This is the durability path behind FlushAck: a flush
     * acknowledgement must never be sent when a covering commit failed.
     *
     * @throws IOException naming every shard whose commit failed
     */
    public void commitCollection(String collection) throws IOException {
        CollectionConfig config = configs.get(collection);
        if (config == null) {
            throw new IOException("Unknown collection: " + collection);
        }
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < config.numShards(); i++) {
            IndexWriter writer = writers.get(writerKey(collection, i));
            if (writer == null) {
                continue;
            }
            try {
                if (writer.hasUncommittedChanges()) {
                    writer.commit();
                }
            } catch (Throwable t) {
                // AlreadyClosedException (tragic event) is unchecked; catch it too.
                LOG.errorf(t, "Commit failed for %s/shard-%d", collection, i);
                failures.add("shard-" + i + ": " + t);
            }
        }
        if (!failures.isEmpty()) {
            throw new IOException("Commit failed for collection '" + collection + "': "
                    + String.join("; ", failures));
        }
    }

    @Scheduled(every = "5s")
    public void periodicCommit() {
        writers.forEach((key, writer) -> {
            try {
                if (writer.isOpen() && writer.hasUncommittedChanges()) {
                    writer.commit();
                }
            } catch (Throwable t) {
                // Catch everything: an AlreadyClosedException (isOpen() is a
                // TOCTOU check) would otherwise abort the whole forEach pass and
                // leave the remaining writers uncommitted.
                LOG.warnf(t, "Periodic commit failed for %s", key);
            }
        });
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        close();
    }

    public synchronized void close() {
        writers.forEach((key, writer) -> closeWriter(key));
        readers.forEach((key, reader) -> closeReader(key));
    }

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
        // Temp-file-and-rename so a crash mid-write never leaves a truncated
        // config that poisons the next startup scan.
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), new CollectionConfigJson(
                config.name(), config.vectorDimension(),
                config.similarity().name(), config.numShards(),
                config.embeddingModel(),
                config.documentCentric(), config.chunkMessage(),
                config.placement().name(), config.maxChunksPerDocument()
        ));
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private CollectionConfig readConfig(Path path) throws IOException {
        CollectionConfigJson json = MAPPER.readValue(path.toFile(), CollectionConfigJson.class);
        return new CollectionConfig(
                json.name, json.vectorDimension,
                VectorSimilarityFunction.valueOf(json.similarity),
                json.numShards, json.embeddingModel,
                json.documentCentric,
                json.chunkMessage == null ? "" : json.chunkMessage,
                json.placement == null
                        ? null
                        : CollectionConfig.PlacementMode.valueOf(json.placement),
                json.maxChunksPerDocument
        );
    }

    static class CollectionConfigJson {
        public String name;
        public int vectorDimension;
        public String similarity;
        public int numShards;
        public String embeddingModel;
        // Absent in pre-document-centric configs; Jackson leaves the defaults.
        public boolean documentCentric;
        public String chunkMessage;
        public String placement;
        public int maxChunksPerDocument;

        public CollectionConfigJson() {}

        public CollectionConfigJson(String name, int vectorDimension, String similarity,
                                    int numShards, String embeddingModel,
                                    boolean documentCentric, String chunkMessage,
                                    String placement, int maxChunksPerDocument) {
            this.name = name;
            this.vectorDimension = vectorDimension;
            this.similarity = similarity;
            this.numShards = numShards;
            this.embeddingModel = embeddingModel;
            this.documentCentric = documentCentric;
            this.chunkMessage = chunkMessage;
            this.placement = placement;
            this.maxChunksPerDocument = maxChunksPerDocument;
        }
    }

    public static VectorSimilarityFunction toLuceneSimilarity(ai.pipestream.search.v1alpha1.VectorSimilarity protoSimilarity) {
        return switch (protoSimilarity) {
            case VECTOR_SIMILARITY_COSINE, VECTOR_SIMILARITY_UNSPECIFIED -> VectorSimilarityFunction.COSINE;
            case VECTOR_SIMILARITY_DOT_PRODUCT -> VectorSimilarityFunction.DOT_PRODUCT;
            case VECTOR_SIMILARITY_EUCLIDEAN -> VectorSimilarityFunction.EUCLIDEAN;
            case VECTOR_SIMILARITY_MAX_INNER_PRODUCT -> VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;
            default -> VectorSimilarityFunction.COSINE;
        };
    }

    public static VectorSimilarityFunction toLuceneSimilarity(ai.pipestream.index.v1.VectorSimilarity protoSimilarity) {
        return switch (protoSimilarity) {
            case VECTOR_SIMILARITY_COSINE, VECTOR_SIMILARITY_UNSPECIFIED -> VectorSimilarityFunction.COSINE;
            case VECTOR_SIMILARITY_DOT_PRODUCT -> VectorSimilarityFunction.DOT_PRODUCT;
            case VECTOR_SIMILARITY_EUCLIDEAN -> VectorSimilarityFunction.EUCLIDEAN;
            default -> VectorSimilarityFunction.COSINE;
        };
    }
}
