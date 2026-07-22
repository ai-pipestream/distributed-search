package ai.pipestream.search.node;

import ai.pipestream.search.v1alpha1.*;
import com.google.protobuf.Descriptors;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P5 integration proof: a parent split across two shards by balanced
 * similarity placement returns ONCE with its global max score, chunks from
 * both shards, and generation replacement purges every shard it touched.
 */
@QuarkusTest
@QuarkusTestResource(value = KnnNodeTest.IndexResource.class, restrictToAnnotatedClass = true)
public class V1Alpha1MultiShardBlockTest {

    @Inject
    @io.quarkus.grpc.GrpcService
    CollectionAdminService adminService;

    @Inject
    @io.quarkus.grpc.GrpcService
    IndexService indexService;

    @Inject
    @io.quarkus.grpc.GrpcService
    SearchService searchService;

    private static final float[][] BLOB_A = {
            {1f, 0.02f, 0f, 0f}, {1f, 0.04f, 0f, 0f}, {1f, 0.06f, 0f, 0f}};
    private static final float[][] BLOB_B = {
            {0f, 0.02f, 1f, 0f}, {0f, 0.04f, 1f, 0f}, {0f, 0.06f, 1f, 0f}};

    private static Vector vec(float[] values) {
        Vector.Builder v = Vector.newBuilder();
        for (float f : values) {
            v.addValues(f);
        }
        return v.build();
    }

    private ParentAck sendParent(IndexParentDocument parent) {
        return indexService.bulkIndex(Multi.createFrom().item(
                        BulkIndexRequest.newBuilder().setParentDocument(parent).build()))
                .collect().asList().await().indefinitely().stream()
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.PARENT_ACK)
                .findFirst().orElseThrow().getParentAck();
    }

    @Test
    public void balancedPlacementSplitsMergesAndReplaces() throws Exception {
        String collection = "multishard-blocks";
        adminService.createCollection(CreateCollectionRequest.newBuilder()
                .setName(collection).setNumShards(2)
                .setSchema(CollectionSchema.newBuilder()
                        .addFields(FieldSchema.newBuilder().setName("embedding")
                                .setDenseVector(DenseVectorFieldSchema.newBuilder()
                                        .setDims(4)
                                        .setSimilarity(VectorSimilarity.VECTOR_SIMILARITY_COSINE))))
                .build()).await().indefinitely();
        adminService.registerSchema(RegisterSchemaRequest.newBuilder()
                .setCollection(collection)
                .setSource(SchemaSource.newBuilder()
                        .setDescriptorSet(DocCentricTestSchema.wireDescriptorSet())
                        .setRootMessage("t.Doc")
                        .setChunkMessage("t.DocChunk"))
                .build()).await().indefinitely();
        Descriptors.FileDescriptor file = DocCentricTestSchema.buildFile();

        // Six chunks in two well-separated blobs, interleaved by ordinal so
        // positional placement would split the blobs.
        SuppliedChunks.Builder chunks = SuppliedChunks.newBuilder();
        for (int i = 0; i < 3; i++) {
            chunks.addChunks(Chunk.newBuilder()
                    .setChunkId("a-" + i)
                    .setPayload(DocCentricTestSchema.chunkPayload(file, "blob a " + i))
                    .setVector(vec(BLOB_A[i])));
            chunks.addChunks(Chunk.newBuilder()
                    .setChunkId("b-" + i)
                    .setPayload(DocCentricTestSchema.chunkPayload(file, "blob b " + i))
                    .setVector(vec(BLOB_B[i])));
        }

        ParentAck ack = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(1)
                .setCollection(collection)
                .setDocId("split-doc")
                .setPayload(DocCentricTestSchema.docPayload(file, "the split document"))
                .setSuppliedChunks(chunks)
                .build());

        Assertions.assertEquals(0, ack.getStatus().getCode(),
                "balanced write must succeed: " + ack.getStatus().getMessage());
        Assertions.assertEquals(6, ack.getChunkCount());
        Assertions.assertEquals(2, ack.getBlocksCount(), "the two blobs land on two shards");
        for (BlockAck block : ack.getBlocksList()) {
            Assertions.assertEquals(3, block.getChunkCount(),
                    "cap ceil(6/2)=3: each shard gets one whole blob");
        }

        // --- document-centric search: the parent returns ONCE, globally merged ---
        List<SearchResponse> responses = searchService.search(SearchRequest.newBuilder()
                .setCollection(collection)
                .setSize(10)
                .setChunksPerHit(10)
                .setQuery(Query.newBuilder().setKnn(KnnQuery.newBuilder()
                        .setField("embedding")
                        .setVector(vec(new float[]{1f, 0f, 0f, 0f}))
                        .setK(5)
                        .setDocumentCentric(true)))
                .build()).collect().asList().await().indefinitely();

        List<Hit> hits = responses.stream()
                .filter(r -> r.getFrameCase() == SearchResponse.FrameCase.HIT)
                .map(SearchResponse::getHit)
                .toList();
        Assertions.assertEquals(1, hits.size(),
                "a parent split across shards must return exactly once");
        Hit hit = hits.get(0);
        Assertions.assertEquals("split-doc", hit.getDocId());

        // Score equals the single-index baseline: max cosine over ALL chunks.
        float expected = Float.NEGATIVE_INFINITY;
        for (float[][] blob : new float[][][]{BLOB_A, BLOB_B}) {
            for (float[] v : blob) {
                expected = Math.max(expected, VectorSimilarityFunction.COSINE.compare(
                        new float[]{1f, 0f, 0f, 0f}, v));
            }
        }
        Assertions.assertEquals(expected, hit.getScore(),
                "merged score must equal max over the full chunk set");

        // Chunks arrive from BOTH shards.
        Assertions.assertEquals(6, hit.getChunksCount(), "all chunks, both shards");
        Set<Integer> chunkShards = hit.getChunksList().stream()
                .map(ChunkHit::getShardId).collect(Collectors.toSet());
        Assertions.assertEquals(2, chunkShards.size(),
                "chunks[] must carry entries from both shard_ids");
        // Blob cohesion: every a-chunk shares a shard; every b-chunk shares a shard.
        Set<Integer> blobAShards = hit.getChunksList().stream()
                .filter(c -> c.getChunkId().startsWith("a-"))
                .map(ChunkHit::getShardId).collect(Collectors.toSet());
        Assertions.assertEquals(1, blobAShards.size(), "blob A must not be split");

        Summary summary = responses.get(responses.size() - 1).getSummary();
        Assertions.assertEquals(List.of("split-doc"), summary.getTopDocIdsList());

        // --- retrieval scans placement-chosen shards ---
        GetDocumentResponse got = indexService.getDocument(GetDocumentRequest.newBuilder()
                .setCollection(collection).setDocId("split-doc").setIncludeChunks(true)
                .build()).await().indefinitely();
        Assertions.assertTrue(got.getFound());
        Assertions.assertEquals(6, got.getChunksCount(), "chunks merged across shards");
        for (int i = 0; i < 6; i++) {
            Assertions.assertEquals(i, got.getChunks(i).getOrdinal(), "global ordinal order");
        }

        // --- generation 2 purges BOTH shards ---
        ParentAck gen2 = sendParent(IndexParentDocument.newBuilder()
                .setClientSeq(2)
                .setCollection(collection)
                .setDocId("split-doc")
                .setGeneration(2)
                .setPayload(DocCentricTestSchema.docPayload(file, "rewritten"))
                .setSuppliedChunks(SuppliedChunks.newBuilder()
                        .addChunks(Chunk.newBuilder()
                                .setChunkId("only")
                                .setPayload(DocCentricTestSchema.chunkPayload(file, "only chunk"))
                                .setVector(vec(new float[]{1f, 0f, 0f, 0f}))))
                .build());
        Assertions.assertEquals(0, gen2.getStatus().getCode());
        int totalPurged = gen2.getBlocksList().stream().mapToInt(BlockAck::getPurgedDocs).sum();
        Assertions.assertEquals(8, totalPurged,
                "6 chunks + 2 stubs of generation 1 purged across both shards");

        GetDocumentResponse rewritten = indexService.getDocument(GetDocumentRequest.newBuilder()
                .setCollection(collection).setDocId("split-doc").setIncludeChunks(true)
                .build()).await().indefinitely();
        Assertions.assertEquals(1, rewritten.getChunksCount());

        // --- DeleteParentDocument removes everything everywhere ---
        DeleteParentDocumentResponse deleted = indexService.deleteParentDocument(
                        DeleteParentDocumentRequest.newBuilder()
                                .setCollection(collection).setDocId("split-doc").build())
                .await().indefinitely();
        Assertions.assertTrue(deleted.getBlocksDeleted() >= 1);
        Assertions.assertFalse(indexService.getDocument(GetDocumentRequest.newBuilder()
                        .setCollection(collection).setDocId("split-doc").build())
                .await().indefinitely().getFound());

        adminService.dropCollection(DropCollectionRequest.newBuilder().setName(collection).build())
                .await().indefinitely();
    }
}
