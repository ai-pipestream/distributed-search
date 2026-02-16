package org.apache.lucene.collab.discovery;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.Member;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.api.ServiceDiscovery;
import io.smallrye.stork.api.ServiceInstance;
import io.smallrye.stork.impl.DefaultServiceInstance;
import io.smallrye.stork.utils.ServiceInstanceIds;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Stork ServiceDiscovery that returns ScaleCube cluster members as gRPC service instances.
 * Uses each member's host from ScaleCube transport address with configured gRPC port.
 * <p>
 * With shard/replica metadata: returns one instance per logical shard (dedupes replicas)
 * when {@code dedupe-replicas=true}. Optionally filters by {@code collection}.
 */
public class ScaleCubeServiceDiscovery implements ServiceDiscovery {

    private static final Logger LOG = Logger.getLogger(ScaleCubeServiceDiscovery.class);

    private final ScaleCubeClusterBootstrap bootstrap;
    private final ScalecubeConfiguration config;

    public ScaleCubeServiceDiscovery(ScaleCubeClusterBootstrap bootstrap, ScalecubeConfiguration config) {
        this.bootstrap = bootstrap;
        this.config = config;
    }

    @Override
    public Uni<List<ServiceInstance>> getServiceInstances() {
        if (!bootstrap.isEnabled()) {
            LOG.debug("ScaleCube disabled; returning empty instance list");
            return Uni.createFrom().item(Collections.emptyList());
        }

        String grpcPortStr = config.getGrpcPort();
        if (grpcPortStr == null || grpcPortStr.isBlank()) {
            LOG.warn("ScaleCube discovery: grpc-port not configured");
            return Uni.createFrom().item(Collections.emptyList());
        }

        int fallbackGrpcPort;
        try {
            fallbackGrpcPort = Integer.parseInt(grpcPortStr.trim());
        } catch (NumberFormatException e) {
            LOG.warnf("ScaleCube discovery: invalid grpc-port '%s'", grpcPortStr);
            return Uni.createFrom().item(Collections.emptyList());
        }

        String collectionFilter = config.getCollection();
        final String collectionFilterFinal = (collectionFilter != null) ? collectionFilter.trim() : null;
        boolean dedupeReplicas = !"false".equalsIgnoreCase(config.getDedupeReplicas());

        Cluster cluster = bootstrap.getCluster();
        List<Member> others = cluster.members().stream()
                .filter(m -> !isSelf(cluster, m))
                .collect(Collectors.toList());

        List<MemberWithMeta> withMeta = others.stream()
                .map(m -> toMemberWithMeta(cluster, m))
                .filter(mwm -> matchesCollection(mwm, collectionFilterFinal))
                .collect(Collectors.toList());

        List<ServiceInstance> instances;
        if (dedupeReplicas) {
            // One instance per (collection, shardId) - prefer primary (replicaId 0)
            Map<String, MemberWithMeta> bestPerShard = new ConcurrentHashMap<>();
            for (MemberWithMeta mwm : withMeta) {
                String key = mwm.meta.collection() + ":" + mwm.meta.shardId();
                bestPerShard.merge(key, mwm, (a, b) ->
                        a.meta.replicaId() <= b.meta.replicaId() ? a : b);
            }
            instances = bestPerShard.values().stream()
                    .map(mwm -> toServiceInstance(mwm.member, resolveGrpcPort(mwm, fallbackGrpcPort)))
                    .collect(Collectors.toList());
        } else {
            instances = withMeta.stream()
                    .map(mwm -> toServiceInstance(mwm.member, resolveGrpcPort(mwm, fallbackGrpcPort)))
                    .collect(Collectors.toList());
        }

        LOG.debugf("ScaleCube discovered %d instance(s) for gRPC (fallback-port=%d, dedupe=%s, collection=%s)",
                instances.size(), fallbackGrpcPort, dedupeReplicas, collectionFilterFinal);
        return Uni.createFrom().item(instances);
    }

    private boolean isSelf(Cluster cluster, Member member) {
        return cluster.member().id().equals(member.id());
    }

    private MemberWithMeta toMemberWithMeta(Cluster cluster, Member member) {
        Optional<ShardMetadata> opt = cluster.metadata(member);
        ShardMetadata meta = opt.orElse(new ShardMetadata(0, 0, ""));
        return new MemberWithMeta(member, meta);
    }

    private boolean matchesCollection(MemberWithMeta mwm, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String c = mwm.meta.collection();
        return c != null && c.equals(filter);
    }

    private ServiceInstance toServiceInstance(Member member, int grpcPort) {
        String host = parseHost(member.address());
        return new DefaultServiceInstance(ServiceInstanceIds.next(), host, grpcPort, false);
    }

    private static int resolveGrpcPort(MemberWithMeta mwm, int fallbackGrpcPort) {
        int advertised = mwm.meta.grpcPort();
        return advertised > 0 ? advertised : fallbackGrpcPort;
    }

    private static String parseHost(String address) {
        if (address == null || !address.contains(":")) return "localhost";
        return address.substring(0, address.indexOf(':'));
    }

    private static final class MemberWithMeta {
        final Member member;
        final ShardMetadata meta;

        MemberWithMeta(Member member, ShardMetadata meta) {
            this.member = member;
            this.meta = meta;
        }
    }
}
