package ai.pipestream.search.node;

import ai.pipestream.search.query.DocumentTopDocs.ChunkScore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/** P5 merge-exactness proofs on constructed shard results (pure). */
class DocumentMergerTest {

    private static ChunkScore chunk(String id, int ordinal, float score) {
        return new ChunkScore(id, ordinal, 0, 0, score, "");
    }

    @Test
    void mergeTakesMaxScoreAndConcatenatesChunks() {
        List<DocumentMerger.MergedDocument> merged = DocumentMerger.merge(List.of(
                new DocumentMerger.ShardDocument("doc-a", 0, 0.9f,
                        List.of(chunk("a-0", 0, 0.9f), chunk("a-2", 2, 0.4f))),
                new DocumentMerger.ShardDocument("doc-a", 1, 0.7f,
                        List.of(chunk("a-1", 1, 0.7f))),
                new DocumentMerger.ShardDocument("doc-b", 1, 0.8f,
                        List.of(chunk("b-0", 0, 0.8f)))
        ), 10, 10);

        Assertions.assertEquals(2, merged.size(), "doc-a must return ONCE");
        DocumentMerger.MergedDocument docA = merged.get(0);
        Assertions.assertEquals("doc-a", docA.docId());
        Assertions.assertEquals(0.9f, docA.score(), "score = max over shards");
        Assertions.assertEquals(0, docA.shardId(), "shard of the best chunk");
        Assertions.assertEquals(List.of("a-0", "a-1", "a-2"),
                docA.chunks().stream().map(c -> c.chunk().chunkId()).toList(),
                "chunks concatenate across shards, score-descending");
        Assertions.assertEquals(List.of(0, 1, 0),
                docA.chunks().stream().map(DocumentMerger.ShardChunk::shardId).toList());

        Assertions.assertEquals("doc-b", merged.get(1).docId());
    }

    @Test
    void tiesBreakOnDocIdSoReplicasAgree() {
        List<DocumentMerger.MergedDocument> merged = DocumentMerger.merge(List.of(
                new DocumentMerger.ShardDocument("zulu", 0, 0.5f, List.of(chunk("z", 0, 0.5f))),
                new DocumentMerger.ShardDocument("alpha", 1, 0.5f, List.of(chunk("a", 0, 0.5f)))
        ), 10, 10);
        Assertions.assertEquals(List.of("alpha", "zulu"),
                merged.stream().map(DocumentMerger.MergedDocument::docId).toList());
    }

    @Test
    void capsDocumentsAndChunks() {
        List<DocumentMerger.MergedDocument> merged = DocumentMerger.merge(List.of(
                new DocumentMerger.ShardDocument("doc-a", 0, 0.9f,
                        List.of(chunk("a-0", 0, 0.9f), chunk("a-1", 1, 0.8f), chunk("a-2", 2, 0.7f))),
                new DocumentMerger.ShardDocument("doc-b", 0, 0.6f, List.of(chunk("b-0", 0, 0.6f))),
                new DocumentMerger.ShardDocument("doc-c", 0, 0.5f, List.of(chunk("c-0", 0, 0.5f)))
        ), 2, 2);
        Assertions.assertEquals(2, merged.size(), "capped at d documents");
        Assertions.assertEquals(2, merged.get(0).chunks().size(), "capped at chunksPerHit");
        Assertions.assertEquals("a-0", merged.get(0).chunks().get(0).chunk().chunkId());
    }
}
