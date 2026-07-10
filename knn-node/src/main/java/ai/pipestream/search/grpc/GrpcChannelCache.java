package ai.pipestream.search.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches gRPC ManagedChannels per (host, port) for reuse across streaming calls.
 * Channels are long-lived; creating one per request and shutting it down is wasteful.
 */
@ApplicationScoped
public class GrpcChannelCache {

    private static final Logger LOG = Logger.getLogger(GrpcChannelCache.class);

    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    /** Plaintext is for explicitly configured local development only. */
    @ConfigProperty(name = "knn.grpc.plaintext", defaultValue = "false")
    boolean plaintext;

    /**
     * Returns a cached channel for the given host and port, creating one if absent.
     * Channels are reused for all subsequent calls to the same endpoint.
     */
    public ManagedChannel getOrCreate(String host, int port) {
        String key = host + ":" + port;
        return channels.computeIfAbsent(key, k -> {
            LOG.debugf("Creating gRPC channel for %s", k);
            ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
            return (plaintext ? builder.usePlaintext() : builder.useTransportSecurity()).build();
        });
    }

    @PreDestroy
    void shutdown() {
        channels.forEach((key, ch) -> {
            try {
                ch.shutdown();
            } catch (Exception e) {
                LOG.warnf(e, "Error shutting down channel %s", key);
            }
        });
        channels.clear();
    }
}
