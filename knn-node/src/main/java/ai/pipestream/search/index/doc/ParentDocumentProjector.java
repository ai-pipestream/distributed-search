package ai.pipestream.search.index.doc;

import ai.pipestream.search.index.CollectionConfig;
import ai.pipestream.search.schema.CompiledField;
import ai.pipestream.search.schema.CompiledSchema;
import ai.pipestream.search.schema.SchemaStore;
import ai.pipestream.search.v1alpha1.Chunk;
import ai.pipestream.search.v1alpha1.SchemaPin;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects {@code google.protobuf.Any} payloads onto Lucene documents against
 * a collection's PINNED descriptor set. The Any's type_url CONFIRMS the
 * pinned message; it never selects one — payloads of any other type are
 * rejected, whatever their type_url claims to resolve to.
 */
@ApplicationScoped
public class ParentDocumentProjector {

    /** Payload rejected before touching the index (INVALID_ARGUMENT). */
    public static class InvalidPayloadException extends RuntimeException {
        public InvalidPayloadException(String message) {
            super(message);
        }
    }

    /** Asserted pin does not match the collection pin (FAILED_PRECONDITION). */
    public static class SchemaPinMismatchException extends RuntimeException {
        public SchemaPinMismatchException(String message) {
            super(message);
        }
    }

    @Inject
    SchemaStore schemaStore;

    /**
     * Resolves the collection's pinned schema, verifying an asserted pin when
     * one is supplied. An unset/empty pin means "the collection pin".
     */
    public SchemaStore.StoredSchema resolvePinned(String collection, SchemaPin asserted) {
        SchemaStore.StoredSchema stored = schemaStore.get(collection).orElseThrow(() ->
                new SchemaPinMismatchException(
                        "Collection '" + collection + "' has no registered proto schema"));
        if (asserted != null && !asserted.getDescriptorDigest().isEmpty()
                && !stored.matches(asserted)) {
            throw new SchemaPinMismatchException(
                    "Asserted schema pin does not match the collection pin for '" + collection
                            + "' (stale schema? re-fetch the collection descriptor)");
        }
        return stored;
    }

    /**
     * Parent stub: root-scope non-vector fields (filter metadata), plus the
     * raw payload bytes for retrieval. Identity fields (doc_id, _gen, parent
     * marker) are added by {@link BlockJoinDocumentBuilder}.
     */
    public Document projectParentStub(SchemaStore.StoredSchema schema, Any payload) {
        Document stub = new Document();
        if (hasTypeUrl(payload)) {
            Message message = unpack(schema, schema.rootMessage(), payload);
            projectFields(stub, schema.compiled(), message, false);
            stub.add(new StoredField(BlockJoinFields.PARENT_PAYLOAD, payload.toByteArray()));
        }
        return stub;
    }

    /**
     * Flat typed_document ingest: every root field including vectors, onto a
     * single (non-block) document.
     */
    public Document projectFlatDocument(SchemaStore.StoredSchema schema, Any payload) {
        Document doc = new Document();
        Message message = unpack(schema, schema.rootMessage(), payload);
        projectFields(doc, schema.compiled(), message, true);
        doc.add(new StoredField(BlockJoinFields.PARENT_PAYLOAD, payload.toByteArray()));
        return doc;
    }

    /**
     * The first string value at a dotted path of the parent payload — the
     * chunk source for mode-A (server chunking) ingest.
     */
    public java.util.Optional<String> extractText(SchemaStore.StoredSchema schema,
                                                  Any payload, String dottedPath) {
        Message message = unpack(schema, schema.rootMessage(), payload);
        for (Object value : extract(message, dottedPath)) {
            if (value instanceof String s && !s.isEmpty()) {
                return java.util.Optional.of(s);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Chunk child document: identity + vector + stored payload/offsets, plus
     * opt-in stored chunk text (mode A with store_chunk_text).
     * doc_id and _gen are added by {@link BlockJoinDocumentBuilder}.
     */
    public Document projectChunk(SchemaStore.StoredSchema schema, CollectionConfig config,
                                 Chunk chunk, int ordinal, String chunkId, String chunkText) {
        Document doc = new Document();
        doc.add(new StringField(BlockJoinFields.CHUNK_ID, chunkId, Field.Store.YES));
        if (chunkText != null && !chunkText.isEmpty()) {
            doc.add(new StoredField(BlockJoinFields.CHUNK_TEXT, chunkText));
        }
        doc.add(new StoredField(BlockJoinFields.CHUNK_ORD, ordinal));
        doc.add(new NumericDocValuesField(BlockJoinFields.CHUNK_ORD, ordinal));
        doc.add(new StoredField(BlockJoinFields.CHUNK_START, chunk.getStartOffset()));
        doc.add(new StoredField(BlockJoinFields.CHUNK_END, chunk.getEndOffset()));

        if (hasTypeUrl(chunk.getPayload())) {
            // Validates the payload against the pinned chunk message even
            // though its fields are not individually indexed yet.
            unpack(schema, schema.chunkMessage(), chunk.getPayload());
            doc.add(new StoredField(BlockJoinFields.CHUNK_PAYLOAD, chunk.getPayload().toByteArray()));
        }

        if (!chunk.hasVector()) {
            throw new InvalidPayloadException("Chunk '" + chunkId + "' has no vector");
        }
        float[] vector = new float[chunk.getVector().getValuesCount()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = chunk.getVector().getValues(i);
        }

        CompiledField vectorField = resolveVectorField(schema.compiled(), chunk.getVectorField());
        if (vectorField != null) {
            LuceneFieldEncoder.addVector(doc, vectorField, vector);
        } else {
            // No VECTOR field in the registered schema: fall back to the
            // collection-level vector config.
            LuceneFieldEncoder.validateVector("vector", vector,
                    config.vectorDimension(), config.similarity());
            doc.add(new KnnFloatVectorField("vector", vector, config.similarity()));
        }
        return doc;
    }

    /**
     * The VECTOR field a chunk targets. Empty selector + exactly one VECTOR
     * field = that field; empty selector + several = INVALID_ARGUMENT; a
     * non-empty selector must name an existing VECTOR field.
     */
    public static CompiledField resolveVectorField(CompiledSchema compiled, String selector) {
        List<CompiledField> vectors = compiled.fields().stream()
                .filter(f -> f.type() == CompiledSchema.Kind.VECTOR)
                .toList();
        if (selector != null && !selector.isEmpty()) {
            return vectors.stream()
                    .filter(f -> f.indexName().equals(selector))
                    .findFirst()
                    .orElseThrow(() -> new InvalidPayloadException(
                            "vector_field '" + selector + "' is not a VECTOR field of the schema"));
        }
        if (vectors.isEmpty()) {
            return null;
        }
        if (vectors.size() > 1) {
            throw new InvalidPayloadException("The schema has " + vectors.size()
                    + " VECTOR fields; Chunk.vector_field must select one");
        }
        return vectors.get(0);
    }

    // ------------------------------------------------------------------
    // Any unpacking and field projection
    // ------------------------------------------------------------------

    private static boolean hasTypeUrl(Any any) {
        return any != null && !any.getTypeUrl().isEmpty();
    }

    private static Message unpack(SchemaStore.StoredSchema schema, String expectedMessage, Any any) {
        if (expectedMessage == null || expectedMessage.isEmpty()) {
            throw new InvalidPayloadException("The collection pins no message of this kind");
        }
        String urlType = typeNameOf(any.getTypeUrl());
        if (!urlType.equals(expectedMessage)) {
            throw new InvalidPayloadException("Payload type_url names '" + urlType
                    + "' but the collection pins '" + expectedMessage + "'");
        }
        Descriptors.Descriptor descriptor = schema.message(expectedMessage).orElseThrow(() ->
                new InvalidPayloadException(
                        "Pinned message '" + expectedMessage + "' is missing from the descriptor set"));
        try {
            return DynamicMessage.parseFrom(descriptor, any.getValue());
        } catch (InvalidProtocolBufferException e) {
            throw new InvalidPayloadException("Payload does not parse as '"
                    + expectedMessage + "': " + e.getMessage());
        }
    }

    private static String typeNameOf(String typeUrl) {
        int slash = typeUrl.lastIndexOf('/');
        return slash < 0 ? typeUrl : typeUrl.substring(slash + 1);
    }

    private static void projectFields(Document doc, CompiledSchema compiled,
                                      Message message, boolean includeVectors) {
        for (CompiledField field : compiled.fields()) {
            if (field.joinScope() != CompiledSchema.JoinScope.ROOT) {
                continue;
            }
            if (field.type() == CompiledSchema.Kind.VECTOR) {
                if (!includeVectors) {
                    continue;
                }
                List<Object> floats = extract(message, field.sourcePath());
                if (floats.isEmpty()) {
                    continue;
                }
                float[] vector = new float[floats.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = ((Number) floats.get(i)).floatValue();
                }
                LuceneFieldEncoder.addVector(doc, field, vector);
                continue;
            }
            for (Object value : extract(message, field.sourcePath())) {
                encodeValue(doc, field, value);
            }
        }
    }

    /** Values at a dotted proto path; repeated segments flatten. */
    private static List<Object> extract(Message message, String dottedPath) {
        int dot = dottedPath.indexOf('.');
        String head = dot < 0 ? dottedPath : dottedPath.substring(0, dot);
        Descriptors.FieldDescriptor fd = message.getDescriptorForType().findFieldByName(head);
        if (fd == null) {
            return List.of();
        }
        if (dot < 0) {
            if (fd.isRepeated()) {
                int count = message.getRepeatedFieldCount(fd);
                List<Object> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    values.add(message.getRepeatedField(fd, i));
                }
                return values;
            }
            if (fd.hasPresence() && !message.hasField(fd)) {
                return List.of();
            }
            return List.of(message.getField(fd));
        }
        String rest = dottedPath.substring(dot + 1);
        if (fd.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
            return List.of();
        }
        if (fd.isRepeated()) {
            List<Object> values = new ArrayList<>();
            int count = message.getRepeatedFieldCount(fd);
            for (int i = 0; i < count; i++) {
                values.addAll(extract((Message) message.getRepeatedField(fd, i), rest));
            }
            return values;
        }
        if (fd.hasPresence() && !message.hasField(fd)) {
            return List.of();
        }
        return extract((Message) message.getField(fd), rest);
    }

    private static void encodeValue(Document doc, CompiledField field, Object value) {
        switch (value) {
            case String s -> LuceneFieldEncoder.addString(doc, field, s);
            case Integer i -> LuceneFieldEncoder.addLong(doc, field, i);
            case Long l -> LuceneFieldEncoder.addLong(doc, field, l);
            case Float f -> LuceneFieldEncoder.addDouble(doc, field, f);
            case Double d -> LuceneFieldEncoder.addDouble(doc, field, d);
            case Boolean b -> LuceneFieldEncoder.addBool(doc, field, b);
            case Descriptors.EnumValueDescriptor e -> LuceneFieldEncoder.addString(doc, field, e.getName());
            case ByteString bytes -> doc.add(new StoredField(field.indexName(), bytes.toByteArray()));
            case Message m -> {
                if ("google.protobuf.Timestamp".equals(m.getDescriptorForType().getFullName())) {
                    long seconds = (Long) m.getField(m.getDescriptorForType().findFieldByName("seconds"));
                    int nanos = (Integer) m.getField(m.getDescriptorForType().findFieldByName("nanos"));
                    LuceneFieldEncoder.addLong(doc, field, seconds * 1000L + nanos / 1_000_000L);
                }
                // other message leaves are handled by their own compiled fields
            }
            default -> throw new InvalidPayloadException("Field '" + field.indexName()
                    + "': unsupported value type " + value.getClass().getSimpleName());
        }
    }
}
