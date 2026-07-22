package ai.pipestream.search.node;

import ai.pipestream.search.query.DocumentTopDocs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges per-shard document-centric results by parent doc id under MAX
 * scoring. Pure: no Lucene, no gRPC, no CDI.
 *
 * <p>score(P) = max over shards of (max over that shard's chunks of P). Max
 * is associative and commutative over any partition of the chunk set, so
 * placement is a free choice, never a correctness compromise. Chunk lists
 * CONCATENATE — chunk sets are disjoint across shards by construction.
 * Document ties break on docId ascending so replicas agree.
 */
public final class DocumentMerger {

    private DocumentMerger() {
    }

    /** One shard's view of one parent. */
    public record ShardDocument(String docId, int shardId, float score,
                                List<DocumentTopDocs.ChunkScore> chunks) {}

    /** One chunk with its owning shard, post-merge. */
    public record ShardChunk(DocumentTopDocs.ChunkScore chunk, int shardId) {}

    /** The merged parent: score = global max, shardId = the best chunk's shard. */
    public record MergedDocument(String docId, float score, int shardId,
                                 List<ShardChunk> chunks) {}

    public static List<MergedDocument> merge(List<ShardDocument> perShard, int d, int chunksPerHit) {
        Map<String, List<ShardDocument>> byDoc = new LinkedHashMap<>();
        for (ShardDocument document : perShard) {
            byDoc.computeIfAbsent(document.docId(), k -> new ArrayList<>()).add(document);
        }

        List<MergedDocument> merged = new ArrayList<>(byDoc.size());
        for (Map.Entry<String, List<ShardDocument>> entry : byDoc.entrySet()) {
            float best = Float.NEGATIVE_INFINITY;
            int bestShard = -1;
            List<ShardChunk> chunks = new ArrayList<>();
            for (ShardDocument document : entry.getValue()) {
                if (document.score() > best) {
                    best = document.score();
                    bestShard = document.shardId();
                }
                for (DocumentTopDocs.ChunkScore chunk : document.chunks()) {
                    chunks.add(new ShardChunk(chunk, document.shardId()));
                }
            }
            chunks.sort(Comparator.comparingDouble((ShardChunk c) -> c.chunk().score()).reversed()
                    .thenComparingInt(c -> c.chunk().ordinal())
                    .thenComparingInt(ShardChunk::shardId));
            if (chunks.size() > chunksPerHit) {
                chunks = new ArrayList<>(chunks.subList(0, chunksPerHit));
            }
            merged.add(new MergedDocument(entry.getKey(), best, bestShard, chunks));
        }

        merged.sort(Comparator.comparingDouble(MergedDocument::score).reversed()
                .thenComparing(MergedDocument::docId));
        if (merged.size() > d) {
            merged = new ArrayList<>(merged.subList(0, d));
        }
        return merged;
    }
}
