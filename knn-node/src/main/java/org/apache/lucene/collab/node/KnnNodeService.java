package org.apache.lucene.collab.node;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.apache.lucene.collab.grpc.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.CollaborativeKnnCollector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.knn.CollaborativeKnnCollectorManager;
import org.apache.lucene.search.knn.KnnCollectorManager;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.ArrayUtil;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;

@Singleton
@GrpcService
public class KnnNodeService implements KnnNode {

    private static final Logger LOG = Logger.getLogger(KnnNodeService.class);

    private final Map<String, LongAccumulator> localSearches = new ConcurrentHashMap<>();
    private volatile int indexVectorDimension = -1;

    public static class SearchHit {
        public long globalId;
        public float score;
        public String chunk;
        public float[] vector;
        public SearchHit() {}
        public SearchHit(long globalId, float score, String chunk, float[] vector) {
            this.globalId = globalId;
            this.score = score;
            this.chunk = chunk;
            this.vector = vector;
        }
    }

    void onStart(@Observes StartupEvent ev) {
        String path = resolveIndexPath();
        int shardId = resolveShardId();
        LOG.infof("=== KNN Node Starting (Shard: %d) ===", shardId);
        LOG.infof("Validating index at: %s", path);

        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(Path.of(path)))) {
            int numDocs = reader.numDocs();
            indexVectorDimension = resolveIndexVectorDimension(reader);
            LOG.infof("SUCCESS: Index loaded. Contains %d documents, dim=%d.", numDocs, indexVectorDimension);
            logVectorSanity(reader);
            runWarmupQuery(shardId, path);
        } catch (Exception e) {
            LOG.errorf("CRITICAL: Failed to load index at %s: %s", path, e.getMessage());
        }
    }

    public int getIndexVectorDimension() {
        return indexVectorDimension;
    }

    private void runWarmupQuery(int shardId, String path) {
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(Path.of(path)))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            int dim = indexVectorDimension > 0 ? indexVectorDimension : 1024;
            float[] vector = new float[dim];
            // Unit vector for COSINE similarity (zero vectors cause assertion errors)
            float val = 1f / (float) Math.sqrt(dim);
            java.util.Arrays.fill(vector, val);
            KnnFloatVectorQuery query = new KnnFloatVectorQuery("vector", vector, 1);
            TopDocs td = searcher.search(query, 1);
            LOG.infof("Verification query returned %d hits. Node is ready.", td.totalHits.value());
        } catch (IOException e) {
            LOG.warn("Warmup query failed (non-critical): " + e.getMessage());
        }
    }

    @Override
    public Multi<SearchResponse> search(SearchRequest request) {
        return Multi.createFrom().<SearchResponse>emitter(emitter -> {
            String qid = request.getQueryId();
            String path = resolveIndexPath();
            int shardId = resolveShardId();
            
            if ("NONE".equals(path) || path == null) {
                LOG.errorf("Index path not configured for query %s", qid);
                emitter.complete();
                return;
            }

            LongAccumulator acc = new LongAccumulator(Long::max, Long.MIN_VALUE);
            if (request.getCollaborative()) {
                localSearches.put(qid, acc);
            }

            try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(Path.of(path)))) {
                long startTime = System.currentTimeMillis();
                IndexSearcher searcher = new IndexSearcher(reader);
                float[] vector = new float[request.getVectorCount()];
                for (int i = 0; i < request.getVectorCount(); i++) vector[i] = request.getVector(i);
                int indexDim = resolveIndexVectorDimension(reader);
                if (indexDim > 0 && indexDim != vector.length) {
                    throw new IllegalArgumentException(
                        "Query vector dimension mismatch: query=" + vector.length + ", index=" + indexDim);
                }

                KnnFloatVectorQuery query;
                if (request.getCollaborative()) {
                    KnnCollectorManager manager = new CollaborativeKnnCollectorManager(request.getK(), acc);
                    query = new KnnFloatVectorQuery("vector", vector, request.getK()) {
                        @Override
                        protected KnnCollectorManager getKnnCollectorManager(int k, IndexSearcher searcher) {
                            return manager;
                        }
                    };
                } else {
                    query = new KnnFloatVectorQuery("vector", vector, request.getK());
                }

                TopDocs td = searcher.search(query, request.getK());
                long searchTimeMs = System.currentTimeMillis() - startTime;
                long nodesVisited = td.totalHits.value();

                SearchResponse.Builder response = SearchResponse.newBuilder()
                    .setNodesVisited(nodesVisited)
                    .setSearchTimeMs(searchTimeMs);
                StoredFields storedFields = reader.storedFields();
                List<LeafReaderContext> leaves = reader.leaves();
                for (ScoreDoc sd : td.scoreDocs) {
                    Hit.Builder hitBuilder = Hit.newBuilder()
                            .setGlobalId(((long) shardId << 32) | sd.doc)
                            .setScore(sd.score);
                    try {
                        String chunk = getChunk(storedFields, sd.doc);
                        if (chunk != null) hitBuilder.setChunk(chunk);
                        float[] vec = getVector(leaves, sd.doc);
                        if (vec != null) for (float v : vec) hitBuilder.addVector(v);
                    } catch (Exception e) {
                        LOG.debugf("Could not fetch chunk/vector for doc %d: %s", sd.doc, e.getMessage());
                    }
                    response.addHits(hitBuilder.build());
                }
                emitter.emit(response.build());
                emitter.complete();
            } catch (Exception e) {
                LOG.errorf(e, "Search failed for query %s", qid);
                emitter.fail(e);
            } finally {
                if (request.getCollaborative()) {
                    localSearches.remove(qid);
                }
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Multi<ThresholdUpdate> coordinate(Multi<ThresholdUpdate> updatesFromLeader) {
        return updatesFromLeader.onItem().transform(update -> {
            String qid = update.getQueryId();
            LongAccumulator acc = localSearches.get(qid);
            if (acc != null) {
                acc.accumulate(CollaborativeKnnCollector.encode(Integer.MAX_VALUE, update.getMinScore()));
            }
            float floor = 0.0f;
            if (acc != null) {
                long current = acc.get();
                if (current != Long.MIN_VALUE) {
                    floor = CollaborativeKnnCollector.toScore(current);
                }
            }
            return ThresholdUpdate.newBuilder().setQueryId(qid).setMinScore(floor).build();
        });
    }

    private String resolveIndexPath() {
        return ConfigProvider.getConfig().getOptionalValue("knn.index.path", String.class).orElse("NONE");
    }

    private int resolveShardId() {
        return ConfigProvider.getConfig().getOptionalValue("knn.shard.id", Integer.class).orElse(0);
    }

    private static String getChunk(StoredFields storedFields, int docId) throws IOException {
        var doc = storedFields.document(docId);
        var field = doc.getField("chunk");
        return field != null ? field.stringValue() : "";
    }

    private static float[] getVector(List<LeafReaderContext> leaves, int docId) throws IOException {
        for (LeafReaderContext ctx : leaves) {
            int maxDoc = ctx.reader().maxDoc();
            if (docId >= ctx.docBase && docId < ctx.docBase + maxDoc) {
                FloatVectorValues fvv = ctx.reader().getFloatVectorValues("vector");
                if (fvv == null) return null;
                int localDoc = docId - ctx.docBase;
                // Use ordinal-based lookup: find ord where ordToDoc(ord) == localDoc.
                // Iterator advance+index can be wrong for sparse ordToDoc mappings.
                for (int ord = 0; ord < fvv.size(); ord++) {
                    if (fvv.ordToDoc(ord) == localDoc) {
                        return ArrayUtil.copyOfSubArray(fvv.vectorValue(ord), 0, fvv.dimension());
                    }
                }
                return null;
            }
        }
        return null;
    }

    private static int resolveIndexVectorDimension(DirectoryReader reader) throws IOException {
        for (LeafReaderContext ctx : reader.leaves()) {
            FloatVectorValues fvv = ctx.reader().getFloatVectorValues("vector");
            if (fvv != null && fvv.size() > 0) {
                return fvv.dimension();
            }
        }
        return -1;
    }

    private void logVectorSanity(DirectoryReader reader) throws IOException {
        for (LeafReaderContext ctx : reader.leaves()) {
            FloatVectorValues fvv = ctx.reader().getFloatVectorValues("vector");
            if (fvv == null || fvv.size() == 0) {
                continue;
            }

            int dim = fvv.dimension();
            int samples = Math.min(8, fvv.size());
            int minTrailing = Integer.MAX_VALUE;
            int maxTrailing = Integer.MIN_VALUE;
            int fixedHighTrailing = 0;

            for (int ord = 0; ord < samples; ord++) {
                float[] vec = fvv.vectorValue(ord);
                int trailing = trailingZeros(vec);
                minTrailing = Math.min(minTrailing, trailing);
                maxTrailing = Math.max(maxTrailing, trailing);
                if (trailing >= dim / 2) {
                    fixedHighTrailing++;
                }
            }

            LOG.infof(
                "Vector sanity (shard=%d): dim=%d sampled=%d trailingZeros[min=%d,max=%d]",
                resolveShardId(), dim, samples, minTrailing, maxTrailing);

            if (fixedHighTrailing == samples && minTrailing == maxTrailing && minTrailing > 0) {
                LOG.warnf(
                    "Index vectors appear heavily zero-padded (trailingZeros=%d across all %d sampled vectors). "
                        + "This often indicates a model-dimension mismatch in indexing input data.",
                    minTrailing, samples);
            }
            return;
        }

        LOG.warn("Vector sanity check skipped: no vectors found in index.");
    }

    private static int trailingZeros(float[] vec) {
        int count = 0;
        for (int i = vec.length - 1; i >= 0; i--) {
            if (vec[i] == 0.0f) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }
}
