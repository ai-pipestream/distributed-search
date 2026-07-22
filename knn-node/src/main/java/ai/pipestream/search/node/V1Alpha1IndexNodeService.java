package ai.pipestream.search.node;

import ai.pipestream.search.grpc.GrpcChannelCache;
import ai.pipestream.search.index.CollectionConfig;
import ai.pipestream.search.index.CollectionManager;
import ai.pipestream.search.index.ShardRouter;
import ai.pipestream.search.index.doc.BlockJoinDocumentBuilder;
import ai.pipestream.search.index.doc.BlockJoinFields;
import ai.pipestream.search.index.doc.BlockWriter;
import ai.pipestream.search.index.doc.LuceneFieldEncoder;
import ai.pipestream.search.index.doc.ParentDocumentProjector;
import ai.pipestream.search.schema.CompiledField;
import ai.pipestream.search.schema.CompiledSchema;
import ai.pipestream.search.schema.SchemaStore;
import ai.pipestream.search.v1alpha1.*;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC IndexService implementation for v1alpha1 frame-based bulk indexing, get, and delete.
 *
 * <p>BulkIndex protocol (see index_service.proto): the server sends an
 * unconditional FlowControl frame first (initial credit grant), then one
 * DocAck per document and one FlushAck per FlushMarker. Per-document failures
 * are reported in DocAck.status; only stream-level faults (including a failed
 * durability commit behind a FlushMarker) terminate the RPC.
 *
 * <p>Documents are routed through {@link ShardRouter}: locally-owned shards
 * are written directly, remotely-owned shards are forwarded to their primary
 * owner, and documents whose shard has no owner are rejected UNAVAILABLE
 * rather than written into a shard this node does not own.
 */
@Singleton
@GrpcService
public class V1Alpha1IndexNodeService implements IndexService {

    private static final Logger LOG = Logger.getLogger(V1Alpha1IndexNodeService.class);

    /** Initial credit window granted before any client frame is consumed. */
    private static final int INITIAL_WINDOW = 1000;

    /** Max un-acked chunks in flight (IndexDocument + IndexParentDocument). */
    private static final int INITIAL_CHUNK_WINDOW = 100_000;

    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(30);

    // com.google.rpc.Code values used in DocAck.status.
    private static final int CODE_OK = 0;
    private static final int CODE_INVALID_ARGUMENT = 3;
    private static final int CODE_NOT_FOUND = 5;
    private static final int CODE_ALREADY_EXISTS = 6;
    private static final int CODE_FAILED_PRECONDITION = 9;
    private static final int CODE_UNIMPLEMENTED = 12;
    private static final int CODE_INTERNAL = 13;
    private static final int CODE_UNAVAILABLE = 14;

    @Inject
    CollectionManager collectionManager;

    @Inject
    ShardRouter shardRouter;

    @Inject
    GrpcChannelCache channelCache;

    @Inject
    SchemaStore schemaStore;

    @Inject
    ParentDocumentProjector projector;

    @Inject
    BlockWriter blockWriter;

    /** Per-stream mutable state. */
    private static final class BulkSession {
        volatile String defaultCollection = "";
        volatile SchemaPin defaultSchema;
        volatile IndexPolicy defaultPolicy;
        /** Collections written locally since stream start (flush targets). */
        final Set<String> touchedCollections = ConcurrentHashMap.newKeySet();
        /** Remote owners forwarded to since stream start, keyed host:port. */
        final Map<String, ShardRouter.Route> remoteOwners = new ConcurrentHashMap<>();
    }

    @Override
    public Multi<BulkIndexResponse> bulkIndex(Multi<BulkIndexRequest> requests) {
        BulkSession session = new BulkSession();

        // The proto mandates the server sends the initial credit grant first,
        // regardless of whether the client opens with BulkOptions. The chunk
        // window bounds parent fan-out: a 1000-document window would
        // otherwise admit millions of chunk writes.
        Multi<BulkIndexResponse> initialGrant = Multi.createFrom().item(
                BulkIndexResponse.newBuilder()
                        .setFlowControl(FlowControl.newBuilder()
                                .setState(FlowControl.State.STATE_READY)
                                .setWindow(INITIAL_WINDOW)
                                .setChunkWindow(INITIAL_CHUNK_WINDOW)
                                .setDetail("initial credit grant")
                                .build())
                        .build());

        Multi<BulkIndexResponse> replies = requests
                .onItem().transformToMultiAndConcatenate(request -> handleFrame(session, request));

        return Multi.createBy().concatenating().streams(initialGrant, replies);
    }

    private Multi<BulkIndexResponse> handleFrame(BulkSession session, BulkIndexRequest request) {
        return switch (request.getFrameCase()) {
            case OPTIONS -> {
                BulkOptions options = request.getOptions();
                session.defaultCollection = options.getCollection();
                if (options.hasSchema()) {
                    session.defaultSchema = options.getSchema();
                    // A stale bulk load must fail at frame 1, not at frame
                    // 900k: verify the asserted pin against the collection
                    // pin immediately when both are known.
                    if (!options.getCollection().isEmpty()
                            && !options.getSchema().getDescriptorDigest().isEmpty()) {
                        SchemaStore.StoredSchema stored =
                                schemaStore.get(options.getCollection()).orElse(null);
                        if (stored == null || !stored.matches(options.getSchema())) {
                            throw io.grpc.Status.FAILED_PRECONDITION
                                    .withDescription("BulkOptions.schema does not match the "
                                            + "collection pin for '" + options.getCollection() + "'")
                                    .asRuntimeException();
                        }
                    }
                }
                if (options.hasDefaultPolicy()) {
                    session.defaultPolicy = options.getDefaultPolicy();
                }
                // The credit grant was already sent unconditionally; options
                // carry no dedicated acknowledgement frame.
                yield Multi.createFrom().empty();
            }
            case DOCUMENT -> handleDocument(session, request.getDocument()).toMulti();
            case PARENT_DOCUMENT -> handleParentDocument(session, request.getParentDocument()).toMulti();
            case FLUSH -> handleFlush(session, request.getFlush()).toMulti();
            // An empty oneof is a no-op, not an all-defaults response frame.
            case FRAME_NOT_SET -> Multi.createFrom().empty();
        };
    }

    // ------------------------------------------------------------------
    // DOCUMENT frames
    // ------------------------------------------------------------------

    private Uni<BulkIndexResponse> handleDocument(BulkSession session, IndexDocument docReq) {
        long seq = docReq.getClientSeq();
        String collectionName = docReq.getCollection().isEmpty()
                ? session.defaultCollection : docReq.getCollection();

        if (collectionName.isEmpty()) {
            return Uni.createFrom().item(
                    nack(seq, "", -1, CODE_INVALID_ARGUMENT, "No collection specified"));
        }
        CollectionConfig config = collectionManager.getConfig(collectionName);
        if (config == null) {
            return Uni.createFrom().item(
                    nack(seq, "", -1, CODE_NOT_FOUND, "Collection not found: " + collectionName));
        }
        if (docReq.hasTypedDocument() && docReq.getFieldsCount() > 0) {
            return Uni.createFrom().item(nack(seq, docReq.getDocId(), -1, CODE_INVALID_ARGUMENT,
                    "At most one of 'fields' and 'typed_document' may be set"));
        }
        if (docReq.hasTypedDocument() && schemaStore.get(collectionName).isEmpty()) {
            // Do not ack OK for input the server would discard: reflective
            // unpacking needs the collection's registered descriptor set.
            return Uni.createFrom().item(nack(seq, docReq.getDocId(), -1, CODE_FAILED_PRECONDITION,
                    "typed_document ingest requires a registered proto schema for '"
                            + collectionName + "'"));
        }

        String docId = docReq.getDocId().isEmpty() ? UUID.randomUUID().toString() : docReq.getDocId();
        ShardRouter.Route route = shardRouter.route(collectionName, config.numShards(), docId);

        return switch (route.target()) {
            case LOCAL -> Uni.createFrom()
                    .item(() -> indexLocal(session, config, collectionName, docId, docReq, route.shardId()))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
            case REMOTE -> forwardDocument(session, route, collectionName, docId, docReq);
            case NO_OWNER -> Uni.createFrom().item(nack(seq, docId, route.shardId(), CODE_UNAVAILABLE,
                    "No primary owner is available for shard " + route.shardId()));
        };
    }

    private BulkIndexResponse indexLocal(BulkSession session, CollectionConfig config,
                                         String collectionName, String docId,
                                         IndexDocument docReq, int shardId) {
        long seq = docReq.getClientSeq();
        try {
            SchemaStore.StoredSchema registered = schemaStore.get(collectionName).orElse(null);

            Document doc;
            if (docReq.hasTypedDocument()) {
                // Reflective ingest against the pinned descriptor set.
                doc = projector.projectFlatDocument(registered, docReq.getTypedDocument());
            } else if (registered != null) {
                // DYNAMIC_FIELDS_STRICT: names and kinds must match the
                // registered schema; unknown fields are rejected, not
                // silently mis-indexed.
                doc = buildSchemaStrict(registered.compiled(), docReq);
            } else {
                doc = buildKindDriven(config, docReq);
            }
            doc.add(new StringField("doc_id", docId, Field.Store.YES));

            IndexWriter writer = collectionManager.getWriter(collectionName, shardId);
            writer.updateDocument(new Term("doc_id", docId), doc);
            session.touchedCollections.add(collectionName);

            return ack(seq, docId, shardId);
        } catch (InvalidDocumentException | LuceneFieldEncoder.EncodingException
                 | ParentDocumentProjector.InvalidPayloadException e) {
            return nack(seq, docId, shardId, CODE_INVALID_ARGUMENT, e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Bulk index doc %s failed", docId);
            return nack(seq, docId, shardId, CODE_INTERNAL, safeMessage(e));
        }
    }

    /** Schema-blind fallback for collections without a registered proto schema. */
    private static Document buildKindDriven(CollectionConfig config, IndexDocument docReq) {
        Document doc = new Document();
        Set<String> vectorFields = new HashSet<>();
        for (DocumentField df : docReq.getFieldsList()) {
            requireNotReserved(df.getName());
            addFieldValues(doc, df, config, vectorFields);
        }
        return doc;
    }

    /** Strict schema-driven encoding: every field must exist with a matching kind. */
    private static Document buildSchemaStrict(CompiledSchema compiled, IndexDocument docReq) {
        Document doc = new Document();
        Set<String> vectorFields = new HashSet<>();
        for (DocumentField df : docReq.getFieldsList()) {
            requireNotReserved(df.getName());
            CompiledField field = compiled.field(df.getName()).orElseThrow(() ->
                    new InvalidDocumentException("Unknown field '" + df.getName()
                            + "'; schema fields: " + compiled.fields().stream()
                            .map(CompiledField::indexName).sorted().toList()));
            if (!field.repeated() && df.getValuesCount() > 1) {
                throw new InvalidDocumentException(
                        "Field '" + df.getName() + "' is single-valued; got "
                                + df.getValuesCount() + " values");
            }
            for (FieldValue value : df.getValuesList()) {
                switch (value.getKindCase()) {
                    case STRING_VALUE -> LuceneFieldEncoder.addString(doc, field, value.getStringValue());
                    case INT64_VALUE -> LuceneFieldEncoder.addLong(doc, field, value.getInt64Value());
                    case DOUBLE_VALUE -> LuceneFieldEncoder.addDouble(doc, field, value.getDoubleValue());
                    case BOOL_VALUE -> LuceneFieldEncoder.addBool(doc, field, value.getBoolValue());
                    case TIMESTAMP_VALUE -> LuceneFieldEncoder.addLong(doc, field,
                            value.getTimestampValue().getSeconds() * 1000L
                                    + value.getTimestampValue().getNanos() / 1_000_000L);
                    case VECTOR_VALUE -> {
                        if (!vectorFields.add(field.indexName())) {
                            throw new InvalidDocumentException("Field '" + df.getName()
                                    + "': at most one vector value per field");
                        }
                        Vector v = value.getVectorValue();
                        float[] arr = new float[v.getValuesCount()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = v.getValues(i);
                        }
                        LuceneFieldEncoder.addVector(doc, field, arr);
                    }
                    case KIND_NOT_SET -> throw new InvalidDocumentException(
                            "Field '" + df.getName() + "' has a value with no kind set");
                }
            }
        }
        return doc;
    }

    private static void requireNotReserved(String name) {
        if ("doc_id".equals(name) || (!name.isEmpty() && name.charAt(0) == '_')) {
            // A second indexed doc_id term would make upsert-by-id delete an
            // unrelated document; underscore names are engine-internal.
            throw new InvalidDocumentException("'" + name + "' is a reserved field name");
        }
        if (name.isEmpty()) {
            throw new InvalidDocumentException("Field with empty name");
        }
    }

    /**
     * Maps one DocumentField onto Lucene fields by declared value kind,
     * honoring the index-encoding contract in {@code QueryCompiler}:
     * keyword → StringField, int64/timestamp → LongPoint (+ stored),
     * double → DoublePoint (+ stored), bool → "T"/"F" keyword term,
     * vector → KnnFloatVectorField. Multi-valued fields repeat values.
     */
    private static void addFieldValues(Document doc, DocumentField df,
                                       CollectionConfig config, Set<String> vectorFields) {
        String name = df.getName();
        if (name.isEmpty()) {
            throw new InvalidDocumentException("Field with empty name");
        }
        for (FieldValue value : df.getValuesList()) {
            switch (value.getKindCase()) {
                case VECTOR_VALUE -> {
                    if (!vectorFields.add(name)) {
                        throw new InvalidDocumentException(
                                "Field '" + name + "': at most one vector value per field");
                    }
                    Vector v = value.getVectorValue();
                    float[] arr = new float[v.getValuesCount()];
                    for (int i = 0; i < arr.length; i++) {
                        arr[i] = v.getValues(i);
                    }
                    validateVector(name, arr, config);
                    doc.add(new KnnFloatVectorField(name, arr, config.similarity()));
                }
                case STRING_VALUE -> doc.add(new StringField(name, value.getStringValue(), Field.Store.YES));
                case INT64_VALUE -> {
                    doc.add(new LongPoint(name, value.getInt64Value()));
                    doc.add(new StoredField(name, value.getInt64Value()));
                }
                case DOUBLE_VALUE -> {
                    doc.add(new DoublePoint(name, value.getDoubleValue()));
                    doc.add(new StoredField(name, value.getDoubleValue()));
                }
                case BOOL_VALUE -> doc.add(new StringField(
                        name, value.getBoolValue() ? "T" : "F", Field.Store.YES));
                case TIMESTAMP_VALUE -> {
                    long millis = value.getTimestampValue().getSeconds() * 1000L
                            + value.getTimestampValue().getNanos() / 1_000_000L;
                    doc.add(new LongPoint(name, millis));
                    doc.add(new StoredField(name, millis));
                }
                case KIND_NOT_SET -> throw new InvalidDocumentException(
                        "Field '" + name + "' has a value with no kind set");
            }
        }
    }

    private static void validateVector(String name, float[] arr, CollectionConfig config) {
        if (arr.length != config.vectorDimension()) {
            // One wrong-dim document would otherwise pin the Lucene field's
            // dimension and permanently poison the shard.
            throw new InvalidDocumentException("Field '" + name + "': expected "
                    + config.vectorDimension() + " dims, got " + arr.length);
        }
        boolean allZero = true;
        for (float f : arr) {
            if (!Float.isFinite(f)) {
                throw new InvalidDocumentException("Field '" + name + "': non-finite vector component");
            }
            if (f != 0.0f) {
                allZero = false;
            }
        }
        if (allZero && config.similarity() == org.apache.lucene.index.VectorSimilarityFunction.COSINE) {
            throw new InvalidDocumentException(
                    "Field '" + name + "': all-zero vector is invalid under COSINE similarity");
        }
    }

    // ------------------------------------------------------------------
    // PARENT_DOCUMENT frames (document-centric ingest, mode B)
    // ------------------------------------------------------------------

    private Uni<BulkIndexResponse> handleParentDocument(BulkSession session, IndexParentDocument req) {
        long seq = req.getClientSeq();
        String collectionName = req.getCollection().isEmpty()
                ? session.defaultCollection : req.getCollection();

        if (collectionName.isEmpty()) {
            return Uni.createFrom().item(parentNack(seq, req.getDocId(),
                    CODE_INVALID_ARGUMENT, "No collection specified"));
        }
        CollectionConfig config = collectionManager.getConfig(collectionName);
        if (config == null) {
            return Uni.createFrom().item(parentNack(seq, req.getDocId(),
                    CODE_NOT_FOUND, "Collection not found: " + collectionName));
        }
        if (!config.documentCentric()) {
            return Uni.createFrom().item(parentNack(seq, req.getDocId(), CODE_FAILED_PRECONDITION,
                    "Collection '" + collectionName + "' is not document-centric; register a "
                            + "schema with chunk_message set (on an empty collection) first"));
        }
        String docId = req.getDocId();
        if (docId.isEmpty()) {
            // Unlike IndexDocument, the parent id is load-bearing: replay and
            // delete-by-parent both key on it.
            return Uni.createFrom().item(parentNack(seq, "", CODE_INVALID_ARGUMENT,
                    "IndexParentDocument.doc_id is required"));
        }
        if (req.getModeCase() == IndexParentDocument.ModeCase.SERVER_CHUNKING) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_UNIMPLEMENTED,
                    "Server-side chunking (mode A) is not implemented yet; send supplied_chunks"));
        }
        if (req.getModeCase() != IndexParentDocument.ModeCase.SUPPLIED_CHUNKS) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_INVALID_ARGUMENT,
                    "One of server_chunking / supplied_chunks must be set"));
        }
        SuppliedChunks supplied = req.getSuppliedChunks();
        if (supplied.getChunksCount() == 0) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_INVALID_ARGUMENT,
                    "supplied_chunks.chunks must not be empty"));
        }
        int cap = req.getPolicy().getMaxChunks() > 0
                ? Math.min(req.getPolicy().getMaxChunks(), config.maxChunksPerDocument())
                : config.maxChunksPerDocument();
        if (supplied.getChunksCount() > cap) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_INVALID_ARGUMENT,
                    "Parent has " + supplied.getChunksCount() + " chunks; the cap is " + cap));
        }
        boolean anyMissingVector = supplied.getChunksList().stream().anyMatch(c -> !c.hasVector());
        if (anyMissingVector && supplied.getEmbedMissingVectors()) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_UNIMPLEMENTED,
                    "embed_missing_vectors requires server-side embedding, which is not implemented yet"));
        }
        if (anyMissingVector) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_INVALID_ARGUMENT,
                    "Every chunk needs a vector (or set embed_missing_vectors)"));
        }
        if (!req.hasPayload() || req.getPayload().getTypeUrl().isEmpty()) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_INVALID_ARGUMENT,
                    "IndexParentDocument.payload (the pinned root message, in an Any) is required"));
        }

        int[] ordinals = resolveOrdinals(supplied);
        if (ordinals == null) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_INVALID_ARGUMENT,
                    "Chunk ordinals must be either all-implicit (all zero) or explicit and distinct"));
        }

        IndexPolicy.Placement placement = effectivePlacement(session, config, req);
        if (placement == IndexPolicy.Placement.PLACEMENT_CONTIGUOUS) {
            return Uni.createFrom().item(parentNack(seq, docId, CODE_UNIMPLEMENTED,
                    "PLACEMENT_CONTIGUOUS is not implemented yet"));
        }
        if (placement == IndexPolicy.Placement.PLACEMENT_BALANCED_SIMILARITY) {
            if (config.numShards() > 1 && !shardRouter.allShardsLocal()) {
                return Uni.createFrom().item(parentNack(seq, docId, CODE_UNIMPLEMENTED,
                        "Balanced placement across remote shard owners is not supported yet"));
            }
            return Uni.createFrom()
                    .item(() -> writeParentBalanced(session, config, collectionName, docId,
                            req, supplied, ordinals))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        }

        ShardRouter.Route route = shardRouter.route(collectionName, config.numShards(), docId);
        return switch (route.target()) {
            case LOCAL -> Uni.createFrom()
                    .item(() -> writeParentLocal(session, config, collectionName, docId, req,
                            supplied, ordinals, route.shardId()))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
            case REMOTE -> forwardParent(session, route, collectionName, req);
            case NO_OWNER -> Uni.createFrom().item(parentNack(seq, docId, CODE_UNAVAILABLE,
                    "No primary owner is available for shard " + route.shardId()));
        };
    }

    /** Implicit (all zero → by position) or explicit distinct ordinals; null = invalid. */
    private static int[] resolveOrdinals(SuppliedChunks supplied) {
        int n = supplied.getChunksCount();
        int[] ordinals = new int[n];
        boolean allZero = true;
        for (int i = 0; i < n; i++) {
            ordinals[i] = supplied.getChunks(i).getOrdinal();
            if (ordinals[i] != 0) {
                allZero = false;
            }
        }
        if (allZero) {
            for (int i = 0; i < n; i++) {
                ordinals[i] = i;
            }
            return ordinals;
        }
        Set<Integer> seen = new HashSet<>();
        for (int ordinal : ordinals) {
            if (ordinal < 0 || !seen.add(ordinal)) {
                return null;
            }
        }
        return ordinals;
    }

    private static IndexPolicy.Placement effectivePlacement(BulkSession session,
                                                            CollectionConfig config,
                                                            IndexParentDocument req) {
        if (req.hasPolicy() && req.getPolicy().getPlacement() != IndexPolicy.Placement.PLACEMENT_UNSPECIFIED) {
            return req.getPolicy().getPlacement();
        }
        IndexPolicy sessionDefault = session.defaultPolicy;
        if (sessionDefault != null
                && sessionDefault.getPlacement() != IndexPolicy.Placement.PLACEMENT_UNSPECIFIED) {
            return sessionDefault.getPlacement();
        }
        // Collection default: balanced similarity clustering for
        // document-centric collections created with that placement mode.
        return config.placement() == CollectionConfig.PlacementMode.BALANCED_SIMILARITY
                ? IndexPolicy.Placement.PLACEMENT_BALANCED_SIMILARITY
                : IndexPolicy.Placement.PLACEMENT_SINGLE_SHARD;
    }

    /**
     * Balanced multi-shard fan-out: chunks cluster by similarity across
     * shards (cap ceil(n/S)), each occupied shard gets a block, every other
     * shard gets a generation-bounded purge. All-or-nothing per parent:
     * a mid-fan-out failure compensates by deleting the blocks that landed
     * and reports ABORTED (DATA_LOSS when compensation itself fails).
     */
    private BulkIndexResponse writeParentBalanced(BulkSession session, CollectionConfig config,
                                                  String collectionName, String docId,
                                                  IndexParentDocument req, SuppliedChunks supplied,
                                                  int[] ordinals) {
        long seq = req.getClientSeq();
        try {
            SchemaPin asserted = req.hasSchema() ? req.getSchema() : session.defaultSchema;
            SchemaStore.StoredSchema schema = projector.resolvePinned(collectionName, asserted);

            // Chunks in ordinal order (placement and block layout both use it).
            Integer[] order = new Integer[ordinals.length];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> ordinals[i]));
            List<Chunk> chunksInOrder = new ArrayList<>(order.length);
            List<float[]> vectors = new ArrayList<>(order.length);
            int[] ordinalByPosition = new int[order.length];
            for (int position = 0; position < order.length; position++) {
                Chunk chunk = supplied.getChunks(order[position]);
                chunksInOrder.add(chunk);
                ordinalByPosition[position] = ordinals[order[position]];
                float[] vector = new float[chunk.getVector().getValuesCount()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = chunk.getVector().getValues(i);
                }
                vectors.add(vector);
            }

            ai.pipestream.search.index.placement.ChunkPlacement placement =
                    new ai.pipestream.search.index.placement.BalancedNearestNeighbourChainPlacement()
                            .place(new ai.pipestream.search.index.placement.PlacementRequest(
                                    docId, vectors, config.numShards(), config.similarity()));

            // Generation: monotonic across ALL shards of this parent.
            long lastGeneration = 0;
            for (int shard = 0; shard < config.numShards(); shard++) {
                lastGeneration = Math.max(lastGeneration,
                        blockWriter.lastGeneration(collectionName, shard, docId));
            }
            long generation = req.getGeneration() != 0 ? req.getGeneration() : lastGeneration + 1;
            if (lastGeneration >= generation) {
                return parentNack(seq, docId, CODE_ALREADY_EXISTS, "Parent '" + docId
                        + "' already has generation " + lastGeneration + " >= " + generation);
            }

            // Phase 1 (validation): project EVERY block before any write, so
            // payload problems are INVALID_ARGUMENT with zero index mutations.
            Map<Integer, int[]> occupied = placement.occupiedShards();
            int totalChunks = chunksInOrder.size();
            Map<Integer, List<Document>> blocksByShard = new java.util.LinkedHashMap<>();
            for (Map.Entry<Integer, int[]> entry : occupied.entrySet()) {
                int shardId = entry.getKey();
                List<Document> children = new ArrayList<>(entry.getValue().length);
                for (int position : positionsFor(placement.shardOfChunk(), shardId)) {
                    Chunk chunk = chunksInOrder.get(position);
                    int ordinal = ordinalByPosition[position];
                    String chunkId = chunk.getChunkId().isEmpty()
                            ? docId + "#" + generation + "#" + ordinal
                            : chunk.getChunkId();
                    children.add(projector.projectChunk(schema, config, chunk, ordinal, chunkId));
                }
                Document stub = projector.projectParentStub(schema, req.getPayload());
                blocksByShard.put(shardId, BlockJoinDocumentBuilder.build(
                        docId, generation, stub, children, totalChunks));
            }

            // Phase 2 (committed): fan out. From here on a failure compensates.
            List<BlockAck> blocks = new ArrayList<>();
            List<Integer> writtenShards = new ArrayList<>();
            try {
                for (Map.Entry<Integer, List<Document>> entry : blocksByShard.entrySet()) {
                    int shardId = entry.getKey();
                    BlockWriter.BlockWriteResult result = blockWriter.writeBlock(
                            collectionName, shardId, docId, generation, entry.getValue());
                    writtenShards.add(shardId);
                    blocks.add(BlockAck.newBuilder()
                            .setShardId(shardId)
                            .setChunkCount(result.chunkCount())
                            .setPurgedDocs(result.purgedDocs())
                            .setStatus(com.google.rpc.Status.newBuilder().setCode(CODE_OK).build())
                            .build());
                }
                // Purge older generations off shards this placement skipped.
                for (int shardId = 0; shardId < config.numShards(); shardId++) {
                    if (occupied.containsKey(shardId)) {
                        continue;
                    }
                    int purged = blockWriter.purgeParent(collectionName, shardId, docId, generation);
                    if (purged > 0) {
                        blocks.add(BlockAck.newBuilder()
                                .setShardId(shardId)
                                .setPurgedDocs(purged)
                                .setStatus(com.google.rpc.Status.newBuilder().setCode(CODE_OK).build())
                                .build());
                    }
                }
            } catch (Exception e) {
                LOG.errorf(e, "Parent %s fan-out failed after %d block(s); compensating",
                        docId, writtenShards.size());
                try {
                    for (int shardId : writtenShards) {
                        blockWriter.deleteGeneration(collectionName, shardId, docId, generation);
                    }
                } catch (Exception compensation) {
                    LOG.errorf(compensation, "Compensation for parent %s FAILED", docId);
                    return parentNack(seq, docId, 15 /* DATA_LOSS */,
                            "Partial write could not be compensated on shards " + writtenShards
                                    + ": " + safeMessage(compensation));
                }
                return parentNack(seq, docId, 10 /* ABORTED */,
                        "Fan-out failed (" + safeMessage(e) + "); all blocks were rolled back");
            }

            session.touchedCollections.add(collectionName);
            return BulkIndexResponse.newBuilder()
                    .setParentAck(ParentAck.newBuilder()
                            .setClientSeq(seq)
                            .setDocId(docId)
                            .setGeneration(generation)
                            .setChunkCount(totalChunks)
                            .addAllBlocks(blocks)
                            .setStatus(com.google.rpc.Status.newBuilder().setCode(CODE_OK).build())
                            .setChunkCreditsConsumed(totalChunks)
                            .setResolvedSchema(schema.toPin())
                            .build())
                    .build();
        } catch (BlockWriter.StaleGenerationException e) {
            return parentNack(seq, docId, CODE_ALREADY_EXISTS, e.getMessage());
        } catch (ParentDocumentProjector.SchemaPinMismatchException e) {
            return parentNack(seq, docId, CODE_FAILED_PRECONDITION, e.getMessage());
        } catch (ParentDocumentProjector.InvalidPayloadException
                 | LuceneFieldEncoder.EncodingException | IllegalArgumentException e) {
            return parentNack(seq, docId, CODE_INVALID_ARGUMENT, e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Parent write %s failed", docId);
            return parentNack(seq, docId, CODE_INTERNAL, safeMessage(e));
        }
    }

    /** Positions (in ordinal-sorted chunk order) landing on one shard, ascending. */
    private static List<Integer> positionsFor(int[] shardOfChunk, int shardId) {
        List<Integer> positions = new ArrayList<>();
        for (int position = 0; position < shardOfChunk.length; position++) {
            if (shardOfChunk[position] == shardId) {
                positions.add(position);
            }
        }
        return positions;
    }

    private BulkIndexResponse writeParentLocal(BulkSession session, CollectionConfig config,
                                               String collectionName, String docId,
                                               IndexParentDocument req, SuppliedChunks supplied,
                                               int[] ordinals, int shardId) {
        long seq = req.getClientSeq();
        try {
            SchemaPin asserted = req.hasSchema() ? req.getSchema() : session.defaultSchema;
            SchemaStore.StoredSchema schema = projector.resolvePinned(collectionName, asserted);

            long generation = req.getGeneration();
            if (generation == 0) {
                generation = blockWriter.lastGeneration(collectionName, shardId, docId) + 1;
            }

            Document stub = projector.projectParentStub(schema, req.getPayload());

            // Children in ordinal order, ids assigned where absent.
            Integer[] order = new Integer[ordinals.length];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> ordinals[i]));
            List<Document> children = new ArrayList<>(order.length);
            for (int idx : order) {
                Chunk chunk = supplied.getChunks(idx);
                String chunkId = chunk.getChunkId().isEmpty()
                        ? docId + "#" + generation + "#" + ordinals[idx]
                        : chunk.getChunkId();
                children.add(projector.projectChunk(schema, config, chunk, ordinals[idx], chunkId));
            }

            List<Document> block = BlockJoinDocumentBuilder.build(
                    docId, generation, stub, children, children.size());
            BlockWriter.BlockWriteResult result =
                    blockWriter.writeBlock(collectionName, shardId, docId, generation, block);
            session.touchedCollections.add(collectionName);

            return BulkIndexResponse.newBuilder()
                    .setParentAck(ParentAck.newBuilder()
                            .setClientSeq(seq)
                            .setDocId(docId)
                            .setGeneration(generation)
                            .setChunkCount(result.chunkCount())
                            .addBlocks(BlockAck.newBuilder()
                                    .setShardId(shardId)
                                    .setChunkCount(result.chunkCount())
                                    .setPurgedDocs(result.purgedDocs())
                                    .setStatus(com.google.rpc.Status.newBuilder().setCode(CODE_OK).build())
                                    .build())
                            .setStatus(com.google.rpc.Status.newBuilder().setCode(CODE_OK).build())
                            .setChunkCreditsConsumed(result.chunkCount())
                            .setResolvedSchema(schema.toPin())
                            .build())
                    .build();
        } catch (BlockWriter.StaleGenerationException e) {
            return parentNack(seq, docId, CODE_ALREADY_EXISTS, e.getMessage());
        } catch (ParentDocumentProjector.SchemaPinMismatchException e) {
            return parentNack(seq, docId, CODE_FAILED_PRECONDITION, e.getMessage());
        } catch (ParentDocumentProjector.InvalidPayloadException
                 | LuceneFieldEncoder.EncodingException | IllegalArgumentException e) {
            return parentNack(seq, docId, CODE_INVALID_ARGUMENT, e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Parent write %s failed", docId);
            return parentNack(seq, docId, CODE_INTERNAL, safeMessage(e));
        }
    }

    private Uni<BulkIndexResponse> forwardParent(BulkSession session, ShardRouter.Route route,
                                                 String collectionName, IndexParentDocument req) {
        session.remoteOwners.put(route.host() + ":" + route.port(), route);
        IndexParentDocument forwarded = req.toBuilder().setCollection(collectionName).build();
        MutinyIndexServiceGrpc.MutinyIndexServiceStub stub = MutinyIndexServiceGrpc.newMutinyStub(
                channelCache.getOrCreate(route.host(), route.port()));
        return stub.bulkIndex(Multi.createFrom().item(
                        BulkIndexRequest.newBuilder().setParentDocument(forwarded).build()))
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.PARENT_ACK)
                .toUni()
                .ifNoItem().after(REMOTE_TIMEOUT).fail()
                .onFailure().recoverWithItem(t -> {
                    LOG.warnf(t, "Forward of parent %s to shard %d owner %s:%d failed",
                            req.getDocId(), route.shardId(), route.host(), route.port());
                    return parentNack(req.getClientSeq(), req.getDocId(), CODE_UNAVAILABLE,
                            "Forward to shard owner failed: " + safeMessage(t));
                });
    }

    private static BulkIndexResponse parentNack(long seq, String docId, int code, String message) {
        return BulkIndexResponse.newBuilder()
                .setParentAck(ParentAck.newBuilder()
                        .setClientSeq(seq)
                        .setDocId(docId)
                        .setStatus(com.google.rpc.Status.newBuilder()
                                .setCode(code)
                                .setMessage(message == null ? "" : message)
                                .build())
                        .build())
                .build();
    }

    /** Forwards a document to the remote primary owner of its shard. */
    private Uni<BulkIndexResponse> forwardDocument(BulkSession session, ShardRouter.Route route,
                                                   String collectionName, String docId,
                                                   IndexDocument docReq) {
        session.remoteOwners.put(route.host() + ":" + route.port(), route);
        IndexDocument forwarded = docReq.toBuilder()
                .setCollection(collectionName)
                .setDocId(docId)
                .build();
        MutinyIndexServiceGrpc.MutinyIndexServiceStub stub = MutinyIndexServiceGrpc.newMutinyStub(
                channelCache.getOrCreate(route.host(), route.port()));
        return stub.bulkIndex(Multi.createFrom().item(
                        BulkIndexRequest.newBuilder().setDocument(forwarded).build()))
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.ACK)
                .toUni()
                .ifNoItem().after(REMOTE_TIMEOUT).fail()
                .onFailure().recoverWithItem(t -> {
                    LOG.warnf(t, "Forward of doc %s to shard %d owner %s:%d failed",
                            docId, route.shardId(), route.host(), route.port());
                    return nack(docReq.getClientSeq(), docId, route.shardId(), CODE_UNAVAILABLE,
                            "Forward to shard owner failed: " + safeMessage(t));
                });
    }

    // ------------------------------------------------------------------
    // FLUSH frames
    // ------------------------------------------------------------------

    private Uni<BulkIndexResponse> handleFlush(BulkSession session, FlushMarker flush) {
        // Durability first: commit every collection this stream wrote locally.
        // A failed commit MUST NOT produce a FlushAck — it fails the stream so
        // the client keeps its replay buffer.
        Uni<Void> localCommit = Uni.createFrom().item(() -> {
            for (String collection : session.touchedCollections) {
                try {
                    collectionManager.commitCollection(collection);
                } catch (IOException e) {
                    LOG.errorf(e, "Durability flush failed for collection %s", collection);
                    throw io.grpc.Status.INTERNAL
                            .withDescription("Durability flush failed for '" + collection + "': "
                                    + safeMessage(e))
                            .asRuntimeException();
                }
            }
            return (Void) null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

        // Fan the flush out to every remote owner this stream forwarded to.
        List<Uni<Void>> remoteFlushes = new ArrayList<>();
        for (ShardRouter.Route route : session.remoteOwners.values()) {
            remoteFlushes.add(forwardFlush(route, flush));
        }

        Uni<Void> all = remoteFlushes.isEmpty()
                ? localCommit
                : localCommit.chain(() -> Uni.combine().all().unis(remoteFlushes).discardItems());

        return all.onItem().transform(ignored -> BulkIndexResponse.newBuilder()
                .setFlushAck(FlushAck.newBuilder().setThroughSeq(flush.getClientSeq()).build())
                .build());
    }

    private Uni<Void> forwardFlush(ShardRouter.Route route, FlushMarker flush) {
        MutinyIndexServiceGrpc.MutinyIndexServiceStub stub = MutinyIndexServiceGrpc.newMutinyStub(
                channelCache.getOrCreate(route.host(), route.port()));
        return stub.bulkIndex(Multi.createFrom().item(
                        BulkIndexRequest.newBuilder().setFlush(flush).build()))
                .filter(r -> r.getFrameCase() == BulkIndexResponse.FrameCase.FLUSH_ACK)
                .toUni()
                .ifNoItem().after(REMOTE_TIMEOUT).fail()
                .onFailure().transform(t -> io.grpc.Status.INTERNAL
                        .withDescription("Durability flush failed on shard owner "
                                + route.host() + ":" + route.port() + ": " + safeMessage(t))
                        .asRuntimeException())
                .replaceWithVoid();
    }

    // ------------------------------------------------------------------
    // GetDocument / DeleteDocument
    // ------------------------------------------------------------------

    @Override
    public Uni<GetDocumentResponse> getDocument(GetDocumentRequest request) {
        CollectionConfig config = collectionManager.getConfig(request.getCollection());
        if (config == null) {
            return Uni.createFrom().failure(io.grpc.Status.NOT_FOUND
                    .withDescription("Collection not found: " + request.getCollection())
                    .asRuntimeException());
        }

        if (config.documentCentric()) {
            // Balanced placement: the stub lives on placement-chosen shards,
            // not hash(doc_id) — scan every shard.
            return Uni.createFrom().item(() -> getParentDocument(request, config))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        }

        ShardRouter.Route route = shardRouter.route(
                request.getCollection(), config.numShards(), request.getDocId());
        if (route.target() == ShardRouter.Route.Target.REMOTE) {
            return MutinyIndexServiceGrpc.newMutinyStub(
                            channelCache.getOrCreate(route.host(), route.port()))
                    .getDocument(request);
        }
        if (route.target() == ShardRouter.Route.Target.NO_OWNER) {
            return Uni.createFrom().failure(io.grpc.Status.UNAVAILABLE
                    .withDescription("No primary owner is available for shard " + route.shardId())
                    .asRuntimeException());
        }

        return Uni.createFrom().item(() -> {
            DirectoryReader reader = null;
            try {
                reader = collectionManager.getReader(request.getCollection(), route.shardId());
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(
                        new TermQuery(new Term("doc_id", request.getDocId())), 1);
                if (topDocs.scoreDocs.length == 0) {
                    return GetDocumentResponse.newBuilder().setFound(false).build();
                }

                Document doc;
                if (request.getFieldsCount() > 0) {
                    doc = reader.storedFields().document(
                            topDocs.scoreDocs[0].doc, new HashSet<>(request.getFieldsList()));
                } else {
                    doc = reader.storedFields().document(topDocs.scoreDocs[0].doc);
                }

                GetDocumentResponse.Builder resp = GetDocumentResponse.newBuilder()
                        .setFound(true)
                        .setDocId(request.getDocId());
                for (IndexableField field : doc.getFields()) {
                    if (field.name().startsWith("_")) {
                        continue;   // engine-internal fields
                    }
                    resp.addFields(DocumentField.newBuilder()
                            .setName(field.name())
                            .addValues(toFieldValue(field))
                            .build());
                }
                return resp.build();
            } catch (IndexNotFoundException e) {
                // Empty shard: a successful lookup that matched nothing.
                return GetDocumentResponse.newBuilder().setFound(false).build();
            } catch (IOException e) {
                LOG.errorf(e, "Get document %s failed", request.getDocId());
                throw io.grpc.Status.INTERNAL
                        .withDescription("Get document failed: " + safeMessage(e))
                        .asRuntimeException();
            } finally {
                if (reader != null) {
                    try {
                        collectionManager.releaseReader(reader);
                    } catch (IOException ignored) {
                        // release failures are non-fatal
                    }
                }
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Document-centric lookup: the parent stub is on whichever shards the
     * placement chose; chunks may span several shards. Scans every shard,
     * returns the payload from the first stub found and the chunk list
     * merged across shards in ordinal order.
     */
    private GetDocumentResponse getParentDocument(GetDocumentRequest request,
                                                  CollectionConfig config) {
        GetDocumentResponse.Builder resp = GetDocumentResponse.newBuilder()
                .setDocId(request.getDocId());
        boolean found = false;
        List<Chunk> chunks = new ArrayList<>();
        org.apache.lucene.search.Query stubLookup = new org.apache.lucene.search.BooleanQuery.Builder()
                .add(new TermQuery(new Term("doc_id", request.getDocId())),
                        org.apache.lucene.search.BooleanClause.Occur.MUST)
                .add(BlockJoinFields.PARENT_QUERY,
                        org.apache.lucene.search.BooleanClause.Occur.MUST)
                .build();

        for (int shardId = 0; shardId < config.numShards(); shardId++) {
            DirectoryReader reader = null;
            try {
                try {
                    reader = collectionManager.getReader(request.getCollection(), shardId);
                } catch (IndexNotFoundException e) {
                    continue;   // empty shard
                }
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs stubs = searcher.search(stubLookup, 1);
                if (stubs.scoreDocs.length > 0 && !found) {
                    found = true;
                    Document stub = reader.storedFields().document(stubs.scoreDocs[0].doc);
                    for (IndexableField field : stub.getFields()) {
                        if (field.name().startsWith("_")) {
                            continue;
                        }
                        if (request.getFieldsCount() > 0
                                && !request.getFieldsList().contains(field.name())) {
                            continue;
                        }
                        resp.addFields(DocumentField.newBuilder()
                                .setName(field.name())
                                .addValues(toFieldValue(field))
                                .build());
                    }
                    org.apache.lucene.util.BytesRef payload =
                            stub.getBinaryValue(BlockJoinFields.PARENT_PAYLOAD);
                    if (payload != null) {
                        resp.setTypedDocument(com.google.protobuf.Any.parseFrom(
                                com.google.protobuf.ByteString.copyFrom(
                                        payload.bytes, payload.offset, payload.length)));
                    }
                }
                if (request.getIncludeChunks()) {
                    collectChunks(chunks, searcher, reader, request.getDocId(), config);
                }
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                LOG.warnf(e, "Stored payload for %s failed to parse", request.getDocId());
            } catch (IOException e) {
                LOG.errorf(e, "Get parent document %s failed on shard %d",
                        request.getDocId(), shardId);
                throw io.grpc.Status.INTERNAL
                        .withDescription("Get document failed: " + safeMessage(e))
                        .asRuntimeException();
            } finally {
                if (reader != null) {
                    try {
                        collectionManager.releaseReader(reader);
                    } catch (IOException ignored) {
                        // release failures are non-fatal
                    }
                }
            }
        }

        if (!found) {
            return GetDocumentResponse.newBuilder().setFound(false).build();
        }
        chunks.sort(java.util.Comparator.comparingInt(Chunk::getOrdinal));
        resp.addAllChunks(chunks);
        return resp.setFound(true).build();
    }

    /** Reassembles Chunk entries from one shard's child documents. */
    private static void collectChunks(List<Chunk> into, IndexSearcher searcher,
                                      DirectoryReader reader, String docId,
                                      CollectionConfig config) throws IOException {
        org.apache.lucene.search.Query children = new org.apache.lucene.search.BooleanQuery.Builder()
                .add(new TermQuery(new Term("doc_id", docId)),
                        org.apache.lucene.search.BooleanClause.Occur.MUST)
                .add(BlockJoinFields.PARENT_QUERY,
                        org.apache.lucene.search.BooleanClause.Occur.MUST_NOT)
                .build();
        TopDocs childDocs = searcher.search(children, config.maxChunksPerDocument(),
                new org.apache.lucene.search.Sort(new org.apache.lucene.search.SortField(
                        BlockJoinFields.CHUNK_ORD, org.apache.lucene.search.SortField.Type.LONG)));
        for (org.apache.lucene.search.ScoreDoc sd : childDocs.scoreDocs) {
            Document child = reader.storedFields().document(sd.doc);
            Chunk.Builder chunk = Chunk.newBuilder();
            String chunkId = child.get(BlockJoinFields.CHUNK_ID);
            if (chunkId != null) {
                chunk.setChunkId(chunkId);
            }
            IndexableField ordinal = child.getField(BlockJoinFields.CHUNK_ORD);
            if (ordinal != null && ordinal.numericValue() != null) {
                chunk.setOrdinal(ordinal.numericValue().intValue());
            }
            IndexableField start = child.getField(BlockJoinFields.CHUNK_START);
            if (start != null && start.numericValue() != null) {
                chunk.setStartOffset(start.numericValue().intValue());
            }
            IndexableField end = child.getField(BlockJoinFields.CHUNK_END);
            if (end != null && end.numericValue() != null) {
                chunk.setEndOffset(end.numericValue().intValue());
            }
            org.apache.lucene.util.BytesRef payload =
                    child.getBinaryValue(BlockJoinFields.CHUNK_PAYLOAD);
            if (payload != null) {
                chunk.setPayload(com.google.protobuf.Any.parseFrom(
                        com.google.protobuf.ByteString.copyFrom(
                                payload.bytes, payload.offset, payload.length)));
            }
            into.add(chunk.build());
        }
    }

    @Override
    public Uni<DeleteParentDocumentResponse> deleteParentDocument(DeleteParentDocumentRequest request) {
        CollectionConfig config = collectionManager.getConfig(request.getCollection());
        if (config == null) {
            return Uni.createFrom().failure(io.grpc.Status.NOT_FOUND
                    .withDescription("Collection not found: " + request.getCollection())
                    .asRuntimeException());
        }
        if (!config.documentCentric()) {
            return Uni.createFrom().failure(io.grpc.Status.FAILED_PRECONDITION
                    .withDescription("Collection '" + request.getCollection()
                            + "' is not document-centric")
                    .asRuntimeException());
        }
        if (request.getDocId().isEmpty()) {
            return Uni.createFrom().failure(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("doc_id is required")
                    .asRuntimeException());
        }

        return Uni.createFrom().item(() -> {
            List<Integer> shards = new ArrayList<>();
            if (request.getShardsCount() > 0) {
                request.getShardsList().forEach(shards::add);
            } else {
                for (int i = 0; i < config.numShards(); i++) {
                    shards.add(i);
                }
            }

            DeleteParentDocumentResponse.Builder resp = DeleteParentDocumentResponse.newBuilder();
            int blocksDeleted = 0;
            for (int shardId : shards) {
                try {
                    int purged = blockWriter.purgeParent(request.getCollection(), shardId,
                            request.getDocId(), request.getBelowGeneration());
                    if (purged > 0) {
                        blocksDeleted++;
                    }
                    resp.addBlocks(BlockAck.newBuilder()
                            .setShardId(shardId)
                            .setPurgedDocs(purged)
                            .setStatus(com.google.rpc.Status.newBuilder().setCode(CODE_OK).build())
                            .build());
                } catch (IOException e) {
                    LOG.errorf(e, "Purge of parent %s on shard %d failed",
                            request.getDocId(), shardId);
                    resp.addBlocks(BlockAck.newBuilder()
                            .setShardId(shardId)
                            .setStatus(com.google.rpc.Status.newBuilder()
                                    .setCode(CODE_INTERNAL)
                                    .setMessage(safeMessage(e))
                                    .build())
                            .build());
                }
            }
            return resp.setBlocksDeleted(blocksDeleted).build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private static FieldValue toFieldValue(IndexableField field) {
        Number numeric = field.numericValue();
        if (numeric instanceof Long l) {
            return FieldValue.newBuilder().setInt64Value(l).build();
        }
        if (numeric instanceof Double d) {
            return FieldValue.newBuilder().setDoubleValue(d).build();
        }
        return FieldValue.newBuilder().setStringValue(
                field.stringValue() == null ? "" : field.stringValue()).build();
    }

    @Override
    public Uni<DeleteDocumentResponse> deleteDocument(DeleteDocumentRequest request) {
        CollectionConfig config = collectionManager.getConfig(request.getCollection());
        if (config == null) {
            return Uni.createFrom().failure(io.grpc.Status.NOT_FOUND
                    .withDescription("Collection not found: " + request.getCollection())
                    .asRuntimeException());
        }

        if (config.documentCentric()) {
            // The whole block, wherever placement put its pieces.
            return Uni.createFrom().item(() -> {
                try {
                    boolean found = false;
                    for (int shardId = 0; shardId < config.numShards(); shardId++) {
                        found |= blockWriter.purgeParent(request.getCollection(), shardId,
                                request.getDocId(), 0) > 0;
                    }
                    return DeleteDocumentResponse.newBuilder().setFound(found).build();
                } catch (IOException e) {
                    LOG.errorf(e, "Delete parent %s failed", request.getDocId());
                    throw io.grpc.Status.INTERNAL
                            .withDescription("Delete failed: " + safeMessage(e))
                            .asRuntimeException();
                }
            }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        }

        ShardRouter.Route route = shardRouter.route(
                request.getCollection(), config.numShards(), request.getDocId());
        if (route.target() == ShardRouter.Route.Target.REMOTE) {
            return MutinyIndexServiceGrpc.newMutinyStub(
                            channelCache.getOrCreate(route.host(), route.port()))
                    .deleteDocument(request);
        }
        if (route.target() == ShardRouter.Route.Target.NO_OWNER) {
            return Uni.createFrom().failure(io.grpc.Status.UNAVAILABLE
                    .withDescription("No primary owner is available for shard " + route.shardId())
                    .asRuntimeException());
        }

        return Uni.createFrom().item(() -> {
            try {
                boolean found;
                try {
                    DirectoryReader reader = collectionManager.getReader(
                            request.getCollection(), route.shardId());
                    try {
                        found = new IndexSearcher(reader).count(
                                new TermQuery(new Term("doc_id", request.getDocId()))) > 0;
                    } finally {
                        collectionManager.releaseReader(reader);
                    }
                } catch (IndexNotFoundException e) {
                    // Never-written shard: nothing to delete.
                    return DeleteDocumentResponse.newBuilder().setFound(false).build();
                }

                IndexWriter writer = collectionManager.getWriter(request.getCollection(), route.shardId());
                writer.deleteDocuments(new Term("doc_id", request.getDocId()));
                return DeleteDocumentResponse.newBuilder().setFound(found).build();
            } catch (IOException e) {
                // A delete that FAILED must not look like "nothing to delete".
                LOG.errorf(e, "Delete document %s failed", request.getDocId());
                throw io.grpc.Status.INTERNAL
                        .withDescription("Delete failed: " + safeMessage(e))
                        .asRuntimeException();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static BulkIndexResponse ack(long seq, String docId, int shardId) {
        return BulkIndexResponse.newBuilder()
                .setAck(DocAck.newBuilder()
                        .setClientSeq(seq)
                        .setDocId(docId)
                        .setShardId(shardId)
                        .setStatus(com.google.rpc.Status.newBuilder().setCode(CODE_OK).build())
                        .build())
                .build();
    }

    private static BulkIndexResponse nack(long seq, String docId, int shardId, int code, String message) {
        DocAck.Builder ack = DocAck.newBuilder()
                .setClientSeq(seq)
                .setDocId(docId)
                .setStatus(com.google.rpc.Status.newBuilder()
                        .setCode(code)
                        .setMessage(message == null ? "" : message)
                        .build());
        if (shardId >= 0) {
            ack.setShardId(shardId);
        }
        return BulkIndexResponse.newBuilder().setAck(ack.build()).build();
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** A document rejected before touching the index (DocAck INVALID_ARGUMENT). */
    private static final class InvalidDocumentException extends RuntimeException {
        InvalidDocumentException(String message) {
            super(message);
        }
    }
}
