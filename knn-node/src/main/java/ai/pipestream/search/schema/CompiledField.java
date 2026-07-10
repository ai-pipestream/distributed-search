package ai.pipestream.search.schema;

import ai.pipestream.search.v1alpha1.IndexGranularity;
import ai.pipestream.search.v1alpha1.VectorSimilarity;
import com.google.protobuf.Descriptors.FieldDescriptor;

/**
 * One indexable field produced by schema compilation.
 *
 * @param indexName Lucene field name: dotted source path, plus "#rep" suffix
 *     for fan-out representations ("vendor.name", "title#raw").
 * @param sourcePath dotted proto path of the source field.
 * @param parentPath dotted proto path of the enclosing message ("" at root).
 * @param tag proto field number of the source leaf (identity survives renames).
 * @param representation fan-out representation name, or "" for the primary.
 * @param protoType source proto scalar/message type (tag-reuse detection).
 * @param type how the value is indexed.
 * @param analyzer index-time analyzer name ("" = collection default).
 * @param searchAnalyzer search-time analyzer override ("" = same as analyzer).
 * @param stored whether the source value is stored for retrieval.
 * @param docValues column-stride storage for sort/facet.
 * @param granularity postings granularity for TEXT/KEYWORD.
 * @param vectorDims dims for VECTOR fields (0 otherwise).
 * @param vectorSimilarity similarity for VECTOR fields.
 * @param hnswMaxConn HNSW maxConn (0 = server default).
 * @param hnswBeamWidth HNSW beamWidth (0 = server default).
 * @param repeated whether the source field is repeated (multi-valued).
 * @param joinScope ROOT, or BLOCK_CHILD for block-join subtree fields.
 * @param blockPath source path of the block-join root ("" unless BLOCK_CHILD).
 */
public record CompiledField(
    String indexName,
    String sourcePath,
    String parentPath,
    int tag,
    String representation,
    FieldDescriptor.Type protoType,
    CompiledSchema.Kind type,
    String analyzer,
    String searchAnalyzer,
    boolean stored,
    boolean docValues,
    IndexGranularity granularity,
    int vectorDims,
    VectorSimilarity vectorSimilarity,
    int hnswMaxConn,
    int hnswBeamWidth,
    boolean repeated,
    CompiledSchema.JoinScope joinScope,
    String blockPath) {

  /**
   * Stable identity for schema diffing: enclosing path + tag + representation.
   * A rename changes indexName/sourcePath but not identity; a tag reuse
   * changes identity semantics and is caught by comparing protoType.
   */
  public String identity() {
    return parentPath + "#" + tag + (representation.isEmpty() ? "" : "#" + representation);
  }
}
