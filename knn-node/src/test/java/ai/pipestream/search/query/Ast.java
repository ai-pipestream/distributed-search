package ai.pipestream.search.query;

import ai.pipestream.search.v1alpha1.*;
import com.google.protobuf.Timestamp;

import java.time.Instant;

/**
 * Terse builders for v1alpha1 Query AST nodes used by the compiler tests.
 */
final class Ast {

    private Ast() {}

    // -- FieldValue ---------------------------------------------------------

    static FieldValue str(String value) {
        return FieldValue.newBuilder().setStringValue(value).build();
    }

    static FieldValue int64(long value) {
        return FieldValue.newBuilder().setInt64Value(value).build();
    }

    static FieldValue dbl(double value) {
        return FieldValue.newBuilder().setDoubleValue(value).build();
    }

    static FieldValue flag(boolean value) {
        return FieldValue.newBuilder().setBoolValue(value).build();
    }

    static FieldValue ts(String isoInstant) {
        Instant instant = Instant.parse(isoInstant);
        return FieldValue.newBuilder()
                .setTimestampValue(Timestamp.newBuilder()
                        .setSeconds(instant.getEpochSecond())
                        .setNanos(instant.getNano()))
                .build();
    }

    // -- Query nodes --------------------------------------------------------

    static Query term(String field, FieldValue value) {
        return Query.newBuilder()
                .setTerm(TermQuery.newBuilder().setField(field).setValue(value))
                .build();
    }

    static Query match(String field, String text) {
        return match(field, text, Operator.OPERATOR_UNSPECIFIED);
    }

    static Query match(String field, String text, Operator operator) {
        return Query.newBuilder()
                .setMatch(MatchQuery.newBuilder().setField(field).setText(text).setOperator(operator))
                .build();
    }

    static Query matchMsm(String field, String text, int minimumShouldMatch) {
        return Query.newBuilder()
                .setMatch(MatchQuery.newBuilder()
                        .setField(field).setText(text).setMinimumShouldMatch(minimumShouldMatch))
                .build();
    }

    static Query matchWithAnalyzer(String field, String text, String analyzer) {
        return Query.newBuilder()
                .setMatch(MatchQuery.newBuilder().setField(field).setText(text).setAnalyzer(analyzer))
                .build();
    }

    static Query phrase(String field, String text, int slop) {
        return Query.newBuilder()
                .setPhrase(PhraseQuery.newBuilder().setField(field).setText(text).setSlop(slop))
                .build();
    }

    /** Range query; pass null for unbounded ends. */
    static Query range(String field, FieldValue gt, FieldValue gte, FieldValue lt, FieldValue lte) {
        RangeQuery.Builder builder = RangeQuery.newBuilder().setField(field);
        if (gt != null) {
            builder.setGt(gt);
        }
        if (gte != null) {
            builder.setGte(gte);
        }
        if (lt != null) {
            builder.setLt(lt);
        }
        if (lte != null) {
            builder.setLte(lte);
        }
        return Query.newBuilder().setRange(builder).build();
    }

    static Query bool(BoolQuery boolQuery) {
        return Query.newBuilder().setBool(boolQuery).build();
    }

    static KnnQuery.Builder knnBuilder(String field, int k, float... vector) {
        Vector.Builder target = Vector.newBuilder();
        for (float value : vector) {
            target.addValues(value);
        }
        return KnnQuery.newBuilder().setField(field).setK(k).setVector(target);
    }

    static Query knn(KnnQuery.Builder knnQuery) {
        return Query.newBuilder().setKnn(knnQuery).build();
    }

    static Query hybrid(Fusion fusion, Query... subQueries) {
        HybridQuery.Builder builder = HybridQuery.newBuilder();
        for (Query subQuery : subQueries) {
            builder.addQueries(subQuery);
        }
        if (fusion != null) {
            builder.setFusion(fusion);
        }
        return Query.newBuilder().setHybrid(builder).build();
    }

    static Fusion rrf(int k) {
        return Fusion.newBuilder().setRrf(RrfFusion.newBuilder().setK(k)).build();
    }

    static Fusion linear(float... weights) {
        LinearFusion.Builder builder = LinearFusion.newBuilder();
        for (float weight : weights) {
            builder.addWeights(weight);
        }
        return Fusion.newBuilder().setLinear(builder).build();
    }

    static Query matchAll() {
        return Query.newBuilder().setMatchAll(MatchAllQuery.getDefaultInstance()).build();
    }

    static Query queryString(String query, String defaultField, Operator defaultOperator) {
        return Query.newBuilder()
                .setQueryString(QueryStringQuery.newBuilder()
                        .setQuery(query).setDefaultField(defaultField).setDefaultOperator(defaultOperator))
                .build();
    }

    static Query boosted(Query query, float boost) {
        return query.toBuilder().setBoost(boost).build();
    }
}
