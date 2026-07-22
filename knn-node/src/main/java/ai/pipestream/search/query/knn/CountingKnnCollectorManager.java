package ai.pipestream.search.query.knn;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.knn.KnnCollectorManager;
import org.apache.lucene.search.knn.KnnSearchStrategy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a collector manager to aggregate real visit counts per query — the
 * number Summary.visited / ShardSummary.visited must report instead of a
 * fabricated hit count. Each collector's visited total is added when its
 * TopDocs are drained (once, at the end of its leaf search).
 */
public final class CountingKnnCollectorManager implements KnnCollectorManager {

    private final KnnCollectorManager delegate;
    private final AtomicLong visited;

    public CountingKnnCollectorManager(KnnCollectorManager delegate, AtomicLong visited) {
        this.delegate = delegate;
        this.visited = visited;
    }

    @Override
    public KnnCollector newCollector(int visitLimit, KnnSearchStrategy searchStrategy,
                                     LeafReaderContext context) throws IOException {
        KnnCollector inner = delegate.newCollector(visitLimit, searchStrategy, context);
        return new KnnCollector.Decorator(inner) {
            private boolean counted;

            @Override
            public TopDocs topDocs() {
                if (!counted) {
                    counted = true;
                    visited.addAndGet(visitedCount());
                }
                return super.topDocs();
            }
        };
    }
}
