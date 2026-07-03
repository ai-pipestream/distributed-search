package ai.pipestream.search.query;

import ai.pipestream.search.v1alpha1.AnalyzerRef;
import ai.pipestream.search.v1alpha1.BooleanFieldSchema;
import ai.pipestream.search.v1alpha1.BuiltinAnalyzer;
import ai.pipestream.search.v1alpha1.CollectionSchema;
import ai.pipestream.search.v1alpha1.DateFieldSchema;
import ai.pipestream.search.v1alpha1.DenseVectorFieldSchema;
import ai.pipestream.search.v1alpha1.FieldSchema;
import ai.pipestream.search.v1alpha1.KeywordFieldSchema;
import ai.pipestream.search.v1alpha1.NumericFieldSchema;
import ai.pipestream.search.v1alpha1.NumericType;
import ai.pipestream.search.v1alpha1.Query;
import ai.pipestream.search.v1alpha1.TextFieldSchema;
import ai.pipestream.search.v1alpha1.VectorSimilarity;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tiny in-memory index (6 docs) plus the matching v1alpha1 CollectionSchema,
 * shared by the query compiler tests. Field encodings follow the
 * {@link QueryCompiler} contract: LongPoint for int64/date, DoublePoint for
 * double, StringField terms for keyword and boolean ("T"/"F").
 *
 * <pre>
 * id    category title                     body (english)                 price rating created    active vector
 * doc1  fruit    apple apple apple         running shoes                  10    1.0    2026-01-01 T      [1.0, 0.0, 0.0]
 * doc2  fruit    apple apple banana        run fast                       20    2.0    2026-02-01 F      [0.0, 1.0, 0.0]
 * doc3  fruit    apple banana banana       jumping high                   30    3.0    2026-03-01 T      [0.6, 0.8, 0.0]
 * doc4  veggie   banana banana banana      walked slowly                  40    4.0    2026-04-01 F      [0.8, 0.6, 0.0]
 * doc5  veggie   quick brown fox jumps     the quick brown fox jumps ...  50    5.0    2026-05-01 T      [0.0, 0.0, 1.0]
 * doc6  veggie   lazy dog sleeps           lazy dogs sleep                60    6.0    2026-06-01 T      [0.7, 0.7, 0.1]
 * </pre>
 */
final class IndexFixture implements AutoCloseable {

    static final CollectionSchema SCHEMA = CollectionSchema.newBuilder()
            .addFields(FieldSchema.newBuilder().setName("id")
                    .setKeyword(KeywordFieldSchema.getDefaultInstance()).setStored(true))
            .addFields(FieldSchema.newBuilder().setName("category")
                    .setKeyword(KeywordFieldSchema.getDefaultInstance()))
            .addFields(FieldSchema.newBuilder().setName("title")
                    .setText(TextFieldSchema.getDefaultInstance()))
            .addFields(FieldSchema.newBuilder().setName("body")
                    .setText(TextFieldSchema.newBuilder().setAnalyzer(AnalyzerRef.newBuilder()
                            .setBuiltin(BuiltinAnalyzer.BUILTIN_ANALYZER_ENGLISH))))
            .addFields(FieldSchema.newBuilder().setName("price")
                    .setNumeric(NumericFieldSchema.newBuilder().setType(NumericType.NUMERIC_TYPE_INT64)))
            .addFields(FieldSchema.newBuilder().setName("rating")
                    .setNumeric(NumericFieldSchema.newBuilder().setType(NumericType.NUMERIC_TYPE_DOUBLE)))
            .addFields(FieldSchema.newBuilder().setName("created")
                    .setDate(DateFieldSchema.getDefaultInstance()))
            .addFields(FieldSchema.newBuilder().setName("active")
                    .setBoolean(BooleanFieldSchema.getDefaultInstance()))
            .addFields(FieldSchema.newBuilder().setName("embedding")
                    .setDenseVector(DenseVectorFieldSchema.newBuilder().setDims(3)
                            .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE)))
            .build();

    final Directory directory = new ByteBuffersDirectory();
    final DirectoryReader reader;
    final IndexSearcher searcher;
    final QueryCompiler compiler = new QueryCompiler(new AnalyzerRegistry());
    final HybridExecutor executor = new HybridExecutor();

    IndexFixture() throws IOException {
        Analyzer indexAnalyzer = new PerFieldAnalyzerWrapper(
                new StandardAnalyzer(), Map.of("body", new EnglishAnalyzer()));
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(indexAnalyzer))) {
            writer.addDocument(doc("doc1", "fruit", "apple apple apple", "running shoes",
                    10, 1.0, "2026-01-01", true, 1.0f, 0.0f, 0.0f));
            writer.addDocument(doc("doc2", "fruit", "apple apple banana", "run fast",
                    20, 2.0, "2026-02-01", false, 0.0f, 1.0f, 0.0f));
            writer.addDocument(doc("doc3", "fruit", "apple banana banana", "jumping high",
                    30, 3.0, "2026-03-01", true, 0.6f, 0.8f, 0.0f));
            writer.addDocument(doc("doc4", "veggie", "banana banana banana", "walked slowly",
                    40, 4.0, "2026-04-01", false, 0.8f, 0.6f, 0.0f));
            writer.addDocument(doc("doc5", "veggie", "quick brown fox jumps",
                    "the quick brown fox jumps over the lazy dog",
                    50, 5.0, "2026-05-01", true, 0.0f, 0.0f, 1.0f));
            writer.addDocument(doc("doc6", "veggie", "lazy dog sleeps", "lazy dogs sleep",
                    60, 6.0, "2026-06-01", true, 0.7f, 0.7f, 0.1f));
        }
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    private static Document doc(String id, String category, String title, String body,
                                long price, double rating, String createdDate, boolean active,
                                float x, float y, float z) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new StringField("category", category, Field.Store.NO));
        doc.add(new TextField("title", title, Field.Store.NO));
        doc.add(new TextField("body", body, Field.Store.NO));
        doc.add(new LongPoint("price", price));
        doc.add(new DoublePoint("rating", rating));
        doc.add(new LongPoint("created", Instant.parse(createdDate + "T00:00:00Z").toEpochMilli()));
        doc.add(new StringField("active",
                active ? QueryCompiler.BOOL_TRUE_TERM : QueryCompiler.BOOL_FALSE_TERM, Field.Store.NO));
        doc.add(new KnnFloatVectorField("embedding", new float[] {x, y, z}, VectorSimilarityFunction.COSINE));
        return doc;
    }

    QueryPlan compile(Query query) {
        return compiler.compile(query, SCHEMA);
    }

    /** Compile, execute, and return matching doc ids best-first. */
    List<String> search(Query query, int k) throws IOException {
        return ids(executor.execute(compile(query), searcher, k));
    }

    List<String> ids(TopDocs topDocs) throws IOException {
        StoredFields storedFields = searcher.storedFields();
        List<String> ids = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            ids.add(storedFields.document(scoreDoc.doc).get("id"));
        }
        return ids;
    }

    @Override
    public void close() throws IOException {
        reader.close();
        directory.close();
    }
}
