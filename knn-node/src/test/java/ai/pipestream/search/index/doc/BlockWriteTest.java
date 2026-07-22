package ai.pipestream.search.index.doc;

import ai.pipestream.search.index.CollectionConfig;
import ai.pipestream.search.index.CollectionManager;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.join.CheckJoinIndex;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P2 proofs at the Lucene level: block shape (stub last, ids on every
 * member), generation-bounded replacement, replay idempotence, and
 * out-of-order purge safety.
 */
class BlockWriteTest {

    private static final String COLLECTION = "blocks";

    @TempDir
    Path tempDir;

    private CollectionManager manager;
    private BlockWriter writer;

    @BeforeEach
    void setUp() throws IOException {
        manager = ai.pipestream.search.index.TestCollectionManagers.create(tempDir);
        manager.createCollection(new CollectionConfig(
                COLLECTION, 4, VectorSimilarityFunction.COSINE, 1, "",
                true, "t.Chunk", null, 0));
        writer = new BlockWriter();
        writer.collectionManager = manager;
    }

    private static Document chunk(int ordinal) {
        Document doc = new Document();
        doc.add(new StringField(BlockJoinFields.CHUNK_ID, "c" + ordinal, Field.Store.YES));
        doc.add(new KnnFloatVectorField("vector",
                new float[]{1f, ordinal * 0.1f, 0f, 0f}, VectorSimilarityFunction.COSINE));
        return doc;
    }

    private List<Document> block(String docId, long gen, int chunks) {
        List<Document> children = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            children.add(chunk(i));
        }
        return BlockJoinDocumentBuilder.build(docId, gen, new Document(), children, chunks);
    }

    private int count(org.apache.lucene.search.Query query) throws IOException {
        DirectoryReader reader = manager.getReader(COLLECTION, 0);
        try {
            return new IndexSearcher(reader).count(query);
        } finally {
            manager.releaseReader(reader);
        }
    }

    @Test
    void blockPassesCheckJoinIndexAndStubIsLast() throws IOException {
        writer.writeBlock(COLLECTION, 0, "doc-a", 1, block("doc-a", 1, 3));
        manager.commit(COLLECTION, 0);

        DirectoryReader reader = manager.getReader(COLLECTION, 0);
        try {
            CheckJoinIndex.check(reader, new QueryBitSetProducer(BlockJoinFields.PARENT_QUERY));
            Assertions.assertEquals(4, count(new TermQuery(new Term(BlockJoinFields.DOC_ID, "doc-a"))),
                    "3 chunks + 1 stub");
            Assertions.assertEquals(1, count(BlockJoinFields.PARENT_QUERY));
        } finally {
            manager.releaseReader(reader);
        }
    }

    @Test
    void builderRejectsMalformedBlocks() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BlockJoinDocumentBuilder.build("d", 1, new Document(), List.of(), 0),
                "a block with no children must be rejected");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BlockJoinDocumentBuilder.build("d", 0, new Document(), List.of(chunk(0)), 1),
                "generation must be positive");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BlockJoinDocumentBuilder.build("", 1, new Document(), List.of(chunk(0)), 1));
    }

    /** A stub-first block is exactly what CheckJoinIndex exists to catch. */
    @Test
    void stubFirstBlockFailsCheckJoinIndex() throws IOException {
        List<Document> good = block("doc-a", 1, 2);
        // Malformed: stub (last element) moved to the front.
        List<Document> malformed = new ArrayList<>();
        malformed.add(good.get(good.size() - 1));
        malformed.addAll(good.subList(0, good.size() - 1));

        org.apache.lucene.index.IndexWriter iw = manager.getWriter(COLLECTION, 0);
        iw.addDocuments(malformed);
        iw.commit();

        DirectoryReader reader = manager.getReader(COLLECTION, 0);
        try {
            Assertions.assertThrows(IllegalStateException.class, () ->
                    CheckJoinIndex.check(reader, new QueryBitSetProducer(BlockJoinFields.PARENT_QUERY)));
        } finally {
            manager.releaseReader(reader);
        }
    }

    @Test
    void newGenerationReplacesTheWholeBlock() throws IOException {
        writer.writeBlock(COLLECTION, 0, "doc-a", 1, block("doc-a", 1, 5));
        BlockWriter.BlockWriteResult second =
                writer.writeBlock(COLLECTION, 0, "doc-a", 2, block("doc-a", 2, 3));

        Assertions.assertEquals(3, second.chunkCount());
        Assertions.assertEquals(6, second.purgedDocs(), "5 old chunks + 1 old stub purged");
        Assertions.assertEquals(4, count(new TermQuery(new Term(BlockJoinFields.DOC_ID, "doc-a"))),
                "count returns to chunks+1 after the re-write");
        Assertions.assertEquals(0, count(BlockJoinFields.purgeQuery("doc-a", 2)),
                "no generation-1 member survives");
    }

    @Test
    void sameGenerationReplayIsANoOp() throws IOException {
        writer.writeBlock(COLLECTION, 0, "doc-a", 7, block("doc-a", 7, 2));
        Assertions.assertThrows(BlockWriter.StaleGenerationException.class,
                () -> writer.writeBlock(COLLECTION, 0, "doc-a", 7, block("doc-a", 7, 2)));
        Assertions.assertEquals(3, count(new TermQuery(new Term(BlockJoinFields.DOC_ID, "doc-a"))),
                "the replay must not duplicate the block");
    }

    @Test
    void olderGenerationIsRejected() throws IOException {
        writer.writeBlock(COLLECTION, 0, "doc-a", 5, block("doc-a", 5, 2));
        Assertions.assertThrows(BlockWriter.StaleGenerationException.class,
                () -> writer.writeBlock(COLLECTION, 0, "doc-a", 4, block("doc-a", 4, 2)));
    }

    /** The test a bare-term purge design fails: a late purge for g must not delete g+1. */
    @Test
    void latePurgeForOldGenerationCannotDeleteTheNewBlock() throws IOException {
        writer.writeBlock(COLLECTION, 0, "doc-a", 1, block("doc-a", 1, 2));
        writer.writeBlock(COLLECTION, 0, "doc-a", 2, block("doc-a", 2, 4));

        // The purge that SHOULD have run before generation 2 arrives late.
        int purged = writer.purgeParent(COLLECTION, 0, "doc-a", 2);
        Assertions.assertEquals(0, purged, "generation-1 members were already replaced");
        Assertions.assertEquals(5, count(new TermQuery(new Term(BlockJoinFields.DOC_ID, "doc-a"))),
                "the generation-2 block survives the late purge");
    }

    @Test
    void purgeAllGenerationsRemovesTheParent() throws IOException {
        writer.writeBlock(COLLECTION, 0, "doc-a", 3, block("doc-a", 3, 2));
        int purged = writer.purgeParent(COLLECTION, 0, "doc-a", 0);
        Assertions.assertEquals(3, purged);
        Assertions.assertEquals(0, count(new TermQuery(new Term(BlockJoinFields.DOC_ID, "doc-a"))));
        // Idempotent.
        Assertions.assertEquals(0, writer.purgeParent(COLLECTION, 0, "doc-a", 0));
    }

    @Test
    void lastGenerationTracksTheNewestWrite() throws IOException {
        Assertions.assertEquals(0, writer.lastGeneration(COLLECTION, 0, "doc-a"));
        writer.writeBlock(COLLECTION, 0, "doc-a", 3, block("doc-a", 3, 2));
        Assertions.assertEquals(3, writer.lastGeneration(COLLECTION, 0, "doc-a"));
        writer.writeBlock(COLLECTION, 0, "doc-a", 9, block("doc-a", 9, 1));
        Assertions.assertEquals(9, writer.lastGeneration(COLLECTION, 0, "doc-a"));
        Assertions.assertEquals(0, writer.lastGeneration(COLLECTION, 0, "doc-b"));
    }
}
