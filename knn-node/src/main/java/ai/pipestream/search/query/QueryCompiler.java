package ai.pipestream.search.query;

import ai.pipestream.search.v1alpha1.BoolQuery;
import ai.pipestream.search.v1alpha1.CollectionSchema;
import ai.pipestream.search.v1alpha1.FieldSchema;
import ai.pipestream.search.v1alpha1.FieldValue;
import ai.pipestream.search.v1alpha1.Fusion;
import ai.pipestream.search.v1alpha1.HybridQuery;
import ai.pipestream.search.v1alpha1.KnnQuery;
import ai.pipestream.search.v1alpha1.MatchQuery;
import ai.pipestream.search.v1alpha1.Operator;
import ai.pipestream.search.v1alpha1.QueryStringQuery;
import ai.pipestream.search.v1alpha1.RangeQuery;
import com.google.protobuf.Timestamp;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles the v1alpha1 Query AST into a {@link QueryPlan} of executable
 * Lucene queries, resolving field types from the CollectionSchema.
 *
 * <p>Index encoding contract (the write path must index fields the same way):
 * <ul>
 *   <li><b>keyword</b>: unanalyzed StringField terms.</li>
 *   <li><b>numeric</b>: INT32/INT64 as {@link LongPoint}, FLOAT/DOUBLE as
 *       {@link DoublePoint} (two point widths keep the compiler and the
 *       document converter trivially aligned).</li>
 *   <li><b>date</b>: epoch milliseconds UTC in a {@link LongPoint}. Query
 *       operands accept {@code timestamp_value} or {@code int64_value}
 *       (interpreted as epoch millis).</li>
 *   <li><b>boolean</b>: single-character keyword term, {@link #BOOL_TRUE_TERM}
 *       ("T") / {@link #BOOL_FALSE_TERM} ("F").</li>
 *   <li><b>dense_vector</b>: KnnFloatVectorField.</li>
 * </ul>
 *
 * <p>knn clauses compile to {@link KnnFloatVectorQuery} with
 * {@code num_candidates} (or {@code k} when unset) as the Lucene k and the
 * compiled pre-filter attached; trimming to the requested top-k happens at
 * execution time. The {@code collaborative} flag and {@code visit_budget}
 * ride along on the plan as {@link QueryPlan.KnnHints} — this class never
 * instantiates collectors.
 */
@ApplicationScoped
public class QueryCompiler {

    /** Indexed term for boolean {@code true} (see class Javadoc). */
    public static final String BOOL_TRUE_TERM = "T";

    /** Indexed term for boolean {@code false} (see class Javadoc). */
    public static final String BOOL_FALSE_TERM = "F";

    /** Default RRF rank constant when the AST leaves it unset (0). */
    public static final int DEFAULT_RRF_K = 60;

    private final AnalyzerRegistry analyzers;

    public QueryCompiler(AnalyzerRegistry analyzers) {
        this.analyzers = analyzers;
    }

    /**
     * Compile a query tree against a collection schema.
     *
     * @throws InvalidQueryException if the tree references unknown fields,
     *                               mismatches field types, or uses constructs
     *                               the compiler does not support
     */
    public QueryPlan compile(ai.pipestream.search.v1alpha1.Query query, CollectionSchema schema) {
        if (query.getKindCase() == ai.pipestream.search.v1alpha1.Query.KindCase.HYBRID) {
            if (query.hasBoost()) {
                throw new InvalidQueryException(
                        "boost is not supported on hybrid queries; boost the sub-queries or use linear fusion weights");
            }
            return compileHybrid(query.getHybrid(), schema);
        }
        Compilation compilation = new Compilation(schema);
        Query lucene = compilation.compileNode(query);
        return new QueryPlan.Single(lucene, compilation.knnHints);
    }

    private QueryPlan.Hybrid compileHybrid(HybridQuery hybrid, CollectionSchema schema) {
        if (hybrid.getQueriesCount() == 0) {
            throw new InvalidQueryException("hybrid query has no sub-queries");
        }
        List<QueryPlan> subPlans = new ArrayList<>(hybrid.getQueriesCount());
        for (ai.pipestream.search.v1alpha1.Query subQuery : hybrid.getQueriesList()) {
            subPlans.add(compile(subQuery, schema));
        }
        return new QueryPlan.Hybrid(subPlans, compileFusion(hybrid.getFusion(), subPlans.size()));
    }

    private static QueryPlan.FusionSpec compileFusion(Fusion fusion, int subQueryCount) {
        return switch (fusion.getMethodCase()) {
            case RRF -> {
                int k = fusion.getRrf().getK();
                if (k < 0) {
                    throw new InvalidQueryException("rrf.k must be >= 0, got " + k);
                }
                yield new QueryPlan.FusionSpec.Rrf(k == 0 ? DEFAULT_RRF_K : k);
            }
            case LINEAR -> {
                List<Float> weights = fusion.getLinear().getWeightsList();
                if (weights.size() != subQueryCount) {
                    throw new InvalidQueryException("linear fusion has " + weights.size()
                            + " weights for " + subQueryCount + " sub-queries; lengths must match");
                }
                yield new QueryPlan.FusionSpec.Linear(weights);
            }
            // Unset fusion falls back to the server default: RRF with k=60.
            case METHOD_NOT_SET -> new QueryPlan.FusionSpec.Rrf(DEFAULT_RRF_K);
        };
    }

    /** One compile pass: field lookup plus the knn hints collected on the way. */
    private final class Compilation {

        private final Map<String, FieldSchema> fields = new LinkedHashMap<>();
        private final List<QueryPlan.KnnHints> knnHints = new ArrayList<>();
        private boolean insideKnnFilter;

        Compilation(CollectionSchema schema) {
            for (FieldSchema field : schema.getFieldsList()) {
                fields.put(field.getName(), field);
            }
        }

        Query compileNode(ai.pipestream.search.v1alpha1.Query query) {
            Query compiled = switch (query.getKindCase()) {
                case BOOL -> compileBool(query.getBool());
                case TERM -> compileTerm(query.getTerm());
                case MATCH -> compileMatch(query.getMatch());
                case PHRASE -> compilePhrase(query.getPhrase());
                case RANGE -> compileRange(query.getRange());
                case KNN -> compileKnn(query.getKnn());
                case HYBRID -> throw new InvalidQueryException(
                        "hybrid queries may only appear at the root or as direct sub-queries of another hybrid");
                case MATCH_ALL -> MatchAllDocsQuery.INSTANCE;
                case QUERY_STRING -> compileQueryString(query.getQueryString());
                case KIND_NOT_SET -> throw new InvalidQueryException("Query has no kind set");
            };
            if (query.hasBoost() && query.getBoost() != 1.0f) {
                if (query.getBoost() < 0) {
                    throw new InvalidQueryException("boost must be >= 0, got " + query.getBoost());
                }
                compiled = new BoostQuery(compiled, query.getBoost());
            }
            return compiled;
        }

        private Query compileBool(BoolQuery bool) {
            int clauseCount = bool.getMustCount() + bool.getShouldCount()
                    + bool.getMustNotCount() + bool.getFilterCount();
            if (clauseCount == 0) {
                throw new InvalidQueryException("bool query has no clauses");
            }
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (ai.pipestream.search.v1alpha1.Query clause : bool.getMustList()) {
                builder.add(compileNode(clause), Occur.MUST);
            }
            for (ai.pipestream.search.v1alpha1.Query clause : bool.getShouldList()) {
                builder.add(compileNode(clause), Occur.SHOULD);
            }
            for (ai.pipestream.search.v1alpha1.Query clause : bool.getMustNotList()) {
                builder.add(compileNode(clause), Occur.MUST_NOT);
            }
            for (ai.pipestream.search.v1alpha1.Query clause : bool.getFilterList()) {
                builder.add(compileNode(clause), Occur.FILTER);
            }
            // Lucene requires a positive clause; a pure-negation bool gets the
            // ES-style implicit match_all so "everything except X" just works.
            boolean hasPositive = bool.getMustCount() + bool.getShouldCount() + bool.getFilterCount() > 0;
            if (!hasPositive) {
                builder.add(MatchAllDocsQuery.INSTANCE, Occur.FILTER);
            }
            if (bool.hasMinimumShouldMatch()) {
                int msm = bool.getMinimumShouldMatch();
                if (msm < 0 || msm > bool.getShouldCount()) {
                    throw new InvalidQueryException("minimum_should_match=" + msm
                            + " is out of range for " + bool.getShouldCount() + " should clauses");
                }
                builder.setMinimumNumberShouldMatch(msm);
            }
            // When minimum_should_match is unset, Lucene's defaults already match
            // the proto contract: pure disjunctions require one should clause,
            // should clauses next to must/filter are optional.
            return builder.build();
        }

        private Query compileTerm(ai.pipestream.search.v1alpha1.TermQuery term) {
            FieldSchema field = requireField(term.getField(), "term");
            FieldValue value = term.getValue();
            return switch (field.getTypeCase()) {
                case KEYWORD -> new TermQuery(new Term(field.getName(), stringValue(value, field)));
                case NUMERIC -> isFloatingPoint(field)
                        ? DoublePoint.newExactQuery(field.getName(), doubleValue(value, field))
                        : LongPoint.newExactQuery(field.getName(), longValue(value, field));
                case DATE -> LongPoint.newExactQuery(field.getName(), epochMillis(value, field));
                case BOOLEAN -> new TermQuery(new Term(field.getName(), boolTerm(boolValue(value, field))));
                case TEXT -> throw new InvalidQueryException("term query cannot target text field '"
                        + field.getName() + "'; use match or phrase for analyzed text");
                case DENSE_VECTOR -> throw new InvalidQueryException(
                        "term query cannot target dense_vector field '" + field.getName() + "'");
                case TYPE_NOT_SET -> throw missingType(field);
            };
        }

        private Query compileMatch(MatchQuery match) {
            FieldSchema field = requireTextField(match.getField(), "match");
            boolean and = match.getOperator() == Operator.OPERATOR_AND;
            if (match.hasMinimumShouldMatch() && and) {
                throw new InvalidQueryException("match.minimum_should_match is only valid with OPERATOR_OR");
            }
            List<Token> tokens = tokenize(analyzerFor(field, match.getAnalyzer()), field.getName(), match.getText());
            if (tokens.isEmpty()) {
                return new MatchNoDocsQuery("match on '" + field.getName() + "' analyzed to zero tokens");
            }
            if (tokens.size() == 1) {
                return new TermQuery(new Term(field.getName(), tokens.get(0).term()));
            }
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            Occur occur = and ? Occur.MUST : Occur.SHOULD;
            for (Token token : tokens) {
                builder.add(new TermQuery(new Term(field.getName(), token.term())), occur);
            }
            if (match.hasMinimumShouldMatch()) {
                int msm = match.getMinimumShouldMatch();
                if (msm < 0 || msm > tokens.size()) {
                    throw new InvalidQueryException("match.minimum_should_match=" + msm
                            + " is out of range for " + tokens.size() + " analyzed terms");
                }
                builder.setMinimumNumberShouldMatch(msm);
            }
            return builder.build();
        }

        private Query compilePhrase(ai.pipestream.search.v1alpha1.PhraseQuery phrase) {
            FieldSchema field = requireTextField(phrase.getField(), "phrase");
            if (phrase.getSlop() < 0) {
                throw new InvalidQueryException("phrase.slop must be >= 0, got " + phrase.getSlop());
            }
            List<Token> tokens = tokenize(analyzerFor(field, phrase.getAnalyzer()), field.getName(), phrase.getText());
            if (tokens.isEmpty()) {
                return new MatchNoDocsQuery("phrase on '" + field.getName() + "' analyzed to zero tokens");
            }
            PhraseQuery.Builder builder = new PhraseQuery.Builder().setSlop(phrase.getSlop());
            for (Token token : tokens) {
                builder.add(new Term(field.getName(), token.term()), token.position());
            }
            return builder.build();
        }

        private Query compileRange(RangeQuery range) {
            FieldSchema field = requireField(range.getField(), "range");
            if (range.hasGt() && range.hasGte()) {
                throw new InvalidQueryException("range on '" + field.getName() + "' sets both gt and gte");
            }
            if (range.hasLt() && range.hasLte()) {
                throw new InvalidQueryException("range on '" + field.getName() + "' sets both lt and lte");
            }
            if (!range.hasGt() && !range.hasGte() && !range.hasLt() && !range.hasLte()) {
                throw new InvalidQueryException("range on '" + field.getName() + "' has no bounds");
            }
            return switch (field.getTypeCase()) {
                case NUMERIC -> isFloatingPoint(field) ? doubleRange(field, range) : longRange(field, range, false);
                case DATE -> longRange(field, range, true);
                case KEYWORD -> keywordRange(field, range);
                case TYPE_NOT_SET -> throw missingType(field);
                default -> throw new InvalidQueryException("range query supports numeric, date, and keyword fields; '"
                        + field.getName() + "' is " + typeName(field));
            };
        }

        private Query longRange(FieldSchema field, RangeQuery range, boolean isDate) {
            long lower = Long.MIN_VALUE;
            long upper = Long.MAX_VALUE;
            if (range.hasGte()) {
                lower = isDate ? epochMillis(range.getGte(), field) : longValue(range.getGte(), field);
            } else if (range.hasGt()) {
                long bound = isDate ? epochMillis(range.getGt(), field) : longValue(range.getGt(), field);
                if (bound == Long.MAX_VALUE) {
                    return new MatchNoDocsQuery("empty range on '" + field.getName() + "'");
                }
                lower = bound + 1;
            }
            if (range.hasLte()) {
                upper = isDate ? epochMillis(range.getLte(), field) : longValue(range.getLte(), field);
            } else if (range.hasLt()) {
                long bound = isDate ? epochMillis(range.getLt(), field) : longValue(range.getLt(), field);
                if (bound == Long.MIN_VALUE) {
                    return new MatchNoDocsQuery("empty range on '" + field.getName() + "'");
                }
                upper = bound - 1;
            }
            return LongPoint.newRangeQuery(field.getName(), lower, upper);
        }

        private Query doubleRange(FieldSchema field, RangeQuery range) {
            double lower = Double.NEGATIVE_INFINITY;
            double upper = Double.POSITIVE_INFINITY;
            if (range.hasGte()) {
                lower = doubleValue(range.getGte(), field);
            } else if (range.hasGt()) {
                lower = Math.nextUp(doubleValue(range.getGt(), field));
            }
            if (range.hasLte()) {
                upper = doubleValue(range.getLte(), field);
            } else if (range.hasLt()) {
                upper = Math.nextDown(doubleValue(range.getLt(), field));
            }
            return DoublePoint.newRangeQuery(field.getName(), lower, upper);
        }

        private Query keywordRange(FieldSchema field, RangeQuery range) {
            String lower = null;
            String upper = null;
            boolean includeLower = true;
            boolean includeUpper = true;
            if (range.hasGte()) {
                lower = stringValue(range.getGte(), field);
            } else if (range.hasGt()) {
                lower = stringValue(range.getGt(), field);
                includeLower = false;
            }
            if (range.hasLte()) {
                upper = stringValue(range.getLte(), field);
            } else if (range.hasLt()) {
                upper = stringValue(range.getLt(), field);
                includeUpper = false;
            }
            return TermRangeQuery.newStringRange(field.getName(), lower, upper, includeLower, includeUpper);
        }

        private Query compileKnn(KnnQuery knn) {
            if (insideKnnFilter) {
                throw new InvalidQueryException("knn queries cannot appear inside a knn pre-filter");
            }
            FieldSchema field = requireField(knn.getField(), "knn");
            if (field.getTypeCase() != FieldSchema.TypeCase.DENSE_VECTOR) {
                throw new InvalidQueryException("knn query requires a dense_vector field; '"
                        + field.getName() + "' is " + typeName(field));
            }
            int valueCount = knn.getVector().getValuesCount();
            if (valueCount == 0) {
                throw new InvalidQueryException("knn query on '" + field.getName() + "' has no query vector");
            }
            int dims = field.getDenseVector().getDims();
            if (dims > 0 && valueCount != dims) {
                throw new InvalidQueryException("knn query vector has " + valueCount
                        + " dimensions; field '" + field.getName() + "' expects " + dims);
            }
            if (knn.getK() <= 0) {
                throw new InvalidQueryException("knn.k must be positive, got " + knn.getK());
            }
            if (knn.getNumCandidates() < 0 || (knn.getNumCandidates() > 0 && knn.getNumCandidates() < knn.getK())) {
                throw new InvalidQueryException("knn.num_candidates must be 0 (server default) or >= k, got "
                        + knn.getNumCandidates());
            }
            Query filter = null;
            if (knn.hasFilter()) {
                insideKnnFilter = true;
                try {
                    filter = compileNode(knn.getFilter());
                } finally {
                    insideKnnFilter = false;
                }
            }
            float[] target = new float[valueCount];
            for (int i = 0; i < valueCount; i++) {
                target[i] = knn.getVector().getValues(i);
            }
            knnHints.add(new QueryPlan.KnnHints(field.getName(), knn.getCollaborative(), knn.getVisitBudget()));
            // num_candidates (ef_search) is the Lucene k: the query gathers that
            // many candidates per shard; execution trims to the requested top-k.
            int luceneK = knn.getNumCandidates() > 0 ? knn.getNumCandidates() : knn.getK();
            return new KnnFloatVectorQuery(field.getName(), target, luceneK, filter);
        }

        private Query compileQueryString(QueryStringQuery queryString) {
            if (queryString.getQuery().isEmpty()) {
                throw new InvalidQueryException("query_string.query is empty");
            }
            // The proto promises "collection default field" but v1alpha1 schemas
            // don't declare one; fall back to the first text field.
            FieldSchema defaultField = queryString.getDefaultField().isEmpty()
                    ? firstTextField()
                    : requireField(queryString.getDefaultField(), "query_string");
            Analyzer analyzer = defaultField.getTypeCase() == FieldSchema.TypeCase.TEXT
                    ? analyzers.resolve(defaultField.getText().getAnalyzer())
                    : analyzers.resolve(AnalyzerRegistry.DEFAULT_ANALYZER);
            QueryParser parser = new QueryParser(defaultField.getName(), analyzer);
            parser.setDefaultOperator(queryString.getDefaultOperator() == Operator.OPERATOR_AND
                    ? QueryParser.Operator.AND
                    : QueryParser.Operator.OR);
            try {
                return parser.parse(queryString.getQuery());
            } catch (ParseException e) {
                throw new InvalidQueryException("query_string failed to parse: " + e.getMessage(), e);
            }
        }

        private FieldSchema firstTextField() {
            return fields.values().stream()
                    .filter(field -> field.getTypeCase() == FieldSchema.TypeCase.TEXT)
                    .findFirst()
                    .orElseThrow(() -> new InvalidQueryException(
                            "query_string.default_field is empty and the schema has no text field to default to"));
        }

        private Analyzer analyzerFor(FieldSchema field, String override) {
            if (!override.isEmpty()) {
                return analyzers.resolve(override);
            }
            return analyzers.resolve(field.getText().getAnalyzer());
        }

        private FieldSchema requireField(String name, String queryKind) {
            if (name.isEmpty()) {
                throw new InvalidQueryException(queryKind + " query is missing a field name");
            }
            FieldSchema field = fields.get(name);
            if (field == null) {
                throw new InvalidQueryException("Unknown field '" + name + "'; schema fields: "
                        + String.join(", ", fields.keySet()));
            }
            return field;
        }

        private FieldSchema requireTextField(String name, String queryKind) {
            FieldSchema field = requireField(name, queryKind);
            if (field.getTypeCase() != FieldSchema.TypeCase.TEXT) {
                throw new InvalidQueryException(queryKind + " query requires a text field; '"
                        + name + "' is " + typeName(field));
            }
            return field;
        }
    }

    // -----------------------------------------------------------------------
    // Value coercion
    // -----------------------------------------------------------------------

    private static boolean isFloatingPoint(FieldSchema field) {
        return switch (field.getNumeric().getType()) {
            case NUMERIC_TYPE_FLOAT, NUMERIC_TYPE_DOUBLE -> true;
            // UNSPECIFIED defaults to INT64 per the proto.
            case NUMERIC_TYPE_UNSPECIFIED, NUMERIC_TYPE_INT32, NUMERIC_TYPE_INT64 -> false;
            case UNRECOGNIZED -> throw new InvalidQueryException(
                    "field '" + field.getName() + "' has an unrecognized numeric type");
        };
    }

    private static String boolTerm(boolean value) {
        return value ? BOOL_TRUE_TERM : BOOL_FALSE_TERM;
    }

    private static String stringValue(FieldValue value, FieldSchema field) {
        if (value.getKindCase() != FieldValue.KindCase.STRING_VALUE) {
            throw typeMismatch(field, "string_value", value);
        }
        return value.getStringValue();
    }

    private static boolean boolValue(FieldValue value, FieldSchema field) {
        if (value.getKindCase() != FieldValue.KindCase.BOOL_VALUE) {
            throw typeMismatch(field, "bool_value", value);
        }
        return value.getBoolValue();
    }

    private static long longValue(FieldValue value, FieldSchema field) {
        if (value.getKindCase() != FieldValue.KindCase.INT64_VALUE) {
            throw typeMismatch(field, "int64_value", value);
        }
        return value.getInt64Value();
    }

    /** Floating-point fields accept double_value, or int64_value widened. */
    private static double doubleValue(FieldValue value, FieldSchema field) {
        return switch (value.getKindCase()) {
            case DOUBLE_VALUE -> value.getDoubleValue();
            case INT64_VALUE -> value.getInt64Value();
            default -> throw typeMismatch(field, "double_value", value);
        };
    }

    /** Date fields accept timestamp_value, or int64_value as epoch millis. */
    private static long epochMillis(FieldValue value, FieldSchema field) {
        return switch (value.getKindCase()) {
            case TIMESTAMP_VALUE -> {
                Timestamp ts = value.getTimestampValue();
                yield Math.addExact(Math.multiplyExact(ts.getSeconds(), 1000L), ts.getNanos() / 1_000_000L);
            }
            case INT64_VALUE -> value.getInt64Value();
            default -> throw typeMismatch(field, "timestamp_value", value);
        };
    }

    private static InvalidQueryException typeMismatch(FieldSchema field, String expected, FieldValue actual) {
        return new InvalidQueryException("field '" + field.getName() + "' (" + typeName(field)
                + ") expects " + expected + ", got " + actual.getKindCase().name().toLowerCase());
    }

    private static InvalidQueryException missingType(FieldSchema field) {
        return new InvalidQueryException("field '" + field.getName() + "' has no type set in the schema");
    }

    private static String typeName(FieldSchema field) {
        return field.getTypeCase().name().toLowerCase();
    }

    // -----------------------------------------------------------------------
    // Analysis
    // -----------------------------------------------------------------------

    private record Token(String term, int position) {}

    private static List<Token> tokenize(Analyzer analyzer, String field, String text) {
        List<Token> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            PositionIncrementAttribute increment = stream.addAttribute(PositionIncrementAttribute.class);
            stream.reset();
            int position = -1;
            while (stream.incrementToken()) {
                position += increment.getPositionIncrement();
                tokens.add(new Token(term.toString(), position));
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException("Analyzer failed on query text", e);
        }
        return tokens;
    }
}
