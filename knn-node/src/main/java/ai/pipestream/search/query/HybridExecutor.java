package ai.pipestream.search.query;

import ai.pipestream.search.index.doc.BlockJoinFields;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.VectorScorer;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.DiversifyingChildrenFloatKnnVectorQuery;
import org.apache.lucene.search.join.ToChildBlockJoinQuery;
import org.apache.lucene.util.BitSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a {@link QueryPlan} against an IndexSearcher. {@link QueryPlan.Single}
 * plans run directly; {@link QueryPlan.Hybrid} plans run each sub-plan
 * independently over the same searcher and fuse the rankings into one TopDocs.
 *
 * <p>Fusion semantics:
 * <ul>
 *   <li><b>RRF</b>: score(d) = sum over sub-queries of 1 / (k + rank(d)),
 *       ranks 1-based within each sub-query's top-{@code k} window.</li>
 *   <li><b>Linear</b>: each sub-query's scores are min-max normalized to
 *       [0, 1] within its result window (a constant-score window normalizes
 *       to 1.0), then combined as a weighted sum. Documents absent from a
 *       sub-query's window contribute 0 for that sub-query.</li>
 * </ul>
 * Score ties break by Lucene doc id ascending, so fused rankings are
 * deterministic.
 *
 * <p>The knn execution hints on the plan ({@code collaborative},
 * {@code visit_budget}) are not interpreted here; the shard execution layer
 * wires the matching collector managers.
 */
@ApplicationScoped
public class HybridExecutor {

    /**
     * Run the plan and return the top {@code k} fused (or plain) results.
     */
    public TopDocs execute(QueryPlan plan, IndexSearcher searcher, int k) throws IOException {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got " + k);
        }
        return switch (plan) {
            case QueryPlan.Single single -> searcher.search(single.query(), k);
            case QueryPlan.Hybrid hybrid -> executeHybrid(hybrid, searcher, k);
        };
    }

    /**
     * The k a hybrid sub-plan contributes to fusion. A knn clause's
     * num_candidates widens the Lucene candidate pool, but only the clause's
     * requested top-k may earn fusion credit — otherwise docs at knn ranks
     * k+1..num_candidates shift the fused ordering.
     */
    private static int fusionK(QueryPlan subPlan, int callerK) {
        if (subPlan instanceof QueryPlan.Single single && single.knnHints().size() == 1) {
            int clauseK = single.knnHints().get(0).k();
            if (clauseK > 0) {
                return Math.min(clauseK, callerK);
            }
        }
        return callerK;
    }

    private TopDocs executeHybrid(QueryPlan.Hybrid hybrid, IndexSearcher searcher, int k) throws IOException {
        List<TopDocs> results = new ArrayList<>(hybrid.subPlans().size());
        for (QueryPlan subPlan : hybrid.subPlans()) {
            results.add(execute(subPlan, searcher, fusionK(subPlan, k)));
        }
        Map<Integer, Float> fused = switch (hybrid.fusion()) {
            case QueryPlan.FusionSpec.Rrf rrf -> fuseRrf(results, rrf.k());
            case QueryPlan.FusionSpec.Linear linear -> fuseLinear(results, linear.weights());
        };
        ScoreDoc[] ranked = fused.entrySet().stream()
                .map(entry -> new ScoreDoc(entry.getKey(), entry.getValue()))
                .sorted(Comparator.<ScoreDoc>comparingDouble(scoreDoc -> scoreDoc.score).reversed()
                        .thenComparingInt(scoreDoc -> scoreDoc.doc))
                .limit(k)
                .toArray(ScoreDoc[]::new);
        return new TopDocs(new TotalHits(fused.size(), TotalHits.Relation.EQUAL_TO), ranked);
    }

    // ------------------------------------------------------------------
    // Document-centric execution
    // ------------------------------------------------------------------

    /**
     * Executes a document-centric knn clause on one shard: top-{@code d}
     * PARENT documents via the diversifying block-join query (score = best
     * child), then an exact second pass over each winning block scoring
     * EVERY chunk — including ones HNSW never visited, which is what
     * highlighting needs. The collector's heap physically holds one child
     * per parent, so per-chunk scores cannot come from the first pass.
     */
    public DocumentTopDocs executeDocumentCentric(DocumentCentricKnnQuery query,
                                                  IndexSearcher searcher,
                                                  BitSetProducer parentsFilter,
                                                  int chunksPerHit) throws IOException {
        return executeDocumentCentric(query, searcher, parentsFilter, chunksPerHit, null);
    }

    /**
     * @param collectorManager shared-floor collector manager for collaborative
     *        queries (one per query, shared across this query's shards), or
     *        null for the stock diversifying search
     */
    public DocumentTopDocs executeDocumentCentric(DocumentCentricKnnQuery query,
                                                  IndexSearcher searcher,
                                                  BitSetProducer parentsFilter,
                                                  int chunksPerHit,
                                                  org.apache.lucene.search.knn.KnnCollectorManager collectorManager)
            throws IOException {
        // A user filter compiles against parent-scope (stub) fields; the
        // block-join query filters CHILDREN, so map parent matches onto
        // their children.
        org.apache.lucene.search.Query childFilter = query.filter() == null
                ? null
                : new ToChildBlockJoinQuery(query.filter(), parentsFilter);

        org.apache.lucene.search.Query joinQuery = collectorManager == null
                ? new DiversifyingChildrenFloatKnnVectorQuery(query.field(), query.target(),
                        childFilter, query.luceneK(), parentsFilter)
                : new ai.pipestream.search.query.knn.SharedFloorParentJoinQuery(
                        query.field(), query.target(), childFilter, query.luceneK(),
                        parentsFilter, collectorManager);
        TopDocs parents = searcher.search(joinQuery, query.luceneK());

        int limit = Math.min(query.k(), parents.scoreDocs.length);
        List<DocumentTopDocs.DocumentHit> hits = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ScoreDoc parent = parents.scoreDocs[i];
            hits.add(rescoreBlock(searcher, parentsFilter, query, parent, chunksPerHit));
        }
        return new DocumentTopDocs(hits);
    }

    /** Exact pass over ONE winning block: children between the previous stub and this one. */
    private static DocumentTopDocs.DocumentHit rescoreBlock(IndexSearcher searcher,
                                                            BitSetProducer parentsFilter,
                                                            DocumentCentricKnnQuery query,
                                                            ScoreDoc best,
                                                            int chunksPerHit) throws IOException {
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        LeafReaderContext leaf = leaves.get(org.apache.lucene.index.ReaderUtil.subIndex(best.doc, leaves));
        // The diversifying query returns the best CHILD per parent, never the
        // stub. Resolve the block: the stub is the next parent bit at or
        // after the child, and the block starts right after the previous stub.
        int childLocal = best.doc - leaf.docBase;
        BitSet parentBits = parentsFilter.getBitSet(leaf);
        int stubLocal = parentBits.nextSetBit(childLocal);
        int firstChild = stubLocal == 0 ? 0 : parentBits.prevSetBit(stubLocal - 1) + 1;

        Document stubDoc = leaf.reader().storedFields().document(stubLocal);
        String docId = stubDoc.get(BlockJoinFields.DOC_ID);
        if (docId == null) {
            docId = String.valueOf(best.doc);
        }

        List<DocumentTopDocs.ChunkScore> chunks = new ArrayList<>();
        FloatVectorValues vectors = leaf.reader().getFloatVectorValues(query.field());
        if (vectors != null) {
            VectorScorer scorer = vectors.scorer(query.target());
            if (scorer != null) {
                DocIdSetIterator iterator = scorer.iterator();
                int child = iterator.advance(firstChild);
                while (child < stubLocal && child != DocIdSetIterator.NO_MORE_DOCS) {
                    float score = scorer.score();
                    Document childDoc = leaf.reader().storedFields().document(child);
                    String chunkId = childDoc.get(BlockJoinFields.CHUNK_ID);
                    Number ordinal = numeric(childDoc, BlockJoinFields.CHUNK_ORD);
                    Number start = numeric(childDoc, BlockJoinFields.CHUNK_START);
                    Number end = numeric(childDoc, BlockJoinFields.CHUNK_END);
                    String text = childDoc.get(BlockJoinFields.CHUNK_TEXT);
                    org.apache.lucene.util.BytesRef nlp =
                            childDoc.getBinaryValue(BlockJoinFields.CHUNK_NLP);
                    chunks.add(new DocumentTopDocs.ChunkScore(
                            chunkId == null ? "" : chunkId,
                            ordinal == null ? 0 : ordinal.intValue(),
                            start == null ? 0 : start.intValue(),
                            end == null ? 0 : end.intValue(),
                            score,
                            text == null ? "" : text,
                            nlp == null ? null
                                    : java.util.Arrays.copyOfRange(nlp.bytes, nlp.offset,
                                            nlp.offset + nlp.length)));
                    child = iterator.nextDoc();
                }
            }
        }
        chunks.sort(Comparator.comparingDouble(DocumentTopDocs.ChunkScore::score).reversed());
        if (chunks.size() > chunksPerHit) {
            chunks = new ArrayList<>(chunks.subList(0, chunksPerHit));
        }
        return new DocumentTopDocs.DocumentHit(docId, best.score, chunks);
    }

    private static Number numeric(Document doc, String field) {
        org.apache.lucene.index.IndexableField f = doc.getField(field);
        return f == null ? null : f.numericValue();
    }

    private static Map<Integer, Float> fuseRrf(List<TopDocs> results, int rrfK) {
        Map<Integer, Float> scores = new HashMap<>();
        for (TopDocs result : results) {
            ScoreDoc[] docs = result.scoreDocs;
            for (int rank = 1; rank <= docs.length; rank++) {
                scores.merge(docs[rank - 1].doc, 1.0f / (rrfK + rank), Float::sum);
            }
        }
        return scores;
    }

    private static Map<Integer, Float> fuseLinear(List<TopDocs> results, List<Float> weights) {
        if (weights.size() != results.size()) {
            throw new IllegalArgumentException("linear fusion has " + weights.size()
                    + " weights for " + results.size() + " sub-queries");
        }
        Map<Integer, Float> scores = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            float weight = weights.get(i);
            ScoreDoc[] docs = results.get(i).scoreDocs;
            if (docs.length == 0) {
                continue;
            }
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (ScoreDoc doc : docs) {
                min = Math.min(min, doc.score);
                max = Math.max(max, doc.score);
            }
            for (ScoreDoc doc : docs) {
                float normalized = max > min ? (doc.score - min) / (max - min) : 1.0f;
                scores.merge(doc.doc, weight * normalized, Float::sum);
            }
        }
        return scores;
    }
}
