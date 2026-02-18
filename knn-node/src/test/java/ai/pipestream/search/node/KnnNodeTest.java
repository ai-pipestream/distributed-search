package ai.pipestream.search.node;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@QuarkusTestResource(value = KnnNodeTest.IndexResource.class, restrictToAnnotatedClass = true)
public class KnnNodeTest {

    /**
     * Dynamically creates a Lucene index and provides its path to Quarkus.
     */
    public static class IndexResource implements QuarkusTestResourceLifecycleManager {
        private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(IndexResource.class);
        private Path tempIndexDir;

        @Override
        public Map<String, String> start() {
            String customPath = System.getProperty("knn.index.path");
            if (customPath != null) {
                LOG.infof("Using custom index path from system property: %s", customPath);
                tempIndexDir = Paths.get(customPath);
            } else {
                try {
                    tempIndexDir = Files.createTempDirectory("knn-test-index-");
                    LOG.infof("Created dynamic test index at: %s", tempIndexDir.toAbsolutePath());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create temp index dir", e);
                }
            }

            try {
                createTestIndex(tempIndexDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to index test data", e);
            }

            return Map.of(
                    "knn.index.path", tempIndexDir.toAbsolutePath().toString(),
                    "knn.shard.id", "0",
                    "knn.single.node", "true",
                    "quarkus.http.test-port", "0"
            );
        }

        private void createTestIndex(Path path) throws IOException {
            IndexWriterConfig iwc = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(FSDirectory.open(path), iwc)) {
                Random r = new Random(42);
                for (int i = 0; i < 1000; i++) {
                    float[] vector = new float[128];
                    for (int j = 0; j < 128; j++) {
                        vector[j] = r.nextFloat();
                    }
                    // Normalize for COSINE
                    float norm = 0;
                    for (float v : vector) norm += v * v;
                    norm = (float) Math.sqrt(norm);
                    for (int j = 0; j < vector.length; j++) vector[j] /= norm;

                    Document doc = new Document();
                    doc.add(new KnnFloatVectorField("vector", vector, VectorSimilarityFunction.COSINE));
                    doc.add(new StoredField("id", (long) i));
                    doc.add(new StoredField("chunk", "Document " + i + " with some text content for testing."));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
        }

        @Override
        public void stop() {
            // Temp dir is cleaned up by OS
        }
    }

    private List<Float> queryVector() {
        Random r = new Random(99);
        List<Float> vec = new ArrayList<>(128);
        float norm = 0;
        float[] raw = new float[128];
        for (int i = 0; i < 128; i++) {
            raw[i] = r.nextFloat();
            norm += raw[i] * raw[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < 128; i++) {
            vec.add(raw[i] / norm);
        }
        return vec;
    }

    // --- Standard (non-collaborative) search tests ---

    @Test
    public void testStandardSearchReturnsResults() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("k", 10)
                .queryParam("collaborative", false)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .body("hits", hasSize(10))
                .body("collaborative", is(false))
                .body("totalVisited", greaterThan(0));
    }

    @Test
    public void testStandardSearchRespectsK() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("k", 5)
                .queryParam("collaborative", false)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .body("hits", hasSize(5));
    }

    @Test
    public void testStandardSearchHitsHaveScores() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("k", 3)
                .queryParam("collaborative", false)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .body("hits[0].score", greaterThan(0.0f))
                .body("hits[1].score", greaterThan(0.0f))
                .body("hits[2].score", greaterThan(0.0f));
    }

    @Test
    public void testStandardSearchResultsSortedByScore() {
        float[] scores = given()
                .contentType(ContentType.JSON)
                .queryParam("k", 10)
                .queryParam("collaborative", false)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .extract().jsonPath().getObject("hits.score", float[].class);

        for (int i = 1; i < scores.length; i++) {
            assert scores[i - 1] >= scores[i]
                    : "Results not sorted: score[" + (i - 1) + "]=" + scores[i - 1] + " < score[" + i + "]=" + scores[i];
        }
    }

    @Test
    public void testStandardSearchReturnsChunks() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("k", 5)
                .queryParam("collaborative", false)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .body("hits[0].chunk", startsWith("Document "));
    }

    @Test
    public void testStandardSearchReportsMetrics() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("k", 10)
                .queryParam("collaborative", false)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .body("totalVisited", greaterThan(0))
                .body("searchTimeMs", greaterThanOrEqualTo(0));
    }

    // --- Collaborative search tests ---

    @Test
    public void testCollaborativeSearchReturnsResults() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("k", 10)
                .queryParam("collaborative", true)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .body("hits", hasSize(10))
                .body("collaborative", is(true))
                .body("totalVisited", greaterThan(0));
    }

    @Test
    public void testCollaborativeSearchReturnsChunks() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("k", 5)
                .queryParam("collaborative", true)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .body("hits[0].chunk", startsWith("Document "));
    }

    @Test
    public void testCollaborativeSearchReportsMetrics() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("k", 10)
                .queryParam("collaborative", true)
                .body(queryVector())
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .body("totalVisited", greaterThan(0))
                .body("searchTimeMs", greaterThanOrEqualTo(0));
    }

    // --- Both modes return same results (single-node, no cross-shard effect) ---

    @Test
    public void testBothModesReturnSameResultsSingleNode() {
        // On a single node with no peers, both modes should return identical results
        // because there's no cross-shard coordination to make a difference.
        List<Float> vector = queryVector();

        List<Long> standardIds = given()
                .contentType(ContentType.JSON)
                .queryParam("k", 10)
                .queryParam("collaborative", false)
                .body(vector)
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("hits.globalId", Long.class);

        List<Long> collaborativeIds = given()
                .contentType(ContentType.JSON)
                .queryParam("k", 10)
                .queryParam("collaborative", true)
                .body(vector)
                .when()
                .post("/search")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("hits.globalId", Long.class);

        assert standardIds.equals(collaborativeIds)
                : "Single-node results should be identical: standard=" + standardIds + " vs collaborative=" + collaborativeIds;
    }

    // --- Smoke endpoint tests ---

    @Test
    public void testSmokeStandard() {
        given()
                .queryParam("k", 5)
                .queryParam("collaborative", false)
                .when()
                .get("/search/smoke")
                .then()
                .statusCode(200)
                .body("hits", hasSize(5))
                .body("collaborative", is(false));
    }

    @Test
    public void testSmokeCollaborative() {
        given()
                .queryParam("k", 5)
                .queryParam("collaborative", true)
                .when()
                .get("/search/smoke")
                .then()
                .statusCode(200)
                .body("hits", hasSize(5))
                .body("collaborative", is(true));
    }

    @Test
    public void testSmokeDefaultsToCollaborative() {
        given()
                .queryParam("k", 5)
                .when()
                .get("/search/smoke")
                .then()
                .statusCode(200)
                .body("collaborative", is(true));
    }
}
