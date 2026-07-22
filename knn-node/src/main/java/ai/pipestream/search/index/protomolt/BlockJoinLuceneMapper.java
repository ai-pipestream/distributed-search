package ai.pipestream.search.index.protomolt;

import ai.pipestream.proto.index.lucene.ProtoLuceneMapper;
import ai.pipestream.proto.index.spi.BlockRole;
import ai.pipestream.proto.index.spi.IndexFieldKind;
import ai.pipestream.proto.index.spi.IndexingPlan;
import ai.pipestream.proto.index.spi.SearchEngineIndexer;
import ai.pipestream.proto.mapper.MappingException;
import ai.pipestream.proto.mapper.ProtoFieldMapper;
import ai.pipestream.search.index.doc.BlockJoinDocumentBuilder;
import ai.pipestream.search.index.doc.BlockJoinFields;
import com.google.protobuf.Message;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ProtoMolt {@link SearchEngineIndexer} for document-centric (block-join)
 * collections: maps one parent message into a whole Lucene block — chunk
 * children in ordinal order, parent stub LAST — instead of the single flat
 * {@link Document} the stock {@code lucene} engine produces.
 *
 * <p>All flat field mapping is delegated to {@link ProtoLuceneMapper}; this
 * class only splits the plan into parent/chunk scopes and adds the block
 * bookkeeping fields ({@code doc_id}, {@code _gen}, {@code _is_parent},
 * {@code chunk_id}, {@code _chunk_ord}) that the engine's write and query
 * paths key on.
 *
 * <p><b>Plan contract.</b> The chunk scope is the field carrying
 * {@link BlockRole#CHUNKS} (the first-class vocabulary; the
 * {@code distributed-lucene.role=chunks} engine param and a lone repeated
 * NESTED field remain as fallbacks). Fields under that path are mapped onto
 * each chunk child; everything else lands on the stub. VECTOR fields outside
 * the chunk scope are rejected: the stub must not carry a vector, or
 * block-join collectors would attribute it as a chunk. Identity roles work
 * the same way: {@link BlockRole#DOC_ID} (or {@code role=doc_id}, or a field
 * named {@code doc_id}) is consumed rather than re-emitted since the builder
 * indexes it canonically on every block member, and {@link BlockRole#CHUNK_ID}
 * (or {@code role=chunk_id}) names a per-chunk id field, defaulting to
 * {@code <doc_id>#<generation>#<ordinal>}, the same convention the v1alpha1
 * ingest path assigns.
 */
public final class BlockJoinLuceneMapper implements SearchEngineIndexer {

    public static final String ENGINE_ID = "distributed-lucene";

    /** Engine-scoped param key: {@code distributed-lucene.role = <role>}. */
    public static final String ROLE_PARAM = "role";
    public static final String ROLE_DOC_ID = "doc_id";
    public static final String ROLE_CHUNK_ID = "chunk_id";
    public static final String ROLE_CHUNKS = "chunks";

    private final ProtoFieldMapper fieldMapper;
    private final ProtoLuceneMapper delegate;

    public BlockJoinLuceneMapper(ProtoFieldMapper fieldMapper) {
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper");
        this.delegate = new ProtoLuceneMapper(fieldMapper);
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    /**
     * SPI entry point: identity comes from the plan's {@code doc_id}-role
     * field and the generation is fixed at 1 — SPI callers have no write
     * protocol carrying one. Engine ingest, which does, uses
     * {@link #map(String, long, Message, IndexingPlan)}.
     */
    @Override
    public List<Document> map(Message message, IndexingPlan plan) throws MappingException {
        Objects.requireNonNull(plan, "plan");
        Split split = split(plan);
        if (split.docIdField == null) {
            throw new MappingException(
                    "No identity field: hint one field with distributed-lucene.role=doc_id "
                            + "or name it '" + BlockJoinFields.DOC_ID + "'", plan.messageFullName());
        }
        Object raw = fieldMapper.getValue(message, split.docIdField.path());
        String docId = raw == null ? "" : String.valueOf(raw);
        if (docId.isBlank()) {
            throw new MappingException("The identity field is empty", split.docIdField.path());
        }
        return map(docId, 1L, message, plan);
    }

    /** Engine entry point: identity and generation come from the write protocol. */
    public List<Document> map(String docId, long generation, Message message, IndexingPlan plan)
            throws MappingException {
        Objects.requireNonNull(message, "message");
        Split split = split(Objects.requireNonNull(plan, "plan"));

        Object value = fieldMapper.getValue(message, split.chunkField.path());
        if (!(value instanceof List<?> chunks) || chunks.isEmpty()) {
            throw new MappingException(
                    "A document-centric block needs at least one chunk", split.chunkField.path());
        }

        List<Document> children = new ArrayList<>(chunks.size());
        for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
            if (!(chunks.get(ordinal) instanceof Message chunk)) {
                throw new MappingException(
                        "Chunk field must be a repeated message", split.chunkField.path());
            }
            Document child = delegate.map(chunk, split.childPlan);
            child.add(new StringField(BlockJoinFields.CHUNK_ID,
                    chunkId(docId, generation, ordinal, chunk, split), Field.Store.YES));
            child.add(new StoredField(BlockJoinFields.CHUNK_ORD, ordinal));
            child.add(new NumericDocValuesField(BlockJoinFields.CHUNK_ORD, ordinal));
            children.add(child);
        }

        Document stub = delegate.map(message, split.parentPlan);
        return BlockJoinDocumentBuilder.build(docId, generation, stub, children, children.size());
    }

    private String chunkId(String docId, long generation, int ordinal, Message chunk, Split split)
            throws MappingException {
        if (split.chunkIdField != null) {
            Object raw = fieldMapper.getValue(chunk, split.chunkIdField.path());
            if (raw != null && !String.valueOf(raw).isBlank()) {
                return String.valueOf(raw);
            }
        }
        return docId + "#" + generation + "#" + ordinal;
    }

    /** Plan split into chunk scope, stub scope, and the role-tagged fields. */
    private record Split(IndexingPlan.IndexedField chunkField,
                         IndexingPlan.IndexedField docIdField,
                         IndexingPlan.IndexedField chunkIdField,
                         IndexingPlan childPlan,
                         IndexingPlan parentPlan) {
    }

    private static Split split(IndexingPlan plan) throws MappingException {
        IndexingPlan.IndexedField chunkField = resolveChunkField(plan);
        String chunkPrefix = chunkField.path() + ".";

        IndexingPlan.IndexedField docIdField = null;
        IndexingPlan.IndexedField chunkIdField = null;
        List<IndexingPlan.IndexedField> childFields = new ArrayList<>();
        List<IndexingPlan.IndexedField> parentFields = new ArrayList<>();

        for (IndexingPlan.IndexedField field : plan.fields()) {
            if (field == chunkField) {
                continue;
            }
            boolean inChunkScope = field.path().startsWith(chunkPrefix);
            BlockRole blockRole = field.hint().blockRole();
            String role = field.hint().engineParams(ENGINE_ID).get(ROLE_PARAM);
            if (inChunkScope) {
                IndexingPlan.IndexedField relative = new IndexingPlan.IndexedField(
                        field.path().substring(chunkPrefix.length()),
                        field.fieldName(), field.hint(), field.repeated());
                if (blockRole == BlockRole.CHUNK_ID || ROLE_CHUNK_ID.equals(role)) {
                    // consumed as identity; BlockJoinFields.CHUNK_ID carries it
                    chunkIdField = relative;
                } else {
                    childFields.add(relative);
                }
                continue;
            }
            if (blockRole == BlockRole.DOC_ID || ROLE_DOC_ID.equals(role)
                    || (blockRole == BlockRole.UNSPECIFIED && role == null
                            && BlockJoinFields.DOC_ID.equals(field.fieldName()))) {
                // consumed as identity; the builder indexes doc_id on every member
                docIdField = field;
                continue;
            }
            if (field.type() == IndexFieldKind.VECTOR) {
                throw new MappingException(
                        "The parent stub carries no vector; VECTOR fields must live under "
                                + "the chunk field '" + chunkField.path() + "'", field.path());
            }
            parentFields.add(field);
        }

        return new Split(chunkField, docIdField, chunkIdField,
                new IndexingPlan(plan.messageFullName() + "." + chunkField.path(), childFields),
                new IndexingPlan(plan.messageFullName(), parentFields));
    }

    /**
     * The {@link BlockRole#CHUNKS} field; fallbacks for plans built without
     * the vocabulary: the {@code role=chunks} engine param, then a lone
     * repeated NESTED field.
     */
    private static IndexingPlan.IndexedField resolveChunkField(IndexingPlan plan)
            throws MappingException {
        List<IndexingPlan.IndexedField> candidates = new ArrayList<>();
        for (IndexingPlan.IndexedField field : plan.fields()) {
            if (field.hint().blockRole() == BlockRole.CHUNKS) {
                return field;
            }
            if (field.type() != IndexFieldKind.NESTED || !field.repeated()) {
                continue;
            }
            if (ROLE_CHUNKS.equals(field.hint().engineParams(ENGINE_ID).get(ROLE_PARAM))) {
                return field;
            }
            candidates.add(field);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new MappingException(candidates.isEmpty()
                ? "No chunk field: hint one repeated message field with BLOCK_ROLE_CHUNKS"
                : "Ambiguous chunk field: hint exactly one field with BLOCK_ROLE_CHUNKS",
                plan.messageFullName());
    }
}
