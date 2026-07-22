package ai.pipestream.search.query.knn;

import ai.pipestream.search.index.doc.BlockJoinFields;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.sandbox.search.knn.GlobalKnnFloor;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.DiversifyingChildrenFloatKnnVectorQuery;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P4 proofs at the Lucene level: the floor holds PARENT maxima (the RFC's
 * child-score floor would silently lose recall), floor sharing never changes
 * results, and the searchLeaf override removes core's parent-count-vs-chunk-
 * quota exact-search cliff.
 */
class SharedFloorParentJoinQueryTest {

    private static final String VECTOR_FIELD = "embedding";
    private static final BitSetProducer PARENTS =
            new QueryBitSetProducer(BlockJoinFields.PARENT_QUERY);

    private static Document chunk(String tag, float[] vector) {
        Document doc = new Document();
        doc.add(new StringField("tag", tag, Field.Store.NO));
        doc.add(new KnnFloatVectorField(VECTOR_FIELD, vector, VectorSimilarityFunction.COSINE));
        return doc;
    }

    private static Document stub(String docId) {
        Document doc = new Document();
        doc.add(new StringField(BlockJoinFields.DOC_ID, docId, Field.Store.YES));
        doc.add(new StringField(BlockJoinFields.PARENT_MARKER, BlockJoinFields.PARENT_VALUE,
                Field.Store.NO));
        return doc;
    }

    private static void addBlock(IndexWriter writer, String docId,
                                 List<float[]> vectors) throws IOException {
        List<Document> block = new ArrayList<>();
        for (float[] vector : vectors) {
            block.add(chunk("all", vector));
        }
        block.add(stub(docId));
        writer.addDocuments(block);
    }

    private static IndexWriter newWriter(Directory dir) throws IOException {
        return new IndexWriter(dir, new IndexWriterConfig()
                .setParentField("_parent"));
    }

    /**
     * The single most important correction to the RFC: the floor must hold
     * PARENT maxima. One 50-chunk document whose chunks all outscore a
     * 1-chunk document; a chunk-score floor would sit at the 50-chunk
     * document's second-best chunk, above the true 2nd-parent cutoff.
     */
    @Test
    void floorHoldsParentMaximaNotChunkScores() throws IOException {
        try (Directory dir = new ByteBuffersDirectory()) {
            try (IndexWriter writer = newWriter(dir)) {
                List<float[]> bigDoc = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    // All chunks of doc-big score higher than doc-small's chunk.
                    bigDoc.add(new float[]{1f, 0.001f * i, 0f, 0f});
                }
                addBlock(writer, "doc-big", bigDoc);
                addBlock(writer, "doc-small", List.of(new float[]{1f, 1.6f, 0f, 0f}));
                writer.commit();
            }

            float[] query = {1f, 0f, 0f, 0f};
            GlobalKnnFloor floor = new GlobalKnnFloor(2);
            var manager = DocumentCentricKnnFactory.manager(2, floor, PARENTS, 1f, 1);

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs top = searcher.search(new SharedFloorParentJoinQuery(
                        VECTOR_FIELD, query, null, 2, PARENTS, manager), 2);
                Assertions.assertEquals(2, top.scoreDocs.length);

                float smallScore = VectorSimilarityFunction.COSINE.compare(
                        query, new float[]{1f, 1.6f, 0f, 0f});
                float bigSecondChunk = VectorSimilarityFunction.COSINE.compare(
                        query, new float[]{1f, 0.001f * 48, 0f, 0f});

                // The floor is the 2nd-best PARENT score = doc-small's score...
                Assertions.assertEquals(smallScore, floor.floor(), 1e-6f,
                        "the floor must hold the 2nd-best PARENT score");
                // ...which a chunk-score floor would overshoot: doc-big's
                // 2nd-best chunk outscores doc-small entirely.
                Assertions.assertTrue(floor.floor() < bigSecondChunk,
                        "a chunk-score floor would sit at " + bigSecondChunk
                                + " and prune doc-small (" + smallScore + ") out of the results");
            }
        }
    }

    private record Shard(Directory dir, DirectoryReader reader, IndexSearcher searcher) {}

    private static List<Shard> buildTwoShards() throws IOException {
        List<Shard> shards = new ArrayList<>();
        Random random = new Random(42);
        for (int s = 0; s < 2; s++) {
            Directory dir = new ByteBuffersDirectory();
            try (IndexWriter writer = newWriter(dir)) {
                for (int p = 0; p < 150; p++) {
                    List<float[]> vectors = new ArrayList<>();
                    for (int c = 0; c < 2; c++) {
                        float[] v = new float[8];
                        float norm = 0;
                        for (int i = 0; i < 8; i++) {
                            v[i] = random.nextFloat();
                            norm += v[i] * v[i];
                        }
                        norm = (float) Math.sqrt(norm);
                        for (int i = 0; i < 8; i++) {
                            v[i] /= norm;
                        }
                        vectors.add(v);
                    }
                    addBlock(writer, "s" + s + "-doc-" + p, vectors);
                }
                writer.commit();
            }
            DirectoryReader reader = DirectoryReader.open(dir);
            shards.add(new Shard(dir, reader, new IndexSearcher(reader)));
        }
        return shards;
    }

    private record Merged(List<String> ids, List<Float> scores, long visited) {}

    private static Merged run(List<Shard> shards, float[] query, int k,
                              boolean sharedFloor) throws IOException {
        GlobalKnnFloor shared = sharedFloor ? new GlobalKnnFloor(k) : null;
        AtomicLong visited = new AtomicLong();
        record Entry(String id, float score) {}
        List<Entry> all = new ArrayList<>();
        for (Shard shard : shards) {
            GlobalKnnFloor floor = sharedFloor ? shared : new GlobalKnnFloor(k);
            var manager = new CountingKnnCollectorManager(
                    DocumentCentricKnnFactory.manager(k, floor, PARENTS, 0.5f, 1), visited);
            TopDocs top = shard.searcher().search(new SharedFloorParentJoinQuery(
                    VECTOR_FIELD, query, null, k, PARENTS, manager), k);
            for (ScoreDoc sd : top.scoreDocs) {
                // Resolve the parent doc id through the stub.
                LeafReaderContext leaf = shard.reader().leaves().get(
                        org.apache.lucene.index.ReaderUtil.subIndex(sd.doc, shard.reader().leaves()));
                var bits = PARENTS.getBitSet(leaf);
                int stubLocal = bits.nextSetBit(sd.doc - leaf.docBase);
                String id = leaf.reader().storedFields().document(stubLocal)
                        .get(BlockJoinFields.DOC_ID);
                all.add(new Entry(id, sd.score));
            }
        }
        all.sort(Comparator.comparingDouble(Entry::score).reversed()
                .thenComparing(Entry::id));
        List<String> ids = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        for (Entry entry : all.subList(0, Math.min(k, all.size()))) {
            ids.add(entry.id());
            scores.add(entry.score());
        }
        return new Merged(ids, scores, visited.get());
    }

    /** Floor engaged vs disengaged: identical merged documents, never more visits. */
    @Test
    void sharedFloorNeverChangesResultsAndNeverVisitsMore() throws IOException {
        List<Shard> shards = buildTwoShards();
        try {
            float[] query = new float[]{1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f};
            float norm = (float) Math.sqrt(4);
            for (int i = 0; i < 4; i++) {
                query[i] /= norm;
            }

            Merged baseline = run(shards, query, 20, false);
            Merged engaged = run(shards, query, 20, true);

            Assertions.assertEquals(baseline.ids(), engaged.ids(),
                    "floor sharing must never change the merged ranking");
            for (int i = 0; i < baseline.scores().size(); i++) {
                Assertions.assertEquals(baseline.scores().get(i), engaged.scores().get(i),
                        "scores must be identical, float for float");
            }
            Assertions.assertTrue(engaged.visited() <= baseline.visited(),
                    "the shared floor must never visit more (baseline="
                            + baseline.visited() + ", engaged=" + engaged.visited() + ")");
        } finally {
            for (Shard shard : shards) {
                shard.reader().close();
                shard.dir().close();
            }
        }
    }

    /** Instrumented stock query: counts exact-search fallbacks. */
    private static class CountingStockQuery extends DiversifyingChildrenFloatKnnVectorQuery {
        final AtomicInteger exactSearches;

        CountingStockQuery(String field, float[] target, org.apache.lucene.search.Query childFilter,
                           int k, BitSetProducer parents, AtomicInteger counter) {
            super(field, target, childFilter, k, parents);
            this.exactSearches = counter;
        }

        @Override
        protected TopDocs exactSearch(LeafReaderContext context, DocIdSetIterator acceptIterator,
                                      QueryTimeout queryTimeout) throws IOException {
            exactSearches.incrementAndGet();
            return super.exactSearch(context, acceptIterator, queryTimeout);
        }
    }

    /** Instrumented shared-floor query: counts exact-search fallbacks. */
    private static class CountingFloorQuery extends SharedFloorParentJoinQuery {
        final AtomicInteger exactSearches;

        CountingFloorQuery(String field, float[] target, org.apache.lucene.search.Query childFilter,
                           int k, BitSetProducer parents,
                           org.apache.lucene.search.knn.KnnCollectorManager manager,
                           AtomicInteger counter) {
            super(field, target, childFilter, k, parents, manager);
            this.exactSearches = counter;
        }

        @Override
        protected TopDocs exactSearch(LeafReaderContext context, DocIdSetIterator acceptIterator,
                                      QueryTimeout queryTimeout) throws IOException {
            exactSearches.incrementAndGet();
            return super.exactSearch(context, acceptIterator, queryTimeout);
        }
    }

    /**
     * The cliff: with a filter and k above the leaf's PARENT count, core
     * compares the parent count against a chunk-derived quota and silently
     * falls back to full exact search on every leaf. The override compares
     * against what the leaf can actually return.
     */
    @Test
    void searchLeafOverrideRemovesTheExactSearchCliff() throws IOException {
        try (Directory dir = new ByteBuffersDirectory()) {
            Random random = new Random(7);
            try (IndexWriter writer = newWriter(dir)) {
                for (int p = 0; p < 10; p++) {
                    List<float[]> vectors = new ArrayList<>();
                    for (int c = 0; c < 30; c++) {
                        float[] v = new float[8];
                        float norm = 0;
                        for (int i = 0; i < 8; i++) {
                            v[i] = random.nextFloat();
                            norm += v[i] * v[i];
                        }
                        norm = (float) Math.sqrt(norm);
                        for (int i = 0; i < 8; i++) {
                            v[i] /= norm;
                        }
                        vectors.add(v);
                    }
                    addBlock(writer, "doc-" + p, vectors);
                }
                writer.commit();
            }

            float[] query = new float[8];
            query[0] = 1f;
            // Filter accepting every child: forces the filtered code path.
            org.apache.lucene.search.Query filter =
                    new TermQuery(new org.apache.lucene.index.Term("tag", "all"));
            int k = 50;   // > 10 parents in the leaf

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(reader);

                AtomicInteger stockExact = new AtomicInteger();
                TopDocs stockTop = searcher.search(new CountingStockQuery(
                        VECTOR_FIELD, query, filter, k, PARENTS, stockExact), k);
                Assertions.assertTrue(stockExact.get() > 0,
                        "core's predicate must fall back to exact search here (the cliff is real)");

                AtomicInteger floorExact = new AtomicInteger();
                GlobalKnnFloor floor = new GlobalKnnFloor(k);
                var manager = DocumentCentricKnnFactory.manager(k, floor, PARENTS, 1f, 1);
                TopDocs floorTop = searcher.search(new CountingFloorQuery(
                        VECTOR_FIELD, query, filter, k, PARENTS, manager, floorExact), k);
                Assertions.assertEquals(0, floorExact.get(),
                        "the override must accept the approximate result: the leaf holds "
                                + "only 10 parents, which IS everything it can return");

                // And the fix loses nothing: same 10 parents either way.
                Assertions.assertEquals(stockTop.scoreDocs.length, floorTop.scoreDocs.length);
                for (int i = 0; i < stockTop.scoreDocs.length; i++) {
                    Assertions.assertEquals(stockTop.scoreDocs[i].score, floorTop.scoreDocs[i].score,
                            1e-6f, "the approximate path must find the same parents exact did");
                }
            }
        }
    }
}
