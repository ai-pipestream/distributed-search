package ai.pipestream.search.index;

import ai.pipestream.index.v1.DeleteDocumentRequest;
import ai.pipestream.index.v1.DeleteDocumentResponse;
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
     * Where a document belongs: this node, a specific remote owner, or an
     * owned-by-nobody shard (cluster mode, no primary advertising the shard).
     * Protocol-agnostic — v1alpha1 and legacy services both route through it.
     */
    public record Route(int shardId, Target target, String host, int port) {
        public enum Target { LOCAL, REMOTE, NO_OWNER }

        static Route local(int shardId) {
            return new Route(shardId, Target.LOCAL, "", 0);
        }

        static Route remote(int shardId, String host, int port) {
            return new Route(shardId, Target.REMOTE, host, port);
        }

        static Route noOwner(int shardId) {
            return new Route(shardId, Target.NO_OWNER, "", 0);
        }
    }

    /**
     * True when every shard of every collection is served by this node
     * (single-node mode or cluster membership disabled). Multi-shard block
     * fan-out currently requires this.
     */
    public boolean allShardsLocal() {
        return singleNode || !clusterBootstrap.isEnabled();
    }

    /**
     * Decides placement for one document id. LOCAL in single-node mode, when
     * the cluster is disabled, or when this node owns the target shard;
     * REMOTE when a primary owner is advertising the shard; NO_OWNER otherwise
     * (callers must reject the write rather than indexing into a shard this
     * node does not own).
     */
    public Route route(String collection, int numShards, String docId) {
        int targetShard = collections.routeToShard(docId, numShards);
        if (singleNode || targetShard == localShardId || !clusterBootstrap.isEnabled()) {
            return Route.local(targetShard);
        }
        return findOwner(targetShard, collection)
                .map(owner -> Route.remote(targetShard, owner.host(), owner.port()))
                .orElseGet(() -> Route.noOwner(targetShard));
    }

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
            LOG.warnf("No remote owner found for shard %d of collection '%s'",
                    targetShard, request.getCollection());
            return Uni.createFrom().item(IndexDocumentResponse.newBuilder()
                    .setSuccess(false)
                    .setDocId(request.getDocId())
                    .setShardId(targetShard)
                    .setError("No primary owner is available for shard " + targetShard)
                    .build());
        }

        // Forward to remote owner
        ShardOwner remote = owner.get();
        LOG.debugf("Forwarding doc %s to shard %d owner at %s:%d",
                request.getDocId(), targetShard, remote.host, remote.port);

        MutinyIndexServiceGrpc.MutinyIndexServiceStub stub =
                MutinyIndexServiceGrpc.newMutinyStub(channelCache.getOrCreate(remote.host, remote.port));
        return stub.indexDocument(request);
    }

    /** Route a delete through the same primary-owner decision as indexing. */
    public Uni<DeleteDocumentResponse> routeAndDelete(
            DeleteDocumentRequest request,
            Function<DeleteDocumentRequest, DeleteDocumentResponse> localDeleteFn) {
        CollectionConfig config = collections.getConfig(request.getCollection());
        if (config == null) {
            return Uni.createFrom().item(DeleteDocumentResponse.newBuilder().setFound(false).build());
        }

        int targetShard = collections.routeToShard(request.getDocId(), config.numShards());
        if (singleNode || targetShard == localShardId || !clusterBootstrap.isEnabled()) {
            return Uni.createFrom().item(() -> localDeleteFn.apply(request))
                    .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
        }

        Optional<ShardOwner> owner = findOwner(targetShard, request.getCollection());
        if (owner.isEmpty()) {
            LOG.warnf("No remote owner found for delete from shard %d of collection '%s'",
                    targetShard, request.getCollection());
            return Uni.createFrom().item(DeleteDocumentResponse.newBuilder().setFound(false).build());
        }

        ShardOwner remote = owner.get();
        return MutinyIndexServiceGrpc.newMutinyStub(channelCache.getOrCreate(remote.host, remote.port))
                .deleteDocument(request);
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
                if (meta.shardId() == shardId
                        && meta.isPrimary()
                        && meta.collection().equals(collection)) {
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
