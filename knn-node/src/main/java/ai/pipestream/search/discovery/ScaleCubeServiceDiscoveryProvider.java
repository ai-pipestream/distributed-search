package ai.pipestream.search.discovery;

import io.smallrye.stork.api.config.ServiceConfig;
import io.smallrye.stork.api.config.ServiceDiscoveryAttribute;
import io.smallrye.stork.api.config.ServiceDiscoveryType;
import io.smallrye.stork.spi.ServiceDiscoveryProvider;
import io.smallrye.stork.spi.StorkInfrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Stork ServiceDiscoveryProvider for ScaleCube cluster membership.
 * Discovers KNN peer nodes via gossip protocol.
 * <p>
 * Configuration (e.g. in application.properties):
 * <pre>
 * quarkus.stork.knn-peers.service-discovery.type=scalecube
 * quarkus.stork.knn-peers.service-discovery.grpc-port=48100
 * </pre>
 *
 * @see <a href="https://smallrye.io/smallrye-stork/latest/service-discovery/custom-service-discovery/">SmallRye Stork Custom Discovery</a>
 */
@ServiceDiscoveryType("scalecube")
@ServiceDiscoveryAttribute(name = "grpc-port", description = "gRPC port of KNN nodes", required = true)
@ServiceDiscoveryAttribute(name = "collection", description = "Filter to members of this collection only (optional)", required = false)
@ServiceDiscoveryAttribute(name = "dedupe-replicas", description = "Return one instance per shard (primary replica). Default: true", required = false)
@ApplicationScoped
public class ScaleCubeServiceDiscoveryProvider
        implements ServiceDiscoveryProvider<ScalecubeConfiguration> {

    @Inject
    ScaleCubeClusterBootstrap bootstrap;

    @Override
    public ScaleCubeServiceDiscovery createServiceDiscovery(
            ScalecubeConfiguration config,
            String serviceName,
            ServiceConfig serviceConfig,
            StorkInfrastructure storkInfrastructure) {
        return new ScaleCubeServiceDiscovery(bootstrap, config);
    }
}
