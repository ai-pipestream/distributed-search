package ai.pipestream.search.index;

import ai.pipestream.index.v1.IndexDocumentRequest;
import ai.pipestream.index.v1.IndexDocumentResponse;
import ai.pipestream.index.v1.MutinyIndexServiceGrpc;
import ai.pipestream.search.discovery.ScaleCubeClusterBootstrap;
import ai.pipestream.search.discovery.ShardMetadata;
import ai.pipestream.search.grpc.GrpcChannelCache;
import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.Member;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.function.Function;

/**
 * Routes index requests to the correct shard owner node.
 * In single-node mode or when the target shard is local, indexes locally.
 * Otherwise forwards via gRPC to the remote node that owns the shard.
 */
@ApplicationScoped
public class ShardRouter {

    private static final Logger LOG = Logger.getLogger(ShardRouter.class);

    @Inject
    ScaleCubeClusterBootstrap clusterBootstrap;

    @Inject
    GrpcChannelCache channelCache;

    @Inject
    CollectionManager collections;

    @ConfigProperty(name = "knn.shard.id", defaultValue = "0")
    int localShardId;

    @ConfigProperty(name = "knn.single.node", defaultValue = "false")
    boolean singleNode;

    /**
     * Route an index request to the correct shard owner.
     * If the target shard is local or no remote owner is found, calls localIndexFn.
     * Otherwise forwards via gRPC to the remote owner.
     */
    public Uni<IndexDocumentResponse> routeAndIndex(
            IndexDocumentRequest request,
            Function<IndexDocumentRequest, IndexDocumentResponse> localIndexFn) {

        CollectionConfig config = collections.getConfig(request.getCollection());
        if (config == null) {
            return Uni.createFrom().item(IndexDocumentResponse.newBuilder()
                    .setSuccess(false)
                    .setDocId(request.getDocId())
                    .setError("Collection not found: " + request.getCollection())
                    .build());
        }

        int targetShard = collections.routeToShard(request.getDocId(), config.numShards());

        // Single-node mode or target shard is local → index locally
        if (singleNode || targetShard == localShardId || !clusterBootstrap.isEnabled()) {
            return Uni.createFrom().item(() -> localIndexFn.apply(request))
                    .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
        }

        // Try to find remote owner for this shard
        Optional<ShardOwner> owner = findOwner(targetShard, request.getCollection());
        if (owner.isEmpty()) {
            LOG.warnf("No remote owner found for shard %d of collection '%s' — indexing locally",
                    targetShard, request.getCollection());
            return Uni.createFrom().item(() -> localIndexFn.apply(request))
                    .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
        }

        // Forward to remote owner
        ShardOwner remote = owner.get();
        LOG.debugf("Forwarding doc %s to shard %d owner at %s:%d",
                request.getDocId(), targetShard, remote.host, remote.port);

        MutinyIndexServiceGrpc.MutinyIndexServiceStub stub =
                MutinyIndexServiceGrpc.newMutinyStub(channelCache.getOrCreate(remote.host, remote.port));
        return stub.indexDocument(request);
    }

    /**
     * Find the cluster member that owns the given shard for a collection.
     */
    Optional<ShardOwner> findOwner(int shardId, String collection) {
        if (!clusterBootstrap.isEnabled()) {
            return Optional.empty();
        }

        Cluster cluster = clusterBootstrap.getCluster();
        for (Member member : cluster.members()) {
            // Skip self
            if (cluster.member().id().equals(member.id())) {
                continue;
            }

            Optional<ShardMetadata> metaOpt = cluster.metadata(member);
            if (metaOpt.isPresent()) {
                ShardMetadata meta = metaOpt.get();
                if (meta.shardId() == shardId && meta.isPrimary()) {
                    String host = parseHost(member.address());
                    int port = meta.grpcPort() > 0 ? meta.grpcPort() : parsePort(member.address());
                    return Optional.of(new ShardOwner(host, port, shardId));
                }
            }
        }

        return Optional.empty();
    }

    private static String parseHost(String address) {
        if (address == null || !address.contains(":")) return "localhost";
        return address.substring(0, address.indexOf(':'));
    }

    private static int parsePort(String address) {
        if (address == null || !address.contains(":")) return 0;
        try {
            return Integer.parseInt(address.substring(address.indexOf(':') + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    record ShardOwner(String host, int port, int shardId) {}
}
