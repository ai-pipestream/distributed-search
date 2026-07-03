package ai.pipestream.search.schema;

import ai.pipestream.search.v1alpha1.SchemaChange;
import ai.pipestream.search.v1alpha1.SchemaChange.Classification;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Classifies the differences between a collection's current compiled schema
 * and a proposed one. Proto wire-compatibility is necessary but not
 * sufficient: the index has stricter rules (renames and index-affecting
 * changes require reindex), which this referee enforces. Pure function.
 */
public final class SchemaValidator {

  private SchemaValidator() {}

  public static List<SchemaChange> diff(CompiledSchema current, CompiledSchema proposed) {
    List<SchemaChange> changes = new ArrayList<>();
    Map<String, CompiledField> oldByIdentity = current.byIdentity();
    Map<String, CompiledField> newByIdentity = proposed.byIdentity();

    for (Map.Entry<String, CompiledField> e : newByIdentity.entrySet()) {
      CompiledField now = e.getValue();
      CompiledField was = oldByIdentity.get(e.getKey());
      if (was == null) {
        changes.add(change(Classification.CLASSIFICATION_WIRE_SAFE_LIVE, now.indexName(),
            "NEW_FIELD", "new field is indexed for documents ingested from now on"));
        continue;
      }
      // Same parent path + tag (+ representation): compare configurations.
      if (was.protoType() != now.protoType()) {
        changes.add(change(Classification.CLASSIFICATION_REJECTED, now.indexName(),
            "TAG_REUSED", "proto type changed " + was.protoType() + " -> " + now.protoType()
                + " on tag " + now.tag() + "; reusing a tag with a different wire type is never legal"));
        continue;
      }
      if (!was.sourcePath().equals(now.sourcePath())) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, now.indexName(),
            "FIELD_RENAMED", "renamed " + was.sourcePath() + " -> " + now.sourcePath()
                + "; wire-safe in proto but index field names are baked into segments"));
        continue;
      }
      if (was.type() != now.type()) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, now.indexName(),
            "TYPE_CHANGED", "index type changed " + was.type() + " -> " + now.type()));
        continue;
      }
      if (!was.analyzer().equals(now.analyzer())) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, now.indexName(),
            "ANALYZER_CHANGED", "index-time analyzer changed '" + was.analyzer() + "' -> '" + now.analyzer() + "'"));
      }
      if (!was.searchAnalyzer().equals(now.searchAnalyzer())) {
        changes.add(change(Classification.CLASSIFICATION_WIRE_SAFE_LIVE, now.indexName(),
            "SEARCH_ANALYZER_CHANGED", "search-time analyzer changes affect only future queries"));
      }
      if (was.joinScope() != now.joinScope() || !was.blockPath().equals(now.blockPath())) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, now.indexName(),
            "NESTED_SEMANTICS_CHANGED", "flatten/block-join mapping changed"));
      }
      if (was.stored() != now.stored()) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, now.indexName(),
            "STORED_CHANGED", "stored flag changed; existing documents lack (or hide) stored values"));
      }
      if (was.docValues() != now.docValues()) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, now.indexName(),
            "DOC_VALUES_CHANGED", "doc-values flag changed"));
      }
      if (was.granularity() != now.granularity()) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, now.indexName(),
            "INDEX_OPTIONS_CHANGED", "postings granularity changed " + was.granularity() + " -> " + now.granularity()));
      }
      if (was.vectorDims() != now.vectorDims() || was.vectorSimilarity() != now.vectorSimilarity()) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, now.indexName(),
            "VECTOR_PARAMS_CHANGED", "vector dims/similarity changed; existing graphs are incompatible"));
      } else if (was.hnswMaxConn() != now.hnswMaxConn() || was.hnswBeamWidth() != now.hnswBeamWidth()) {
        // Graph build params are per-segment; existing graphs stay valid and
        // future segments simply build with the new parameters.
        changes.add(change(Classification.CLASSIFICATION_WIRE_SAFE_LIVE, now.indexName(),
            "HNSW_PARAMS_CHANGED", "HNSW construction params apply to future segments only"));
      }
    }

    for (Map.Entry<String, CompiledField> e : oldByIdentity.entrySet()) {
      if (!newByIdentity.containsKey(e.getKey())) {
        changes.add(change(Classification.CLASSIFICATION_REQUIRES_REINDEX, e.getValue().indexName(),
            "FIELD_REMOVED", "existing documents still carry this field; removal without reindex leaves "
                + "unreachable index data and surprising query behavior"));
      }
    }
    return changes;
  }

  private static SchemaChange change(Classification c, String field, String code, String description) {
    return SchemaChange.newBuilder()
        .setClassification(c)
        .setField(field)
        .setCode(code)
        .setDescription(description)
        .build();
  }
}
