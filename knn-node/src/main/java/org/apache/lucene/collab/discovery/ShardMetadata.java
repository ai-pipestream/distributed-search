package org.apache.lucene.collab.discovery;

import java.io.Serializable;
import java.util.Objects;

/**
 * Metadata advertised via ScaleCube cluster membership. Used for shard/replica awareness
 * and optional collection filtering. Disseminated to all cluster members via gossip.
 */
public final class ShardMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int shardId;
    private final int replicaId;
    private final String collection;

    /**
     * @param shardId   logical shard index (0..N-1)
     * @param replicaId replica index within shard (0 = primary)
     * @param collection logical collection name, or null/empty for default
     */
    public ShardMetadata(int shardId, int replicaId, String collection) {
        this.shardId = shardId;
        this.replicaId = Math.max(0, replicaId);
        this.collection = (collection == null || collection.isBlank()) ? "" : collection.trim();
    }

    public int shardId() {
        return shardId;
    }

    public int replicaId() {
        return replicaId;
    }

    public String collection() {
        return collection == null ? "" : collection;
    }

    public boolean isPrimary() {
        return replicaId == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShardMetadata that = (ShardMetadata) o;
        return shardId == that.shardId && replicaId == that.replicaId
                && Objects.equals(collection, that.collection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shardId, replicaId, collection);
    }

    @Override
    public String toString() {
        return "ShardMetadata{shard=" + shardId + ", replica=" + replicaId
                + (collection != null && !collection.isEmpty() ? ", collection=" + collection : "")
                + "}";
    }
}
