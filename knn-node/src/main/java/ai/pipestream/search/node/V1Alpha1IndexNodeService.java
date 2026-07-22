package ai.pipestream.search.node;

import ai.pipestream.search.grpc.GrpcChannelCache;
import ai.pipestream.search.index.CollectionConfig;
import ai.pipestream.search.index.CollectionManager;
import ai.pipestream.search.index.ShardRouter;
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

    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(30);

    // com.google.rpc.Code values used in DocAck.status.
    private static final int CODE_OK = 0;
    private static final int CODE_INVALID_ARGUMENT = 3;
    private static final int CODE_NOT_FOUND = 5;
    private static final int CODE_UNIMPLEMENTED = 12;
    private static final int CODE_INTERNAL = 13;
    private static final int CODE_UNAVAILABLE = 14;

    @Inject
    CollectionManager collectionManager;

    @Inject
    ShardRouter shardRouter;

    @Inject
    GrpcChannelCache channelCache;

    /** Per-stream mutable state. */
    private static final class BulkSession {
        volatile String defaultCollection = "";
        /** Collections written locally since stream start (flush targets). */
        final Set<String> touchedCollections = ConcurrentHashMap.newKeySet();
        /** Remote owners forwarded to since stream start, keyed host:port. */
        final Map<String, ShardRouter.Route> remoteOwners = new ConcurrentHashMap<>();
    }

    @Override
    public Multi<BulkIndexResponse> bulkIndex(Multi<BulkIndexRequest> requests) {
        BulkSession session = new BulkSession();

        // The proto mandates the server sends the initial credit grant first,
        // regardless of whether the client opens with BulkOptions.
        Multi<BulkIndexResponse> initialGrant = Multi.createFrom().item(
                BulkIndexResponse.newBuilder()
                        .setFlowControl(FlowControl.newBuilder()
                                .setState(FlowControl.State.STATE_READY)
                                .setWindow(INITIAL_WINDOW)
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
                session.defaultCollection = request.getOptions().getCollection();
                // The credit grant was already sent unconditionally; options
                // carry no dedicated acknowledgement frame.
                yield Multi.createFrom().empty();
            }
            case DOCUMENT -> handleDocument(session, request.getDocument()).toMulti();
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
        if (docReq.hasTypedDocument()) {
            // Do not ack OK for input the server would discard: reflective
            // unpacking needs the collection's persisted descriptor set, which
            // lands with the document-centric ingest work.
            return Uni.createFrom().item(nack(seq, docReq.getDocId(), -1, CODE_UNIMPLEMENTED,
                    "typed_document ingest is not implemented yet; send 'fields'"));
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
            Document doc = new Document();
            doc.add(new StringField("doc_id", docId, Field.Store.YES));

            Set<String> vectorFields = new HashSet<>();
            for (DocumentField df : docReq.getFieldsList()) {
                if ("doc_id".equals(df.getName())) {
                    // A second indexed doc_id term would make upsert-by-id
                    // delete an unrelated document.
                    return nack(seq, docId, shardId, CODE_INVALID_ARGUMENT,
                            "'doc_id' is a reserved field name; set IndexDocument.doc_id instead");
                }
                addFieldValues(doc, df, config, vectorFields);
            }

            IndexWriter writer = collectionManager.getWriter(collectionName, shardId);
            writer.updateDocument(new Term("doc_id", docId), doc);
            session.touchedCollections.add(collectionName);

            return ack(seq, docId, shardId);
        } catch (InvalidDocumentException e) {
            return nack(seq, docId, shardId, CODE_INVALID_ARGUMENT, e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Bulk index doc %s failed", docId);
            return nack(seq, docId, shardId, CODE_INTERNAL, safeMessage(e));
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
