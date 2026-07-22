package ai.pipestream.search.index.doc;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles one shard-local Lucene block: chunk children in ordinal order,
 * parent stub LAST, {@code doc_id} and {@code _gen} indexed on every member.
 *
 * <p>The stub must be last because Lucene's block-join collectors resolve a
 * child's parent via {@code parentBitSet.nextSetBit(childDoc)} — a leading
 * stub would mis-attribute every chunk to the NEXT document's stub, and the
 * guarding assert is disabled in production.
 */
public final class BlockJoinDocumentBuilder {

    private BlockJoinDocumentBuilder() {
    }

    /**
     * @param docId           parent document id (required)
     * @param generation      write generation (> 0)
     * @param stub            projected parent stub (content fields only)
     * @param children        projected chunk children, ordinal order
     * @param totalChunkCount chunks across ALL shards of this parent
     */
    public static List<Document> build(String docId, long generation,
                                       Document stub, List<Document> children,
                                       int totalChunkCount) {
        if (docId == null || docId.isEmpty()) {
            throw new IllegalArgumentException("docId is required");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive, got " + generation);
        }
        if (children.isEmpty()) {
            throw new IllegalArgumentException("a block needs at least one chunk child");
        }

        List<Document> block = new ArrayList<>(children.size() + 1);
        for (Document child : children) {
            addIdentity(child, docId, generation);
            block.add(child);
        }

        stub.add(new StringField(BlockJoinFields.PARENT_MARKER, BlockJoinFields.PARENT_VALUE,
                Field.Store.NO));
        stub.add(new StoredField(BlockJoinFields.CHUNK_COUNT, totalChunkCount));
        addIdentity(stub, docId, generation);
        block.add(stub);   // stub LAST — see class javadoc

        return block;
    }

    private static void addIdentity(Document doc, String docId, long generation) {
        doc.add(new StringField(BlockJoinFields.DOC_ID, docId, Field.Store.YES));
        doc.add(new LongPoint(BlockJoinFields.GENERATION, generation));
        doc.add(new NumericDocValuesField(BlockJoinFields.GENERATION, generation));
    }
}
