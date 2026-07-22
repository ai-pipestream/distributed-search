package ai.pipestream.search.node;

import ai.pipestream.search.index.CollectionConfig;
import ai.pipestream.search.index.CollectionManager;
import ai.pipestream.search.v1alpha1.*;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * gRPC IndexService implementation for v1alpha1 frame-based bulk indexing, get, and delete.
 */
@Singleton
@GrpcService
public class V1Alpha1IndexNodeService implements IndexService {

    private static final Logger LOG = Logger.getLogger(V1Alpha1IndexNodeService.class);

    @Inject
    CollectionManager collectionManager;

    @Override
    public Multi<BulkIndexResponse> bulkIndex(Multi<BulkIndexRequest> requests) {
        AtomicReference<String> defaultCollection = new AtomicReference<>("");

        return requests.onItem().transformToUniAndConcatenate(request -> Uni.createFrom().item(() -> {
            switch (request.getFrameCase()) {
                case OPTIONS -> {
                    defaultCollection.set(request.getOptions().getCollection());
                    return BulkIndexResponse.newBuilder()
                            .setFlowControl(FlowControl.newBuilder()
                                    .setState(FlowControl.State.STATE_READY)
                                    .setWindow(1000)
                                    .setDetail("Session initialized")
                                    .build())
                            .build();
                }
                case DOCUMENT -> {
                    IndexDocument docReq = request.getDocument();
                    String collectionName = docReq.getCollection().isEmpty()
                            ? defaultCollection.get() : docReq.getCollection();

                    if (collectionName.isEmpty()) {
                        return BulkIndexResponse.newBuilder()
                                .setAck(DocAck.newBuilder()
                                        .setClientSeq(docReq.getClientSeq())
                                        .setStatus(com.google.rpc.Status.newBuilder()
                                                .setCode(3)
                                                .setMessage("No collection specified")
                                                .build())
                                        .build())
                                .build();
                    }

                    CollectionConfig config = collectionManager.getConfig(collectionName);
                    if (config == null) {
                        return BulkIndexResponse.newBuilder()
                                .setAck(DocAck.newBuilder()
                                        .setClientSeq(docReq.getClientSeq())
                                        .setStatus(com.google.rpc.Status.newBuilder()
                                                .setCode(5)
                                                .setMessage("Collection not found: " + collectionName)
                                                .build())
                                        .build())
                                .build();
                    }

                    String docId = docReq.getDocId().isEmpty()
                            ? UUID.randomUUID().toString() : docReq.getDocId();
                    int shardId = collectionManager.routeToShard(docId, config.numShards());

                    try {
                        Document doc = new Document();
                        doc.add(new StringField("doc_id", docId, Field.Store.YES));

                        for (DocumentField df : docReq.getFieldsList()) {
                            if ("vector".equals(df.getName()) && df.getValuesCount() > 0) {
                                Vector v = df.getValues(0).getVectorValue();
                                float[] arr = new float[v.getValuesCount()];
                                for (int i = 0; i < v.getValuesCount(); i++) {
                                    arr[i] = v.getValues(i);
                                }
                                doc.add(new KnnFloatVectorField("vector", arr, config.similarity()));
                            } else if (df.getValuesCount() > 0) {
                                String strVal = df.getValues(0).getStringValue();
                                doc.add(new StringField(df.getName(), strVal, Field.Store.YES));
                            }
                        }

                        IndexWriter writer = collectionManager.getWriter(collectionName, shardId);
                        writer.updateDocument(new Term("doc_id", docId), doc);

                        return BulkIndexResponse.newBuilder()
                                .setAck(DocAck.newBuilder()
                                        .setClientSeq(docReq.getClientSeq())
                                        .setDocId(docId)
                                        .setShardId(shardId)
                                        .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
                                        .build())
                                .build();

                    } catch (Exception e) {
                        LOG.errorf(e, "Bulk index doc %s failed", docId);
                        return BulkIndexResponse.newBuilder()
                                .setAck(DocAck.newBuilder()
                                        .setClientSeq(docReq.getClientSeq())
                                        .setDocId(docId)
                                        .setShardId(shardId)
                                        .setStatus(com.google.rpc.Status.newBuilder()
                                                .setCode(13)
                                                .setMessage(e.getMessage())
                                                .build())
                                        .build())
                                .build();
                    }
                }
                case FLUSH -> {
                    FlushMarker flush = request.getFlush();
                    collectionManager.periodicCommit();
                    return BulkIndexResponse.newBuilder()
                            .setFlushAck(FlushAck.newBuilder().setThroughSeq(flush.getClientSeq()).build())
                            .build();
                }
                case FRAME_NOT_SET -> {
                    return BulkIndexResponse.newBuilder().build();
                }
            }
            return BulkIndexResponse.newBuilder().build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    }

    @Override
    public Uni<GetDocumentResponse> getDocument(GetDocumentRequest request) {
        return Uni.createFrom().item(() -> {
            CollectionConfig config = collectionManager.getConfig(request.getCollection());
            if (config == null) {
                return GetDocumentResponse.newBuilder().setFound(false).build();
            }

            int shardId = collectionManager.routeToShard(request.getDocId(), config.numShards());
            DirectoryReader reader = null;
            try {
                reader = collectionManager.getReader(request.getCollection(), shardId);
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(new TermQuery(new Term("doc_id", request.getDocId())), 1);

                if (topDocs.scoreDocs.length == 0) {
                    return GetDocumentResponse.newBuilder().setFound(false).build();
                }

                Document doc = reader.storedFields().document(topDocs.scoreDocs[0].doc);
                GetDocumentResponse.Builder resp = GetDocumentResponse.newBuilder()
                        .setFound(true)
                        .setDocId(request.getDocId());

                doc.forEach(field -> resp.addFields(DocumentField.newBuilder()
                        .setName(field.name())
                        .addValues(FieldValue.newBuilder().setStringValue(field.stringValue()).build())
                        .build()));

                return resp.build();

            } catch (IOException e) {
                LOG.errorf(e, "Get document %s failed", request.getDocId());
                return GetDocumentResponse.newBuilder().setFound(false).build();
            } finally {
                if (reader != null) {
                    try { collectionManager.releaseReader(reader); } catch (IOException ignored) {}
                }
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<DeleteDocumentResponse> deleteDocument(DeleteDocumentRequest request) {
        return Uni.createFrom().item(() -> {
            CollectionConfig config = collectionManager.getConfig(request.getCollection());
            if (config == null) {
                return DeleteDocumentResponse.newBuilder().setFound(false).build();
            }

            int shardId = collectionManager.routeToShard(request.getDocId(), config.numShards());
            try {
                DirectoryReader reader = collectionManager.getReader(request.getCollection(), shardId);
                boolean found;
                try {
                    found = new IndexSearcher(reader).count(new TermQuery(new Term("doc_id", request.getDocId()))) > 0;
                } finally {
                    collectionManager.releaseReader(reader);
                }

                IndexWriter writer = collectionManager.getWriter(request.getCollection(), shardId);
                writer.deleteDocuments(new Term("doc_id", request.getDocId()));
                return DeleteDocumentResponse.newBuilder().setFound(found).build();
            } catch (Exception e) {
                LOG.errorf(e, "Delete document %s failed", request.getDocId());
                return DeleteDocumentResponse.newBuilder().setFound(false).build();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
