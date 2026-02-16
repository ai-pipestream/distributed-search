package org.apache.lucene.collab.node;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
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
import org.apache.lucene.collab.discovery.ScaleCubeClusterBootstrap;
import org.apache.lucene.collab.grpc.*;
import org.apache.lucene.search.CollaborativeKnnCollector;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Smoke test: search with a unit vector (no DJL required).
     */
    @GET
    @Path("/smoke")
    public Uni<SearchResult> smokeSearch(
            @QueryParam("k") int k,
            @QueryParam("collaborative") @DefaultValue("true") boolean collaborative) {
        final int topK = k < 1 ? 5 : k;
        int dim = localService.getIndexVectorDimension();
        if (dim <= 0) dim = 1024;
        return leaderSearch(topK, unitVector(dim), collaborative);
    }

    @GET
    @Path("/text")
    public Uni<SearchResult> textSearch(
            @QueryParam("q") String text,
            @QueryParam("k") int k,
            @QueryParam("collaborative") @DefaultValue("true") boolean collaborative) {
        final int topK = k < 1 ? 10 : k;
        String formattedQuery = queryPrefix + text;
        LOG.infof("Leading Distributed Search for text: [%s] collaborative=%b", formattedQuery, collaborative);

        return djlService.getEmbeddingsRaw(List.of(formattedQuery))
                .onItem().transformToUni(raw -> {
                    List<List<Float>> embeddings = parseEmbeddings(raw);
                    if (embeddings.isEmpty()) {
                        return Uni.createFrom().failure(new IllegalStateException("No embeddings from DJL"));
                    }
                    return leaderSearch(topK, embeddings.get(0), collaborative);
                });
    }

    /**
     * Comparison endpoint: runs the same query in both standard and collaborative mode,
     * returns metrics for both.
     */
    @GET
    @Path("/compare")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ComparisonResult> compareSearch(@QueryParam("q") String text, @QueryParam("k") int k) {
        final int topK = k < 1 ? 100 : k;
        String formattedQuery = queryPrefix + text;
        LOG.infof("Comparison search for: [%s] k=%d", formattedQuery, topK);

        return djlService.getEmbeddingsRaw(List.of(formattedQuery))
                .onItem().transformToUni(raw -> {
                    List<List<Float>> embeddings = parseEmbeddings(raw);
                    if (embeddings.isEmpty()) {
                        return Uni.createFrom().failure(new IllegalStateException("No embeddings from DJL"));
                    }
                    List<Float> vector = embeddings.get(0);
                    // Run standard then collaborative sequentially to avoid interference
                    return leaderSearch(topK, vector, false)
                            .onItem().transformToUni(stdResult -> leaderSearch(topK, vector, true)
                                    .onItem().transform(collabResult ->
                                            buildComparison(stdResult, collabResult, topK)));
                });
    }

    @POST
    public Uni<SearchResult> leaderSearch(
            @QueryParam("k") int k,
            List<Float> vector,
            @QueryParam("collaborative") @DefaultValue("true") boolean collaborative) {
        String queryId = UUID.randomUUID().toString();
        LOG.infof("Distributed Search: %s (K=%d, collaborative=%b)", queryId, k, collaborative);

        SearchRequest request = SearchRequest.newBuilder()
                .setQueryId(queryId)
                .addAllVector(vector)
                .setK(k)
                .setCollaborative(collaborative)
                .build();

        // Central accumulator for cross-shard coordination (only used when collaborative)
        LongAccumulator centralAcc = collaborative
                ? new LongAccumulator(Long::max, Long.MIN_VALUE) : null;
        AtomicBoolean searchDone = new AtomicBoolean(false);

        List<Uni<SearchResponse>> shardCalls = new ArrayList<>();

        // 1. Local search
        shardCalls.add(localService.search(request)
                .collect().first()
                .onItem().invoke(resp -> {
                    // Feed local shard's floor into central accumulator
                    if (centralAcc != null && resp.getNodesVisited() > 0) {
                        // The local search's LongAccumulator is in localSearches map;
                        // its best floor is already there. We read it via the coordinate path.
                    }
                })
                .onFailure().recoverWithItem(SearchResponse.getDefaultInstance()));

        // 2. Peer searches
        if (!singleNode && scalecubeBootstrap.isEnabled()) {
            shardCalls.add(
                Stork.getInstance().getService("knn-peers").getInstances()
                    .onItem().transformToUni(instances -> {
                        List<Uni<SearchResponse>> peerSearches = new ArrayList<>();
                        for (ServiceInstance si : instances) {
                            if (collaborative) {
                                peerSearches.add(callPeerCollaboratively(
                                        si.getHost(), si.getPort(), request, centralAcc, searchDone));
                            } else {
                                peerSearches.add(callPeerStandard(si.getHost(), si.getPort(), request));
                            }
                        }
                        if (peerSearches.isEmpty()) {
                            return Uni.createFrom().item(SearchResponse.getDefaultInstance());
                        }
                        // Merge all peer responses into one
                        return Uni.combine().all().unis(peerSearches)
                                .with(results -> {
                                    SearchResponse.Builder merged = SearchResponse.newBuilder();
                                    long totalVisited = 0;
                                    long maxTime = 0;
                                    for (Object obj : results) {
                                        SearchResponse r = (SearchResponse) obj;
                                        merged.addAllHits(r.getHitsList());
                                        totalVisited += r.getNodesVisited();
                                        maxTime = Math.max(maxTime, r.getSearchTimeMs());
                                    }
                                    return merged.setNodesVisited(totalVisited)
                                            .setSearchTimeMs(maxTime).build();
                                });
                    })
                    .onFailure().recoverWithItem(SearchResponse.getDefaultInstance())
            );
        }

        return Uni.combine().all().unis(shardCalls)
                .with(results -> {
                    searchDone.set(true); // signal coordinate tickers to stop
                    List<Hit> allHits = new ArrayList<>();
                    long totalVisited = 0;
                    long maxTime = 0;
                    for (Object obj : results) {
                        SearchResponse r = (SearchResponse) obj;
                        allHits.addAll(r.getHitsList());
                        totalVisited += r.getNodesVisited();
                        maxTime = Math.max(maxTime, r.getSearchTimeMs());
                    }
                    LOG.infof("Query %s: %d candidates from %d shards, %d total visited",
                            queryId, allHits.size(), results.size(), totalVisited);

                    List<KnnNodeService.SearchHit> topK = allHits.stream()
                            .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                            .limit(k)
                            .map(hit -> new KnnNodeService.SearchHit(
                                    hit.getGlobalId(), hit.getScore(),
                                    hit.getChunk().isEmpty() ? null : hit.getChunk(),
                                    hit.getVectorList().isEmpty() ? null : toFloatArray(hit.getVectorList())))
                            .collect(Collectors.toList());

                    return new SearchResult(topK, totalVisited, maxTime, collaborative);
                });
    }

    /**
     * Call a peer with collaborative coordination: opens a Coordinate bidi-stream
     * alongside the Search RPC to broker threshold updates.
     */
    private Uni<SearchResponse> callPeerCollaboratively(
            String host, int port, SearchRequest request,
            LongAccumulator centralAcc, AtomicBoolean done) {

        MutinyKnnNodeGrpc.MutinyKnnNodeStub peer = MutinyKnnNodeGrpc.newMutinyStub(
                channelCache.getOrCreate(host, port));

        // Outbound: tick every 10ms, send current central floor to the peer.
        // Stops when the search is done.
        Multi<ThresholdUpdate> outbound = Multi.createFrom()
                .ticks().every(Duration.ofMillis(10))
                .onOverflow().drop()
                .select().where(tick -> !done.get())
                .map(tick -> {
                    long current = centralAcc.get();
                    float floor = (current == Long.MIN_VALUE) ? 0f
                            : CollaborativeKnnCollector.toScore(current);
                    return ThresholdUpdate.newBuilder()
                            .setQueryId(request.getQueryId())
                            .setMinScore(floor)
                            .build();
                });

        // Open coordinate bidi-stream: receive peer floors, feed into central accumulator
        peer.coordinate(outbound)
                .subscribe().with(
                        peerUpdate -> centralAcc.accumulate(
                                CollaborativeKnnCollector.encode(Integer.MAX_VALUE, peerUpdate.getMinScore())),
                        err -> LOG.debugf("Coordinate stream to %s:%d ended: %s", host, port, err.getMessage())
                );

        // Start search concurrently — runs alongside the coordinate stream
        return peer.search(request)
                .collect().first()
                .onFailure().recoverWithItem(SearchResponse.getDefaultInstance());
    }

    /**
     * Standard (non-collaborative) peer call — no coordinate stream.
     */
    private Uni<SearchResponse> callPeerStandard(String host, int port, SearchRequest request) {
        MutinyKnnNodeGrpc.MutinyKnnNodeStub peer = MutinyKnnNodeGrpc.newMutinyStub(
                channelCache.getOrCreate(host, port));
        return peer.search(request)
                .collect().first()
                .onFailure().recoverWithItem(SearchResponse.getDefaultInstance());
    }

    private ComparisonResult buildComparison(SearchResult standard, SearchResult collaborative, int k) {
        // Compute recall overlap (Jaccard similarity of global_id sets)
        Set<Long> stdIds = new HashSet<>();
        for (KnnNodeService.SearchHit h : standard.hits) stdIds.add(h.globalId);
        Set<Long> collabIds = new HashSet<>();
        for (KnnNodeService.SearchHit h : collaborative.hits) collabIds.add(h.globalId);

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

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    private static List<Float> unitVector(int dim) {
        float val = 1f / (float) Math.sqrt(dim);
        return java.util.Collections.nCopies(dim, val);
    }

    // --- Response models ---

    public static class SearchResult {
        public List<KnnNodeService.SearchHit> hits;
        public long totalVisited;
        public long searchTimeMs;
        public boolean collaborative;

        public SearchResult() {}
        public SearchResult(List<KnnNodeService.SearchHit> hits, long totalVisited,
                            long searchTimeMs, boolean collaborative) {
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
