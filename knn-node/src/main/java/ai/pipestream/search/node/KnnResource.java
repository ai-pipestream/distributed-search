package ai.pipestream.search.node;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.stork.Stork;
import io.smallrye.stork.api.ServiceInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.pipestream.search.discovery.ScaleCubeClusterBootstrap;
import ai.pipestream.search.grpc.*;
import org.apache.lucene.search.CollaborativeKnnCollector;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.stream.Collectors;

@Path("/search")
public class KnnResource {

    private static final Logger LOG = Logger.getLogger(KnnResource.class);

    @Inject
    @RestClient
    DjlService djlService;

    @ConfigProperty(name = "knn.query.prefix", defaultValue = "query: ")
    String queryPrefix;

    @ConfigProperty(name = "knn.shard.id", defaultValue = "0")
    int shardId;

    @ConfigProperty(name = "knn.single.node", defaultValue = "false")
    boolean singleNode;

    @Inject
    @io.quarkus.grpc.GrpcService
    KnnNodeService localService;

    @Inject
    ScaleCubeClusterBootstrap scalecubeBootstrap;

    @Inject
    GrpcChannelCache channelCache;

    @GET
    @Path("/text")
    public Uni<SearchResult> textSearch(
            @QueryParam("q") String text,
            @QueryParam("k") @DefaultValue("10") int k,
            @QueryParam("collaborative") @DefaultValue("true") boolean collaborative,
            @QueryParam("maxPeers") @DefaultValue("-1") int maxPeers,
            @QueryParam("perShardK") @DefaultValue("-1") int perShardK) {
        final int topK = k < 1 ? 10 : k;
        String formattedQuery = queryPrefix + text;
        return djlService.getEmbeddingsRaw(List.of(formattedQuery))
                .onItem().transformToUni(raw -> {
                    List<List<Float>> embeddings = parseEmbeddings(raw);
                    if (embeddings.isEmpty()) {
                        return Uni.createFrom().failure(new IllegalStateException("No embeddings from DJL"));
                    }
                    return leaderSearch(topK, embeddings.get(0), collaborative, maxPeers, perShardK);
                });
    }

    @GET
    @Path("/compare")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ComparisonResult> compareSearch(
            @QueryParam("q") String text,
            @QueryParam("k") @DefaultValue("100") int k,
            @QueryParam("maxPeers") @DefaultValue("-1") int maxPeers,
            @QueryParam("perShardK") @DefaultValue("-1") int perShardK) {
        final int topK = k < 1 ? 100 : k;
        String formattedQuery = queryPrefix + text;
        LOG.infof("Comparison search: [%s] k=%d", formattedQuery, topK);

        return djlService.getEmbeddingsRaw(List.of(formattedQuery))
                .onItem().transformToUni(raw -> {
                    List<List<Float>> embeddings = parseEmbeddings(raw);
                    if (embeddings.isEmpty()) {
                        return Uni.createFrom().failure(new IllegalStateException("No embeddings from DJL"));
                    }
                    List<Float> vector = embeddings.get(0);
                    return leaderSearch(topK, vector, false, maxPeers, perShardK)
                            .onItem().transformToUni(stdResult -> leaderSearch(topK, vector, true, maxPeers, perShardK)
                                    .onItem().transform(collabResult ->
                                            buildComparison(stdResult, collabResult, topK)));
                });
    }

    @POST
    public Uni<SearchResult> leaderSearch(
            @QueryParam("k") int k,
            List<Float> vector,
            @QueryParam("collaborative") @DefaultValue("true") boolean collaborative,
            @QueryParam("maxPeers") @DefaultValue("-1") int maxPeers,
            @QueryParam("perShardK") @DefaultValue("-1") int perShardK) {
        
        String queryId = UUID.randomUUID().toString();
        int shardK = perShardK > 0 ? perShardK : k;
        LOG.infof("Leader: Starting Search %s (K=%d, collab=%b)", queryId, k, collaborative);

        SearchRequest request = SearchRequest.newBuilder()
                .setQueryId(queryId)
                .addAllVector(vector)
                .setK(shardK)
                .setCollaborative(collaborative)
                .build();

        // HEAP FLOOR BROADCASTER: Share the k-th best score globally
        LongAccumulator globalFloorAcc = collaborative
                ? new LongAccumulator(Long::max, Long.MIN_VALUE) : null;
        
        AtomicReference<byte[]> globalHint = new AtomicReference<>(null);
        BroadcastProcessor<CoordinateRequest> coordinatorBroadcaster = BroadcastProcessor.create();
        PriorityQueue<KnnNodeService.SearchHit> topKHeap = new PriorityQueue<>(k, Comparator.comparing(h -> h.score));

        List<Multi<SearchResponse>> allStreams = new ArrayList<>();

        // 1. Local shard
        allStreams.add(localService.search(request)
                .onItem().invoke(hit -> {
                    if (hit.getGlobalId() < Long.MAX_VALUE - 100 && hit.getGlobalId() != 0) {
                        processHit(queryId, hit, k, topKHeap, globalFloorAcc, globalHint, coordinatorBroadcaster);
                    }
                }));

        // 2. Peer shards
        if (!singleNode && scalecubeBootstrap.isEnabled()) {
            return Stork.getInstance().getService("knn-peers").getInstances()
                    .onItem().transformToUni(instances -> {
                        List<ServiceInstance> selectedPeers = selectPeers(instances, maxPeers);
                        for (ServiceInstance si : selectedPeers) {
                            allStreams.add(callPeerStreaming(si.getHost(), si.getPort(), k, request, topKHeap, globalFloorAcc, globalHint, coordinatorBroadcaster));
                        }
                        return aggregateFirehoses(allStreams, topKHeap, k, queryId, globalFloorAcc);
                    });
        }

        return aggregateFirehoses(allStreams, topKHeap, k, queryId, globalFloorAcc);
    }

    private void processHit(String qid, SearchResponse hit, int k, 
                          PriorityQueue<KnnNodeService.SearchHit> heap,
                          LongAccumulator floorAcc, AtomicReference<byte[]> globalHint,
                          BroadcastProcessor<CoordinateRequest> broadcaster) {
        
        KnnNodeService.SearchHit sh = new KnnNodeService.SearchHit(hit.getGlobalId(), hit.getScore(), hit.getChunk());
        
        synchronized (heap) {
            heap.offer(sh);
            if (heap.size() > k) {
                heap.poll();
            }
            
            // If the heap is full, the head of the heap is our GLOBAL FLOOR
            if (heap.size() == k && floorAcc != null) {
                float currentFloor = heap.peek().score;
                long encoded = CollaborativeKnnCollector.encode(Integer.MAX_VALUE, currentFloor);
                long old = floorAcc.get();
                floorAcc.accumulate(encoded);
                
                if (floorAcc.get() > old) {
                    broadcaster.onNext(CoordinateRequest.newBuilder()
                            .setQueryId(qid)
                            .setMinScore(currentFloor)
                            .build());
                }
            }
        }
    }

    private Multi<SearchResponse> callPeerStreaming(
            String host, int port, int k, SearchRequest request,
            PriorityQueue<KnnNodeService.SearchHit> heap,
            LongAccumulator floorAcc, AtomicReference<byte[]> globalHint, 
            BroadcastProcessor<CoordinateRequest> broadcaster) {

        MutinyKnnNodeServiceGrpc.MutinyKnnNodeServiceStub peer = MutinyKnnNodeServiceGrpc.newMutinyStub(
                channelCache.getOrCreate(host, port));

        Multi<CoordinateRequest> outboundCoordination = broadcaster
                .onOverflow().drop();

        peer.coordinate(outboundCoordination)
                .subscribe().with(resp -> {
                    // Peers can also contribute to global floor if they find better hits
                    // For now, we trust the Leader's heap as the source of truth for the floor
                }, err -> {});

        return peer.search(request)
                .onItem().invoke(hit -> {
                    if (hit.getGlobalId() < Long.MAX_VALUE - 100 && hit.getGlobalId() != 0) {
                        processHit(request.getQueryId(), hit, k, heap, floorAcc, globalHint, broadcaster);
                    }
                });
    }

    private Uni<SearchResult> aggregateFirehoses(List<Multi<SearchResponse>> streams, 
                                               PriorityQueue<KnnNodeService.SearchHit> topKHeap,
                                               int k, String qid, LongAccumulator floorAcc) {
        long startTime = System.currentTimeMillis();
        AtomicLong totalVisited = new AtomicLong(0);
        AtomicInteger finishedShards = new AtomicInteger(0);
        int expectedShards = streams.size();

        return Multi.createBy().merging().streams(streams)
                .onItem().invoke(hit -> {
                    if (hit.getGlobalId() > Long.MAX_VALUE - 100) {
                        totalVisited.addAndGet(hit.getNodesVisited());
                        finishedShards.incrementAndGet();
                    }
                })
                .collect().last() 
                .ifNoItem().after(Duration.ofSeconds(30)).fail()
                .onItem().transform(ignored -> {
                    List<KnnNodeService.SearchHit> sortedResults = new ArrayList<>(topKHeap);
                    sortedResults.sort((a, b) -> Float.compare(b.score, a.score));
                    LOG.infof("Query %s COMPLETE. Hits=%d, Shards=%d, Visited=%d", 
                        qid, sortedResults.size(), finishedShards.get(), totalVisited.get());
                    return new SearchResult(sortedResults, totalVisited.get(), System.currentTimeMillis() - startTime, floorAcc != null);
                });
    }

    private ComparisonResult buildComparison(SearchResult standard, SearchResult collaborative, int k) {
        Set<Long> stdIds = standard.hits.stream().map(h -> h.globalId).collect(Collectors.toSet());
        Set<Long> collabIds = collaborative.hits.stream().map(h -> h.globalId).collect(Collectors.toSet());

        Set<Long> intersection = new HashSet<>(stdIds);
        intersection.retainAll(collabIds);
        Set<Long> union = new HashSet<>(stdIds);
        union.addAll(collabIds);

        double recallOverlap = union.isEmpty() ? 1.0 : (double) intersection.size() / union.size();
        double visitReduction = standard.totalVisited == 0 ? 0.0
                : 1.0 - ((double) collaborative.totalVisited / standard.totalVisited);

        return new ComparisonResult(standard, collaborative, visitReduction, recallOverlap);
    }

    private static List<List<Float>> parseEmbeddings(String raw) {
        try {
            return new ObjectMapper().readValue(raw, new TypeReference<List<List<Float>>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse DJL response", e);
        }
    }

    private static List<ServiceInstance> selectPeers(List<ServiceInstance> discovered, int maxPeers) {
        if (discovered == null || discovered.isEmpty()) return List.of();
        List<ServiceInstance> sorted = discovered.stream()
                .sorted((a, b) -> (a.getHost() + a.getPort()).compareTo(b.getHost() + b.getPort()))
                .toList();
        if (maxPeers < 0 || maxPeers >= sorted.size()) return sorted;
        return sorted.subList(0, maxPeers);
    }

    public static class SearchResult {
        public List<KnnNodeService.SearchHit> hits;
        public long totalVisited;
        public long searchTimeMs;
        public boolean collaborative;
        public SearchResult() {}
        public SearchResult(List<KnnNodeService.SearchHit> hits, long totalVisited, long searchTimeMs, boolean collaborative) {
            this.hits = hits;
            this.totalVisited = totalVisited;
            this.searchTimeMs = searchTimeMs;
            this.collaborative = collaborative;
        }
    }

    public static class ComparisonResult {
        public SearchResult standard;
        public SearchResult collaborative;
        public double visitReduction;
        public double recallOverlap;
        public ComparisonResult() {}
        public ComparisonResult(SearchResult standard, SearchResult collaborative,
                                double visitReduction, double recallOverlap) {
            this.standard = standard;
            this.collaborative = collaborative;
            this.visitReduction = visitReduction;
            this.recallOverlap = recallOverlap;
        }
    }
}
