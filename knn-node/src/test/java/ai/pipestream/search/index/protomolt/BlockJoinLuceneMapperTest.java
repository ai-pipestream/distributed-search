package ai.pipestream.search.index.protomolt;

import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.index.hints.FieldIndexHint;
import ai.pipestream.proto.index.hints.IndexFieldType;
import ai.pipestream.proto.index.hints.IndexingHintsProto;
import ai.pipestream.proto.index.spi.IndexFieldKind;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.IndexingPlanFactory;
import ai.pipestream.proto.index.spi.InferringIndexingHintSource;
import ai.pipestream.proto.index.spi.ProtoOptionsIndexingHintSource;
import ai.pipestream.proto.index.spi.ResolvedFieldHint;
import ai.pipestream.proto.index.spi.SearchEngineIndexers;
import ai.pipestream.proto.index.spi.VectorSimilarity;
import ai.pipestream.proto.mapper.MappingException;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import ai.pipestream.search.index.CollectionConfig;
import ai.pipestream.search.index.CollectionManager;
import ai.pipestream.search.index.doc.BlockJoinFields;
import ai.pipestream.search.index.doc.BlockWriter;
import ai.pipestream.search.query.DocumentCentricKnnQuery;
import ai.pipestream.search.query.DocumentTopDocs;
import ai.pipestream.search.query.HybridExecutor;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.join.CheckJoinIndex;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * P7 proofs: the ProtoMolt SPI adapter builds the same block shape the
 * v1alpha1 ingest path writes (stub last, bookkeeping on every member, no
 * vector on the stub), delegates flat mapping to ProtoLuceneMapper, registers
 * under its own engine id, and its blocks are queryable by the P3
 * document-centric executor unchanged.
 */
class BlockJoinLuceneMapperTest {

    private static final String COLLECTION = "articles";

    private static Descriptors.Descriptor article;
    private static BlockJoinLuceneMapper mapper;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void buildSchema() throws Exception {
        // pm.Article { doc_id, title, repeated pm.Passage passages }
        // pm.Passage { text, repeated float embedding }
        DescriptorProto passage = DescriptorProto.newBuilder().setName("Passage")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("text").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("embedding").setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_FLOAT)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();
        DescriptorProto articleProto = DescriptorProto.newBuilder().setName("Article")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("doc_id").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("title").setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("passages").setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".pm.Passage")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();
        Descriptors.FileDescriptor file = Descriptors.FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("article.proto").setPackage("pm").setSyntax("proto3")
                        .addMessageType(articleProto).addMessageType(passage)
                        .build(),
                new Descriptors.FileDescriptor[0]);
        article = file.findMessageTypeByName("Article");
        mapper = new BlockJoinLuceneMapper(new ProtoFieldMapperImpl(new DescriptorRegistry()));
    }

    private static ResolvedFieldHint role(IndexFieldKind kind, String role) {
        return ResolvedFieldHint.builder(kind)
                .engineParams(Map.of(BlockJoinLuceneMapper.ENGINE_ID + "."
                        + BlockJoinLuceneMapper.ROLE_PARAM, role))
                .build();
    }

    private static IndexingPlan articlePlan() {
        return new IndexingPlan("pm.Article", List.of(
                new IndexingPlan.IndexedField("doc_id", "doc_id",
                        role(IndexFieldKind.KEYWORD, BlockJoinLuceneMapper.ROLE_DOC_ID)),
                new IndexingPlan.IndexedField("title", "title",
                        ResolvedFieldHint.of(IndexFieldKind.TEXT)),
                new IndexingPlan.IndexedField("passages", "passages",
                        ResolvedFieldHint.of(IndexFieldKind.NESTED), true),
                new IndexingPlan.IndexedField("passages.text", "text",
                        ResolvedFieldHint.of(IndexFieldKind.TEXT)),
                new IndexingPlan.IndexedField("passages.embedding", "embedding",
                        ResolvedFieldHint.builder(IndexFieldKind.VECTOR)
                                .vectorDims(4).vectorSimilarity(VectorSimilarity.COSINE)
                                .build(), true)));
    }

    private static DynamicMessage article(String docId, String title, float[]... embeddings) {
        Descriptors.Descriptor passage = article.getFile().findMessageTypeByName("Passage");
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(article)
                .setField(article.findFieldByName("doc_id"), docId)
                .setField(article.findFieldByName("title"), title);
        for (int i = 0; i < embeddings.length; i++) {
            DynamicMessage.Builder child = DynamicMessage.newBuilder(passage)
                    .setField(passage.findFieldByName("text"), "passage " + i + " of " + docId);
            for (float component : embeddings[i]) {
                child.addRepeatedField(passage.findFieldByName("embedding"), component);
            }
            builder.addRepeatedField(article.findFieldByName("passages"), child.build());
        }
        return builder.build();
    }

    private static boolean hasVector(Document doc) {
        for (IndexableField field : doc.getFields()) {
            if (field instanceof KnnFloatVectorField) {
                return true;
            }
        }
        return false;
    }

    @Test
    void registersUnderItsOwnEngineIdAlongsideStockLucene() {
        var providers = SearchEngineIndexers.loadProviders();
        Assertions.assertTrue(providers.containsKey(BlockJoinLuceneMapper.ENGINE_ID),
                "the adapter must be ServiceLoader-discoverable");
        Assertions.assertTrue(providers.containsKey("lucene"),
                "the stock engine must survive on the same classpath; "
                        + "loadProviders() resolves id collisions silently");
    }

    @Test
    void buildsTheEngineBlockShapeWithDelegatedFields() throws Exception {
        DynamicMessage message = article("doc-1", "block joins in practice",
                new float[]{1, 0, 0, 0}, new float[]{0, 1, 0, 0}, new float[]{0, 0, 1, 0});

        List<Document> block = mapper.map(message, articlePlan());

        Assertions.assertEquals(4, block.size(), "3 chunk children + 1 stub");
        Document stub = block.get(3);
        Assertions.assertEquals(BlockJoinFields.PARENT_VALUE,
                stub.get(BlockJoinFields.PARENT_MARKER), "the stub must be LAST");
        Assertions.assertFalse(hasVector(stub), "the stub carries no vector");
        Assertions.assertEquals("block joins in practice", stub.get("title"),
                "flat parent fields delegate to ProtoLuceneMapper");
        Assertions.assertNull(stub.get("text"), "chunk fields must not leak onto the stub");

        for (int i = 0; i < 3; i++) {
            Document child = block.get(i);
            Assertions.assertNull(child.get(BlockJoinFields.PARENT_MARKER));
            Assertions.assertTrue(hasVector(child), "every chunk carries its vector");
            Assertions.assertEquals("passage " + i + " of doc-1", child.get("text"));
            Assertions.assertEquals("doc-1#1#" + i, child.get(BlockJoinFields.CHUNK_ID),
                    "SPI mapping uses generation 1 and the server-assigned id convention");
            Assertions.assertEquals(String.valueOf(i), child.get(BlockJoinFields.CHUNK_ORD));
            Assertions.assertNull(child.get("title"), "parent fields must not leak onto chunks");
        }
        for (Document member : block) {
            Assertions.assertEquals("doc-1", member.get(BlockJoinFields.DOC_ID),
                    "doc_id is indexed on every block member");
        }
    }

    @Test
    void adapterBlocksAreQueryableByTheDocCentricExecutor() throws Exception {
        CollectionManager manager = ai.pipestream.search.index.TestCollectionManagers.create(tempDir);
        manager.createCollection(new CollectionConfig(
                COLLECTION, 4, VectorSimilarityFunction.COSINE, 1, "",
                true, "pm.Passage", null, 0));
        BlockWriter writer = ai.pipestream.search.index.doc.TestBlockWriters.create(manager);

        writer.writeBlock(COLLECTION, 0, "doc-near", 1, mapper.map("doc-near", 1,
                article("doc-near", "near", new float[]{1, 0, 0, 0}, new float[]{0.9f, 0.1f, 0, 0}),
                articlePlan()));
        writer.writeBlock(COLLECTION, 0, "doc-far", 1, mapper.map("doc-far", 1,
                article("doc-far", "far", new float[]{0, 0, 0, 1}),
                articlePlan()));
        manager.commit(COLLECTION, 0);

        DirectoryReader reader = manager.getReader(COLLECTION, 0);
        try {
            QueryBitSetProducer parents = new QueryBitSetProducer(BlockJoinFields.PARENT_QUERY);
            CheckJoinIndex.check(reader, parents);

            DocumentTopDocs top = new HybridExecutor().executeDocumentCentric(
                    new DocumentCentricKnnQuery("embedding", new float[]{1, 0, 0, 0}, 2, 10, null),
                    new IndexSearcher(reader), parents, 10);

            Assertions.assertEquals(2, top.hits().size());
            DocumentTopDocs.DocumentHit best = top.hits().get(0);
            Assertions.assertEquals("doc-near", best.docId());
            Assertions.assertEquals(2, best.chunks().size(),
                    "the exact second pass scores every chunk in the block");
            Assertions.assertEquals("doc-near#1#0", best.chunks().get(0).chunkId(),
                    "the best chunk is the exact-match passage");
            Assertions.assertEquals("doc-far", top.hits().get(1).docId());
        } finally {
            manager.releaseReader(reader);
        }
    }

    /**
     * The first-class vocabulary end to end: BLOCK_ROLE hints in proto
     * options drive IndexingPlanFactory, whose plan drives the mapper with
     * no engine params anywhere.
     */
    @Test
    void blockRoleHintsDriveTheWholePipelineFromProtoOptions() throws Exception {
        DescriptorProtos.FieldOptions docIdHint = DescriptorProtos.FieldOptions.newBuilder()
                .setExtension(IndexingHintsProto.index, FieldIndexHint.newBuilder()
                        .setType(IndexFieldType.INDEX_FIELD_TYPE_KEYWORD)
                        .setBlockRole(ai.pipestream.proto.index.hints.BlockRole.BLOCK_ROLE_DOC_ID)
                        .build())
                .build();
        DescriptorProtos.FieldOptions chunksHint = DescriptorProtos.FieldOptions.newBuilder()
                .setExtension(IndexingHintsProto.index, FieldIndexHint.newBuilder()
                        .setType(IndexFieldType.INDEX_FIELD_TYPE_NESTED)
                        .setBlockRole(ai.pipestream.proto.index.hints.BlockRole.BLOCK_ROLE_CHUNKS)
                        .build())
                .build();
        DescriptorProtos.FieldOptions vectorHint = DescriptorProtos.FieldOptions.newBuilder()
                .setExtension(IndexingHintsProto.index, FieldIndexHint.newBuilder()
                        .setType(IndexFieldType.INDEX_FIELD_TYPE_VECTOR)
                        .setVectorDims(4)
                        .build())
                .build();
        DescriptorProto passage = DescriptorProto.newBuilder().setName("Passage")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("text").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("embedding").setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_FLOAT)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .setOptions(vectorHint))
                .build();
        DescriptorProto hinted = DescriptorProto.newBuilder().setName("HintedArticle")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("doc_id").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setOptions(docIdHint))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("title").setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("passages").setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".pm.Passage")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .setOptions(chunksHint))
                .build();
        Descriptors.FileDescriptor file = Descriptors.FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("hinted_article.proto").setPackage("pm").setSyntax("proto3")
                        .addMessageType(hinted).addMessageType(passage)
                        .build(),
                new Descriptors.FileDescriptor[0]);
        Descriptors.Descriptor descriptor = file.findMessageTypeByName("HintedArticle");

        IndexingPlan plan = new IndexingPlanFactory(
                new ProtoOptionsIndexingHintSource().orElse(new InferringIndexingHintSource()))
                .create(descriptor);

        Descriptors.Descriptor passageType = file.findMessageTypeByName("Passage");
        DynamicMessage.Builder child = DynamicMessage.newBuilder(passageType)
                .setField(passageType.findFieldByName("text"), "the only passage");
        for (float component : new float[]{0, 1, 0, 0}) {
            child.addRepeatedField(passageType.findFieldByName("embedding"), component);
        }
        DynamicMessage message = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("doc_id"), "hinted-1")
                .setField(descriptor.findFieldByName("title"), "vocabulary driven")
                .addRepeatedField(descriptor.findFieldByName("passages"), child.build())
                .build();

        List<Document> block = mapper.map(message, plan);

        Assertions.assertEquals(2, block.size());
        Document stub = block.get(1);
        Assertions.assertEquals("hinted-1", stub.get(BlockJoinFields.DOC_ID));
        Assertions.assertEquals(1, stub.getFields(BlockJoinFields.DOC_ID).length,
                "the DOC_ID-role field is consumed, not re-emitted next to the builder's");
        Assertions.assertFalse(hasVector(stub));
        Document chunk = block.get(0);
        Assertions.assertTrue(hasVector(chunk),
                "the factory-expanded chunk scope carries the hinted vector");
        Assertions.assertEquals("the only passage", chunk.get("text"));
    }

    @Test
    void rejectsPlansTheBlockContractCannotHold() {
        // A VECTOR field outside the chunk scope would put a vector on the stub.
        IndexingPlan stubVector = new IndexingPlan("pm.Article", List.of(
                new IndexingPlan.IndexedField("title", "title_vec",
                        ResolvedFieldHint.builder(IndexFieldKind.VECTOR).vectorDims(4).build()),
                new IndexingPlan.IndexedField("passages", "passages",
                        ResolvedFieldHint.of(IndexFieldKind.NESTED), true)));
        Assertions.assertThrows(MappingException.class,
                () -> mapper.map("d", 1, article("d", "t", new float[]{1, 0, 0, 0}), stubVector));

        // No repeated NESTED field: nothing to build children from.
        IndexingPlan flat = new IndexingPlan("pm.Article", List.of(
                new IndexingPlan.IndexedField("title", "title",
                        ResolvedFieldHint.of(IndexFieldKind.TEXT))));
        Assertions.assertThrows(MappingException.class,
                () -> mapper.map("d", 1, article("d", "t", new float[]{1, 0, 0, 0}), flat));

        // A parent without chunks cannot form a block.
        Assertions.assertThrows(MappingException.class,
                () -> mapper.map("d", 1, article("d", "t"), articlePlan()));

        // The SPI entry point needs an identity field.
        IndexingPlan noIdentity = new IndexingPlan("pm.Article", List.of(
                new IndexingPlan.IndexedField("passages", "passages",
                        ResolvedFieldHint.of(IndexFieldKind.NESTED), true),
                new IndexingPlan.IndexedField("passages.embedding", "embedding",
                        ResolvedFieldHint.builder(IndexFieldKind.VECTOR).vectorDims(4).build(), true)));
        Assertions.assertThrows(MappingException.class,
                () -> mapper.map(article("d", "t", new float[]{1, 0, 0, 0}), noIdentity));
    }
}
