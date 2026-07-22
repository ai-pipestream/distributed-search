package ai.pipestream.search.index;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

/**
 * P1 proofs: collection identity round-trips, the parent field is wired at
 * writer creation for document-centric collections only, and the fork's
 * shared-floor block-join composition resolves from the republished jar.
 */
class CollectionManagerTest {

    @TempDir
    Path tempDir;

    private CollectionManager manager;

    @BeforeEach
    void setUp() {
        manager = new CollectionManager();
        manager.dataDir = tempDir.toString();
        manager.init();
    }

    private CollectionManager reload() {
        CollectionManager fresh = new CollectionManager();
        fresh.dataDir = tempDir.toString();
        fresh.init();
        return fresh;
    }

    @Test
    void documentCentricConfigRoundTrips() throws IOException {
        CollectionConfig created = manager.createCollection(new CollectionConfig(
                "articles", 4, VectorSimilarityFunction.COSINE, 2, "",
                true, "acme.kb.ArticleChunk",
                CollectionConfig.PlacementMode.BALANCED_SIMILARITY, 512));

        CollectionConfig loaded = reload().getConfig("articles");
        Assertions.assertEquals(created, loaded,
                "every document-centric identity field must survive a restart");
        Assertions.assertTrue(loaded.documentCentric());
        Assertions.assertEquals("acme.kb.ArticleChunk", loaded.chunkMessage());
        Assertions.assertEquals(CollectionConfig.PlacementMode.BALANCED_SIMILARITY, loaded.placement());
        Assertions.assertEquals(512, loaded.maxChunksPerDocument());
    }

    @Test
    void flatConfigStaysFlatAndOldJsonLoads() throws IOException {
        manager.createCollection("legacy", 8, VectorSimilarityFunction.EUCLIDEAN, 1, "model-x");
        CollectionConfig loaded = reload().getConfig("legacy");
        Assertions.assertFalse(loaded.documentCentric());
        Assertions.assertEquals(CollectionConfig.PlacementMode.HASH_BY_DOC_ID, loaded.placement());
        Assertions.assertEquals(CollectionConfig.DEFAULT_MAX_CHUNKS_PER_DOCUMENT,
                loaded.maxChunksPerDocument());
    }

    @Test
    void documentCentricWriterCarriesParentField() throws IOException {
        manager.createCollection(new CollectionConfig(
                "doccentric", 4, VectorSimilarityFunction.COSINE, 1, "",
                true, "t.Chunk", null, 0));
        IndexWriter writer = manager.getWriter("doccentric", 0);
        Assertions.assertEquals(CollectionManager.PARENT_FIELD,
                writer.getConfig().getParentField());

        manager.createCollection("flat", 4, VectorSimilarityFunction.COSINE, 1, "");
        IndexWriter flatWriter = manager.getWriter("flat", 0);
        Assertions.assertNull(flatWriter.getConfig().getParentField(),
                "flat collections must not carry a parent field");
        manager.close();
    }

    /**
     * The reason documentCentric is create-time-immutable: Lucene refuses a
     * parent field on an index whose segments already have fields.
     */
    @Test
    void parentFieldCannotBeRetrofitted() throws IOException {
        Path dir = tempDir.resolve("retrofit");
        try (IndexWriter flat = new IndexWriter(FSDirectory.open(dir), new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new StringField("doc_id", "a", Field.Store.YES));
            flat.addDocument(doc);
            flat.commit();
        }

        IndexWriterConfig withParent = new IndexWriterConfig()
                .setParentField(CollectionManager.PARENT_FIELD);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new IndexWriter(FSDirectory.open(dir), withParent),
                "adding a parent field to an existing flat index must fail");
    }

    @Test
    void replaceConfigFlipsOnlyEmptyCollections() throws IOException {
        CollectionConfig flat = manager.createCollection(
                "flippable", 4, VectorSimilarityFunction.COSINE, 1, "");
        CollectionConfig docCentric = new CollectionConfig(
                flat.name(), flat.vectorDimension(), flat.similarity(), flat.numShards(),
                flat.embeddingModel(), true, "t.Chunk", null, 0);

        // Empty: the flip is legal and the next writer carries the parent field.
        manager.replaceConfig(docCentric);
        Assertions.assertTrue(manager.getConfig("flippable").documentCentric());
        IndexWriter writer = manager.getWriter("flippable", 0);
        Assertions.assertEquals(CollectionManager.PARENT_FIELD, writer.getConfig().getParentField());

        // Non-empty: the flip must be refused.
        Document doc = new Document();
        doc.add(new StringField("doc_id", "a", Field.Store.YES));
        writer.addDocument(doc);
        writer.commit();
        CollectionConfig backToFlat = new CollectionConfig(
                flat.name(), flat.vectorDimension(), flat.similarity(), flat.numShards(),
                flat.embeddingModel());
        Assertions.assertThrows(IllegalStateException.class,
                () -> manager.replaceConfig(backToFlat));
        manager.close();
    }

    /** The republished fork jar must carry the shared-floor block-join composition. */
    @Test
    void forkCompositionClassesResolve() {
        // Compile-time references — the build fails if the jar predates the
        // composition; the assertions just keep the references live.
        Assertions.assertNotNull(org.apache.lucene.search.join.SharedFloorDiversifyingKnnCollectorManager.class);
        Assertions.assertNotNull(org.apache.lucene.sandbox.search.knn.GlobalKnnFloor.class);
        Assertions.assertNotNull(org.apache.lucene.sandbox.search.knn.SharedFloorKnnCollectorManager.class);
        Assertions.assertNotNull(org.apache.lucene.search.join.DiversifyingChildrenFloatKnnVectorQuery.class);
    }
}
