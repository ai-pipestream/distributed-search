package org.apache.lucene.collab.discovery;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.ClusterConfig;
import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.membership.MembershipConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bootstraps ScaleCube cluster on application startup. Joins the gossip cluster
 * using configured seeds. Publishes shard/replica/collection metadata via membership.
 * Exposes the cluster for the Stork discovery provider.
 */
@ApplicationScoped
public class ScaleCubeClusterBootstrap {

    private static final Logger LOG = Logger.getLogger(ScaleCubeClusterBootstrap.class);

    private Cluster cluster;
    private volatile boolean enabled = false;

    void onStart(@Observes io.quarkus.runtime.StartupEvent ev) {
        String seedsConfig = ConfigProvider.getConfig()
                .getOptionalValue("knn.scalecube.seeds", String.class)
                .orElse(null);

        if (seedsConfig == null || seedsConfig.isBlank()) {
            LOG.info("ScaleCube disabled: knn.scalecube.seeds not configured");
            return;
        }

        List<String> seeds = parseSeeds(seedsConfig);
        if (seeds.isEmpty()) {
            LOG.warn("ScaleCube disabled: no valid seeds in knn.scalecube.seeds");
            return;
        }

        try {
            ClusterConfig config = ClusterConfig.defaultLanConfig()
                    .membership(m -> m.seedMembers(seeds));
            cluster = new ClusterImpl(config).startAwait();
            enabled = true;

            int shardId = ConfigProvider.getConfig()
                    .getOptionalValue("knn.shard.id", Integer.class).orElse(0);
            int replicaId = ConfigProvider.getConfig()
                    .getOptionalValue("knn.replica.id", Integer.class).orElse(0);
            String collection = ConfigProvider.getConfig()
                    .getOptionalValue("knn.collection", String.class).orElse("");

            ShardMetadata metadata = new ShardMetadata(shardId, replicaId, collection);
            cluster.updateMetadata(metadata).block();
            LOG.infof("ScaleCube cluster joined. Members: %d (including self). Address: %s. Metadata: %s",
                    cluster.members().size(), cluster.address(), metadata);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to join ScaleCube cluster: %s", e.getMessage());
        }
    }

    void onStop(@Observes io.quarkus.runtime.ShutdownEvent ev) {
        if (cluster != null) {
            try {
                cluster.shutdown();
            } catch (Exception e) {
                LOG.warnf("Error shutting down ScaleCube: %s", e.getMessage());
            }
        }
    }

    public Cluster getCluster() {
        return cluster;
    }

    public boolean isEnabled() {
        return enabled && cluster != null;
    }

    private static List<String> parseSeeds(String config) {
        if (config == null || config.isBlank()) return Collections.emptyList();
        return Arrays.stream(config.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> s.contains(":"))
                .collect(Collectors.toList());
    }
}
