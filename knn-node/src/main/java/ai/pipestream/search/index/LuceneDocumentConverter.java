package ai.pipestream.search.index;

import ai.pipestream.index.v1.IndexDocumentRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field;

import java.util.Map;

/**
 * Maps a gRPC IndexDocumentRequest + resolved vector to a Lucene Document.
 * CDI-managed so it can be extended with injected config or services later.
 */
@ApplicationScoped
public class LuceneDocumentConverter {

    /**
     * Convert a gRPC index request into a Lucene Document ready for IndexWriter.
     *
     * @param request the gRPC request containing doc_id, chunk_text, metadata
     * @param vector  the resolved embedding vector (from request or DJL)
     * @param config  collection config (provides similarity function)
     * @return a Lucene Document with vector, stored fields, and indexed doc_id
     */
    public Document convert(IndexDocumentRequest request, float[] vector, CollectionConfig config) {
        Document doc = new Document();

        // KNN vector field for similarity search
        doc.add(new KnnFloatVectorField("vector", vector, config.similarity()));

        // Document ID: stored for retrieval + indexed as keyword for delete-by-id
        doc.add(new StoredField("doc_id", request.getDocId()));
        doc.add(new StringField("doc_id", request.getDocId(), Field.Store.NO));

        // Chunk text for retrieval display
        String chunk = request.getChunkText();
        if (chunk.isEmpty()) {
            // Fall back to the text content if chunk_text wasn't explicitly provided
            if (request.hasText()) {
                chunk = request.getText();
            } else if (request.hasTextAndVector()) {
                chunk = request.getTextAndVector().getText();
            }
        }
        if (!chunk.isEmpty()) {
            doc.add(new StoredField("chunk", chunk));
        }

        // Metadata fields — all stored with "meta_" prefix
        for (Map.Entry<String, String> entry : request.getMetadataMap().entrySet()) {
            doc.add(new StoredField("meta_" + entry.getKey(), entry.getValue()));
        }

        return doc;
    }
}
