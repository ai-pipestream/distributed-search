package ai.pipestream.search.node;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import ai.pipestream.search.grpc.*;
import ai.pipestream.search.index.CollectionManager;
import jakarta.inject.Inject;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.CollaborativeKnnCollector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.knn.CollaborativeKnnCollectorManager;
import org.apache.lucene.search.knn.KnnCollectorManager;
import org.apache.lucene.search.knn.KnnSearchStrategy;
import org.apache.lucene.search.knn.TopKnnCollectorManager;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.ArrayUtil;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;

@Singleton
@GrpcService
public class KnnNodeService implements ai.pipestream.search.grpc.KnnNodeService {

    private static final Logger LOG = Logger.getLogger(KnnNodeService.class);

    @Inject
    CollectionManager collectionManager;

    private final Map<String, LongAccumulator> localSearches = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> searchVisits = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<byte[]>> searchHints = new ConcurrentHashMap<>();
    private volatile int indexVectorDimension = -1;

    public static class SearchHit {
        public long globalId;
        public float score;
        public String chunk;
        public SearchHit() {}
        public SearchHit(long globalId, float score, String chunk) {
            this.globalId = globalId;
            this.score = score;
            this.chunk = chunk;
        }
    }

    private static class StreamingKnnCollector extends KnnCollector.Decorator {
        private final MultiEmitter<? super SearchResponse> emitter;
        private final int shardId;
        private final StoredFields storedFields;
        private final List<LeafReaderContext> leaves;
        private final AtomicLong visitCounter;
        private final AtomicReference<byte[]> hintRef;
        private final int originalK;
        private int emittedCount = 0;

        public StreamingKnnCollector(KnnCollector delegate, 
                                   MultiEmitter<? super SearchResponse> emitter, 
                                   int shardId, 
                                   StoredFields storedFields,
                                   List<LeafReaderContext> leaves,
                                   AtomicLong visitCounter,
                                   AtomicReference<byte[]> hintRef,
                                   int originalK) {
            super(delegate);
            this.emitter = emitter;
            this.shardId = shardId;
            this.storedFields = storedFields;
            this.leaves = leaves;
            this.visitCounter = visitCounter;
            this.hintRef = hintRef;
            this.originalK = originalK;
        }

        @Override
        public void incVisitedCount(int count) {
            super.incVisitedCount(count);
            visitCounter.addAndGet(count);
        }

        @Override
        public boolean collect(int docId, float similarity) {
            boolean collected = super.collect(docId, similarity);
            if (collected) {
                try {
                    float[] vec = getVector(leaves, docId);
                    if (vec != null) {
                        hintRef.set(TopologySketchUtil.computeBinarySignature(vec));
                    }

                    if (emittedCount < originalK) {
                        SearchResponse.Builder hitBuilder = SearchResponse.newBuilder()
                                .setGlobalId(((long) shardId << 32) | docId)
                                .setScore(similarity);
                        
                        String chunk = getChunk(storedFields, docId);
                        if (chunk != null) hitBuilder.setChunk(chunk);
                        
                        emitter.emit(hitBuilder.build());
                        emittedCount++;
                    }
                } catch (Exception e) {
                    LOG.error("Failed to emit hit", e);
                }
            }
            return collected;
        }
    }

    void onStart(@Observes StartupEvent ev) {
        String path = resolveIndexPath();
        int shardId = resolveShardId();
        LOG.infof("=== KNN Node Starting (Shard: %d) ===", shardId);
        if ("NONE".equals(path)) {
            LOG.info("No legacy index path configured. Collections-only mode.");
            return;
        }
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(Path.of(path)))) {
            indexVectorDimension = resolveIndexVectorDimension(reader);
            LOG.infof("SUCCESS: Index loaded. Contains %d documents, dim=%d.", reader.numDocs(), indexVectorDimension);
        } catch (Exception e) {
            LOG.warnf("No legacy index at %s: %s (this is OK if using collections)", path, e.getMessage());
        }
    }

    @Override
    public Multi<SearchResponse> search(SearchRequest request) {
        return Multi.createFrom().<SearchResponse>emitter(emitter -> {
            String qid = request.getQueryId();
            String path = resolveIndexPath();
            int shardId = resolveShardId();
            long startTime = System.currentTimeMillis();
            
            int internalK = request.getK() * 5;
            int visitLimit = internalK * 10; 

            LOG.infof("[DIAGNOSTIC] Shard %d starting search for %s (K=%d, internalK=%d, visitLimit=%d)", 
                shardId, qid, request.getK(), internalK, visitLimit);

            LongAccumulator acc = new LongAccumulator(Long::max, Long.MIN_VALUE);
            AtomicLong visits = new AtomicLong(0);
            AtomicReference<byte[]> hint = new AtomicReference<>(null);
            
            if (request.getCollaborative()) {
                localSearches.put(qid, acc);
                searchVisits.put(qid, visits);
                searchHints.put(qid, hint);
            }

            DirectoryReader reader = null;
            boolean ownsReader = false;
            try {
                // Open reader: from CollectionManager if collection specified, else legacy path
                String collectionName = request.getCollection();
                if (!collectionName.isEmpty()) {
                    reader = collectionManager.getReader(collectionName, shardId);
                    ownsReader = false; // CollectionManager owns it
                } else {
                    reader = DirectoryReader.open(FSDirectory.open(Path.of(path)));
                    ownsReader = true;
                }
                final DirectoryReader theReader = reader;
                IndexSearcher searcher = new IndexSearcher(theReader);
                float[] vector = new float[request.getVectorCount()];
                for (int i = 0; i < request.getVectorCount(); i++) vector[i] = request.getVector(i);

                KnnCollectorManager manager;
                if (request.getCollaborative()) {
                    manager = new CollaborativeKnnCollectorManager(internalK, acc);
                } else {
                    manager = new TopKnnCollectorManager(internalK, searcher);
                }

                KnnCollectorManager streamingManager = new KnnCollectorManager() {
                    @Override
                    public KnnCollector newCollector(int ignored, KnnSearchStrategy strategy, LeafReaderContext context) throws IOException {
                        return new StreamingKnnCollector(manager.newCollector(visitLimit, strategy, context),
                                                       emitter, shardId, theReader.storedFields(), theReader.leaves(), visits, hint, request.getK());
                    }
                };

                KnnFloatVectorQuery query = new KnnFloatVectorQuery("vector", vector, internalK) {
                    @Override
                    protected KnnCollectorManager getKnnCollectorManager(int k, IndexSearcher searcher) {
                        return streamingManager;
                    }
                };

                TopDocs td = searcher.search(query, internalK);
                
                LOG.infof("[DIAGNOSTIC] Shard %d search %s finished. Lucene reported %d hits, visits=%d", 
                    shardId, qid, td.scoreDocs.length, visits.get());

                emitter.emit(SearchResponse.newBuilder()
                        .setGlobalId(Long.MAX_VALUE - shardId)
                        .setNodesVisited(visits.get())
                        .setSearchTimeMs(System.currentTimeMillis() - startTime)
                        .build());
                
                emitter.complete();
            } catch (Exception e) {
                LOG.errorf(e, "Search failed for query %s", qid);
                emitter.fail(e);
            } finally {
                if (ownsReader) {
                    try { reader.close(); } catch (IOException ignored) {}
                }
                if (emitter.isCancelled()) {
                    LOG.warnf("[DIAGNOSTIC] Query %s was CANCELLED by gRPC before completion!", qid);
                }
                if (request.getCollaborative()) {
                    localSearches.remove(qid);
                    searchVisits.remove(qid);
                    searchHints.remove(qid);
                }
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Multi<CoordinateResponse> coordinate(Multi<CoordinateRequest> updatesFromLeader) {
        return updatesFromLeader.onItem().transform(update -> {
            String qid = update.getQueryId();
            LongAccumulator acc = localSearches.get(qid);
            AtomicLong visits = searchVisits.get(qid);
            AtomicReference<byte[]> hintRef = searchHints.get(qid);
            
            if (acc != null && Float.isFinite(update.getMinScore()) && update.getMinScore() > Float.NEGATIVE_INFINITY) {
                float oldVal = CollaborativeKnnCollector.toScore(acc.get());
                acc.accumulate(CollaborativeKnnCollector.encode(Integer.MAX_VALUE, update.getMinScore()));
                float newVal = CollaborativeKnnCollector.toScore(acc.get());
                if (newVal > oldVal) {
                    LOG.infof("[DIAGNOSTIC] Query %s updated local floor to GLOBAL value: %.6f (Visited so far: %d)", 
                        qid, newVal, visits != null ? visits.get() : -1);
                }
            }
            
            float floor = Float.NEGATIVE_INFINITY;
            if (acc != null) {
                long current = acc.get();
                if (current != Long.MIN_VALUE) {
                    floor = CollaborativeKnnCollector.toScore(current);
                }
            }
            
            CoordinateResponse.Builder resp = CoordinateResponse.newBuilder()
                    .setQueryId(qid)
                    .setMinScore(floor);
            
            if (hintRef != null && hintRef.get() != null) {
                resp.setNeighborhoodHint(com.google.protobuf.ByteString.copyFrom(hintRef.get()));
            }
            
            return resp.build();
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
}
