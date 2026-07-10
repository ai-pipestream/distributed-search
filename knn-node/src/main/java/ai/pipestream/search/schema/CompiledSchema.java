package ai.pipestream.search.schema;

import ai.pipestream.search.v1alpha1.AnalyzerRef;
import ai.pipestream.search.v1alpha1.BooleanFieldSchema;
import ai.pipestream.search.v1alpha1.CollectionSchema;
import ai.pipestream.search.v1alpha1.DateFieldSchema;
import ai.pipestream.search.v1alpha1.DenseVectorFieldSchema;
import ai.pipestream.search.v1alpha1.FieldSchema;
import ai.pipestream.search.v1alpha1.HnswParams;
import ai.pipestream.search.v1alpha1.KeywordFieldSchema;
import ai.pipestream.search.v1alpha1.NumericFieldSchema;
import ai.pipestream.search.v1alpha1.NumericType;
import ai.pipestream.search.v1alpha1.TextFieldSchema;
import ai.pipestream.search.v1alpha1.VectorIndexParams;
import ai.pipestream.search.v1alpha1.VectorSimilarity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engine-internal result of compiling an annotated schema proto. Richer
 * than the wire CollectionSchema: it keeps source paths, proto tag identity
 * (for change classification), doc-values/index-options directives, and
 * block-join scoping, none of which the v1alpha1 wire schema carries yet.
 */
public record CompiledSchema(String rootMessage, String defaultAnalyzer, List<CompiledField> fields) {

  public Optional<CompiledField> field(String indexName) {
    return fields.stream().filter(f -> f.indexName().equals(indexName)).findFirst();
  }

  /** Index of fields by proto identity (parent path + tag), for schema diffing. */
  public Map<String, CompiledField> byIdentity() {
    Map<String, CompiledField> m = new LinkedHashMap<>();
    for (CompiledField f : fields) {
      m.put(f.identity(), f);
    }
    return m;
  }

  /** Flattens to the v1alpha1 wire schema (lossy: see class javadoc). */
  public CollectionSchema toProto() {
    CollectionSchema.Builder schema = CollectionSchema.newBuilder();
    for (CompiledField f : fields) {
      FieldSchema.Builder fs = FieldSchema.newBuilder().setName(f.indexName()).setStored(f.stored());
      switch (f.type()) {
        case KEYWORD -> fs.setKeyword(KeywordFieldSchema.getDefaultInstance());
        case TEXT -> fs.setText(TextFieldSchema.newBuilder().setAnalyzer(analyzerRef(f.analyzer())));
        case LONG -> fs.setNumeric(NumericFieldSchema.newBuilder().setType(NumericType.NUMERIC_TYPE_INT64));
        case DOUBLE -> fs.setNumeric(NumericFieldSchema.newBuilder().setType(NumericType.NUMERIC_TYPE_DOUBLE));
        case DATE -> fs.setDate(DateFieldSchema.getDefaultInstance());
        case BOOL -> fs.setBoolean(BooleanFieldSchema.getDefaultInstance());
        case VECTOR -> fs.setDenseVector(
            DenseVectorFieldSchema.newBuilder()
                .setDims(f.vectorDims())
                .setSimilarity(f.vectorSimilarity())
                .setIndex(VectorIndexParams.newBuilder()
                    .setHnsw(HnswParams.newBuilder()
                        .setM(f.hnswMaxConn())
                        .setEfConstruction(f.hnswBeamWidth()))));
        case STORED_ONLY -> { /* stored flag only; no index type variant */ }
      }
      schema.addFields(fs);
    }
    return schema.build();
  }

  static AnalyzerRef analyzerRef(String name) {
    AnalyzerRef.Builder ref = AnalyzerRef.newBuilder();
    switch (name == null ? "" : name) {
      case "", "standard" -> ref.setBuiltin(ai.pipestream.search.v1alpha1.BuiltinAnalyzer.BUILTIN_ANALYZER_STANDARD);
      case "english" -> ref.setBuiltin(ai.pipestream.search.v1alpha1.BuiltinAnalyzer.BUILTIN_ANALYZER_ENGLISH);
      case "whitespace" -> ref.setBuiltin(ai.pipestream.search.v1alpha1.BuiltinAnalyzer.BUILTIN_ANALYZER_WHITESPACE);
      case "keyword" -> ref.setBuiltin(ai.pipestream.search.v1alpha1.BuiltinAnalyzer.BUILTIN_ANALYZER_KEYWORD);
      case "simple" -> ref.setBuiltin(ai.pipestream.search.v1alpha1.BuiltinAnalyzer.BUILTIN_ANALYZER_SIMPLE);
      default -> ref.setPlugin(ai.pipestream.search.v1alpha1.PluggableAnalyzer.newBuilder().setName(name));
    }
    return ref.build();
  }

  /** How a message-typed subtree was mapped onto the flat index. */
  public enum JoinScope {
    ROOT,
    /** Field lives on child documents of a block join rooted at blockPath. */
    BLOCK_CHILD
  }

  public enum Kind { KEYWORD, TEXT, LONG, DOUBLE, DATE, BOOL, VECTOR, STORED_ONLY }
}
