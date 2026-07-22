package ai.pipestream.search.index.protomolt;

import ai.pipestream.proto.index.spi.IndexerContext;
import ai.pipestream.proto.index.spi.SearchEngineIndexer;
import ai.pipestream.proto.index.spi.SearchEngineIndexerProvider;

/**
 * ServiceLoader registration for {@link BlockJoinLuceneMapper}.
 *
 * <p>The engine id is {@code distributed-lucene}, never {@code lucene}:
 * {@code SearchEngineIndexers.loadProviders()} resolves id collisions by
 * silent last-wins, so reusing the stock id would make classpath order decide
 * which mapper answers.
 */
public final class BlockJoinLuceneMapperProvider implements SearchEngineIndexerProvider {

    @Override
    public String engineId() {
        return BlockJoinLuceneMapper.ENGINE_ID;
    }

    @Override
    public SearchEngineIndexer create(IndexerContext context) {
        return new BlockJoinLuceneMapper(context.fieldMapper());
    }
}
