package ai.pipestream.search.index.doc;

import ai.pipestream.search.index.CollectionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;

import java.io.IOException;
import java.util.List;

/**
 * Shard-local block writes for document-centric collections. All mutations go
 * through generation-bounded purge queries, so replacement is atomic per
 * shard and replay-safe under reordering.
 */
@ApplicationScoped
public class BlockWriter {

    /** The parent already has this generation (or a newer one). */
    public static class StaleGenerationException extends RuntimeException {
        public StaleGenerationException(String message) {
            super(message);
        }
    }

    public record BlockWriteResult(int chunkCount, int purgedDocs) {}

    @Inject
    CollectionManager collectionManager;

    /**
     * Atomically replaces this parent's block on one shard:
     * {@code updateDocuments(purgeQuery(docId, generation), block)} removes
     * every older-generation member and adds the new block in one operation.
     *
     * @throws StaleGenerationException when the shard already holds this
     *         generation or a newer one (idempotent replay / reorder)
     */
    public BlockWriteResult writeBlock(String collection, int shardId, String docId,
                                       long generation, List<Document> block) throws IOException {
        long last = lastGeneration(collection, shardId, docId);
        if (last >= generation) {
            throw new StaleGenerationException("Parent '" + docId + "' on shard " + shardId
                    + " already has generation " + last + " >= " + generation);
        }
        int purged = countMatching(collection, shardId,
                BlockJoinFields.purgeQuery(docId, generation));

        IndexWriter writer = collectionManager.getWriter(collection, shardId);
        writer.updateDocuments(BlockJoinFields.purgeQuery(docId, generation), block);
        return new BlockWriteResult(block.size() - 1, purged);
    }

    /**
     * Removes this parent's members below {@code belowGeneration}
     * ({@code <= 0} = all generations). Idempotent.
     *
     * @return documents that matched before the delete
     */
    public int purgeParent(String collection, int shardId, String docId,
                           long belowGeneration) throws IOException {
        int matched = countMatching(collection, shardId,
                BlockJoinFields.purgeQuery(docId, belowGeneration));
        if (matched > 0) {
            IndexWriter writer = collectionManager.getWriter(collection, shardId);
            writer.deleteDocuments(BlockJoinFields.purgeQuery(docId, belowGeneration));
        }
        return matched;
    }

    /** Highest generation this shard holds for the parent; 0 when absent. */
    public long lastGeneration(String collection, int shardId, String docId) throws IOException {
        DirectoryReader reader;
        try {
            reader = collectionManager.getReader(collection, shardId);
        } catch (IndexNotFoundException e) {
            return 0;
        }
        try {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopFieldDocs top = searcher.search(
                    new TermQuery(new Term(BlockJoinFields.DOC_ID, docId)), 1,
                    new Sort(new SortField(BlockJoinFields.GENERATION, SortField.Type.LONG, true)));
            if (top.scoreDocs.length == 0) {
                return 0;
            }
            Object value = ((org.apache.lucene.search.FieldDoc) top.scoreDocs[0]).fields[0];
            return value instanceof Long l ? l : 0;
        } finally {
            collectionManager.releaseReader(reader);
        }
    }

    private int countMatching(String collection, int shardId,
                              org.apache.lucene.search.Query query) throws IOException {
        DirectoryReader reader;
        try {
            reader = collectionManager.getReader(collection, shardId);
        } catch (IndexNotFoundException e) {
            return 0;
        }
        try {
            return new IndexSearcher(reader).count(query);
        } finally {
            collectionManager.releaseReader(reader);
        }
    }
}
