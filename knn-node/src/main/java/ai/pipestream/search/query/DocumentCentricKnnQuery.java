package ai.pipestream.search.query;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Compiler marker for a document-centric knn clause. Not directly
 * executable: the shard execution layer replaces it with a
 * DiversifyingChildren block-join query built against the shard's parent
 * bitset (the compiler is reader-free by design and cannot construct one).
 *
 * @see ai.pipestream.search.query.HybridExecutor#executeDocumentCentric
 */
public final class DocumentCentricKnnQuery extends Query {

    private final String field;
    private final float[] target;
    private final int k;
    private final int luceneK;
    private final Query filter;

    public DocumentCentricKnnQuery(String field, float[] target, int k, int luceneK, Query filter) {
        this.field = field;
        this.target = target;
        this.k = k;
        this.luceneK = luceneK;
        this.filter = filter;
    }

    public String field() {
        return field;
    }

    public float[] target() {
        return target;
    }

    /** Requested top-k documents. */
    public int k() {
        return k;
    }

    /** Candidate pool (num_candidates, or k when unset). */
    public int luceneK() {
        return luceneK;
    }

    /** Compiled pre-filter over parent-scope fields, or null. */
    public Query filter() {
        return filter;
    }

    @Override
    public String toString(String defaultField) {
        return "DocumentCentricKnnQuery[field=" + field + ", k=" + k
                + ", luceneK=" + luceneK + ", filter=" + filter + "]";
    }

    @Override
    public void visit(QueryVisitor visitor) {
        if (visitor.acceptField(field)) {
            visitor.visitLeaf(this);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        DocumentCentricKnnQuery that = (DocumentCentricKnnQuery) other;
        return k == that.k && luceneK == that.luceneK
                && field.equals(that.field)
                && Arrays.equals(target, that.target)
                && Objects.equals(filter, that.filter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, k, luceneK, Arrays.hashCode(target), filter);
    }
}
