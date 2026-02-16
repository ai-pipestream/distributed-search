package org.apache.lucene.collab.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
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

    /**
     * Returns a cached channel for the given host and port, creating one if absent.
     * Channels are reused for all subsequent calls to the same endpoint.
     */
    public ManagedChannel getOrCreate(String host, int port) {
        String key = host + ":" + port;
        return channels.computeIfAbsent(key, k -> {
            LOG.debugf("Creating gRPC channel for %s", k);
            return ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
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
