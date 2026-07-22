package ai.pipestream.search.index.doc;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * Engine-reserved field names for document-centric (block-join) collections,
 * plus the two queries every write and read path must agree on.
 *
 * <p>Block layout invariants (enforced by {@code BlockJoinDocumentBuilder}):
 * <ul>
 *   <li>{@code doc_id} and {@code _gen} are indexed on EVERY member of the
 *       block (children and stub), so a single term/range query removes the
 *       whole block atomically.</li>
 *   <li>The parent stub is written LAST in the block and carries no vector:
 *       Lucene's block-join collectors resolve parents by forward-scanning
 *       the parent bitset.</li>
 * </ul>
 */
public final class BlockJoinFields {

    /** Client document id, indexed on every block member. */
    public static final String DOC_ID = "doc_id";
    /** Indexed marker term present only on parent stubs. */
    public static final String PARENT_MARKER = "_is_parent";
    public static final String PARENT_VALUE = "T";
    /** Write generation; LongPoint + doc value on every block member. */
    public static final String GENERATION = "_gen";
    /** Client (or server-assigned) chunk id, stored on children. */
    public static final String CHUNK_ID = "chunk_id";
    /** 0-based chunk ordinal within the parent. */
    public static final String CHUNK_ORD = "_chunk_ord";
    /** Char offsets of the chunk in the parent's chunk-source text. */
    public static final String CHUNK_START = "_chunk_start";
    public static final String CHUNK_END = "_chunk_end";
    /** Total chunk count across all shards, stored on the stub. */
    public static final String CHUNK_COUNT = "_chunk_count";
    /** Opt-in stored chunk text (mode A, store_chunk_text). */
    public static final String CHUNK_TEXT = "_chunk_text";
    /** Raw Chunk.payload Any bytes, stored on children for retrieval. */
    public static final String CHUNK_PAYLOAD = "_chunk_payload";
    /** Serialized NlpSpans for the chunk (mode A, nlp_layers), stored on children. */
    public static final String CHUNK_NLP = "_chunk_nlp";
    /** Raw parent payload Any bytes, stored on the stub for retrieval. */
    public static final String PARENT_PAYLOAD = "_payload";

    /** Matches exactly the parent stubs. */
    public static final Query PARENT_QUERY =
            new TermQuery(new Term(PARENT_MARKER, PARENT_VALUE));

    private BlockJoinFields() {
    }

    /**
     * Everything belonging to {@code docId} with a generation strictly below
     * {@code generation} ({@code <= 0} = all generations). Order-independent
     * and replay-safe: a purge for generation g arriving after g+1 was
     * written cannot delete the g+1 block.
     */
    public static Query purgeQuery(String docId, long generation) {
        long upperExclusive = generation <= 0 ? Long.MAX_VALUE : generation - 1;
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term(DOC_ID, docId)), BooleanClause.Occur.MUST)
                .add(LongPoint.newRangeQuery(GENERATION, Long.MIN_VALUE, upperExclusive),
                        BooleanClause.Occur.MUST)
                .build();
    }

    /** Matches this parent's block members at exactly {@code generation}. */
    public static Query generationQuery(String docId, long generation) {
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term(DOC_ID, docId)), BooleanClause.Occur.MUST)
                .add(LongPoint.newRangeQuery(GENERATION, generation, Long.MAX_VALUE),
                        BooleanClause.Occur.MUST)
                .build();
    }

    /**
     * Restricts a compiled user filter to child documents. The filter clause
     * scores nothing; parent stubs are excluded so a stub can never surface
     * as a chunk match.
     */
    public static Query childFilter(Query userFilter) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder()
                .add(PARENT_QUERY, BooleanClause.Occur.MUST_NOT);
        if (userFilter != null) {
            builder.add(userFilter, BooleanClause.Occur.FILTER);
        } else {
            builder.add(org.apache.lucene.search.MatchAllDocsQuery.INSTANCE,
                    BooleanClause.Occur.FILTER);
        }
        return builder.build();
    }
}
