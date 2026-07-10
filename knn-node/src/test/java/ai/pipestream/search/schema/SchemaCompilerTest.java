package ai.pipestream.search.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.pipestream.search.v1alpha1.FieldType;
import ai.pipestream.search.v1alpha1.NestedSemantics;
import ai.pipestream.search.v1alpha1.Representation;
import ai.pipestream.search.v1alpha1.SchemaChange;
import ai.pipestream.search.v1alpha1.SchemaOptionsProto;
import ai.pipestream.search.v1alpha1.SearchField;
import ai.pipestream.search.v1alpha1.VectorOptions;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plain JUnit tests for the descriptor crawler — no server boot. */
class SchemaCompilerTest {

  private static final SearchField TEXT = SearchField.newBuilder()
      .setType(FieldType.FIELD_TYPE_TEXT).setStored(true).build();
  private static final SearchField KEYWORD = SearchField.newBuilder()
      .setType(FieldType.FIELD_TYPE_KEYWORD).build();
  private static final SearchField LONG = SearchField.newBuilder()
      .setType(FieldType.FIELD_TYPE_LONG).build();
  private static final SearchField VECTOR4 = SearchField.newBuilder()
      .setType(FieldType.FIELD_TYPE_VECTOR)
      .setVector(VectorOptions.newBuilder().setDims(4)
          .setSimilarity(VectorOptions.Similarity.SIMILARITY_DOT_PRODUCT))
      .build();

  // --- descriptor-building helpers -----------------------------------------

  private static FieldOptions opts(SearchField sf) {
    return FieldOptions.newBuilder().setExtension(SchemaOptionsProto.field, sf).build();
  }

  /** proto3 `optional` scalar: needs a synthetic oneof declaration. */
  private static void addOptionalScalar(DescriptorProto.Builder msg, String name, int tag,
      FieldDescriptorProto.Type type, SearchField sf) {
    int oneofIndex = msg.getOneofDeclCount();
    msg.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_" + name));
    msg.addField(FieldDescriptorProto.newBuilder()
        .setName(name).setNumber(tag).setType(type)
        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
        .setProto3Optional(true).setOneofIndex(oneofIndex)
        .setOptions(opts(sf)));
  }

  private static void addPlainScalar(DescriptorProto.Builder msg, String name, int tag,
      FieldDescriptorProto.Type type, SearchField sf) {
    msg.addField(FieldDescriptorProto.newBuilder()
        .setName(name).setNumber(tag).setType(type)
        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
        .setOptions(opts(sf)));
  }

  private static void addRepeated(DescriptorProto.Builder msg, String name, int tag,
      FieldDescriptorProto.Type type, SearchField sf) {
    msg.addField(FieldDescriptorProto.newBuilder()
        .setName(name).setNumber(tag).setType(type)
        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
        .setOptions(opts(sf)));
  }

  private static void addMessageField(DescriptorProto.Builder msg, String name, int tag,
      String typeName, SearchField sf) {
    msg.addField(FieldDescriptorProto.newBuilder()
        .setName(name).setNumber(tag)
        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(typeName)
        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
        .setOptions(opts(sf)));
  }

  private static FileDescriptor build(DescriptorProto... messages) throws Exception {
    FileDescriptorProto.Builder file = FileDescriptorProto.newBuilder()
        .setName("user_schema.proto").setPackage("t").setSyntax("proto3");
    for (DescriptorProto m : messages) {
      file.addMessageType(m);
    }
    return FileDescriptor.buildFrom(file.build(), new FileDescriptor[0]);
  }

  private static DescriptorProto happyDoc() {
    DescriptorProto.Builder vendor = DescriptorProto.newBuilder().setName("Vendor");
    addOptionalScalar(vendor, "name", 1, FieldDescriptorProto.Type.TYPE_STRING, KEYWORD);

    DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
    addOptionalScalar(doc, "title", 1, FieldDescriptorProto.Type.TYPE_STRING,
        TEXT.toBuilder()
            .addRepresentations(Representation.newBuilder().setName("raw")
                .setType(FieldType.FIELD_TYPE_KEYWORD).setDocValues(true))
            .build());
    addRepeated(doc, "embedding", 2, FieldDescriptorProto.Type.TYPE_FLOAT, VECTOR4);
    addOptionalScalar(doc, "price", 3, FieldDescriptorProto.Type.TYPE_INT64, LONG);
    addMessageField(doc, "vendor", 4, ".t.Vendor",
        SearchField.newBuilder().setNested(NestedSemantics.NESTED_SEMANTICS_FLATTEN).build());
    addRepeated(doc, "tags", 5, FieldDescriptorProto.Type.TYPE_STRING, KEYWORD);
    // note: Vendor must be a top-level type in this test file
    doc.build();
    DescriptorProto v = vendor.build();
    DescriptorProto d = doc.build();
    // pack both into one holder via helper caller
    HOLDER_VENDOR = v;
    return d;
  }

  private static DescriptorProto HOLDER_VENDOR;

  // --- tests ----------------------------------------------------------------

  @Test
  void compilesHappySchema() throws Exception {
    DescriptorProto doc = happyDoc();
    FileDescriptor fd = build(HOLDER_VENDOR, doc);
    SchemaCompiler.Result r = SchemaCompiler.compile(fd.findMessageTypeByName("Doc"));

    assertTrue(r.ok(), () -> "unexpected rejections: " + r.rejections());
    List<String> names = r.schema().fields().stream().map(CompiledField::indexName).toList();
    assertEquals(List.of("title", "title#raw", "embedding", "price", "vendor.name", "tags"), names);

    CompiledField title = r.schema().field("title").orElseThrow();
    assertEquals(CompiledSchema.Kind.TEXT, title.type());
    assertTrue(title.stored());
    CompiledField raw = r.schema().field("title#raw").orElseThrow();
    assertEquals(CompiledSchema.Kind.KEYWORD, raw.type());
    assertTrue(raw.docValues());
    CompiledField vec = r.schema().field("embedding").orElseThrow();
    assertEquals(4, vec.vectorDims());
    CompiledField price = r.schema().field("price").orElseThrow();
    assertTrue(price.docValues(), "numeric doc_values should default on");

    // wire projection keeps names and types
    var proto = r.schema().toProto();
    assertEquals(6, proto.getFieldsCount());
    assertEquals("vendor.name", proto.getFields(4).getName());
  }

  @Test
  void rejectsImplicitPresence() throws Exception {
    DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
    addPlainScalar(doc, "price", 1, FieldDescriptorProto.Type.TYPE_INT64, LONG);
    FileDescriptor fd = build(doc.build());
    SchemaCompiler.Result r = SchemaCompiler.compile(fd.findMessageTypeByName("Doc"));
    assertEquals(1, r.rejections().size());
    assertEquals("IMPLICIT_PRESENCE", r.rejections().get(0).getCode());
  }

  @Test
  void rejectsMessageWithoutNestedSemantics() throws Exception {
    DescriptorProto.Builder vendor = DescriptorProto.newBuilder().setName("Vendor");
    addOptionalScalar(vendor, "name", 1, FieldDescriptorProto.Type.TYPE_STRING, KEYWORD);
    DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
    addMessageField(doc, "vendor", 1, ".t.Vendor", SearchField.getDefaultInstance());
    FileDescriptor fd = build(vendor.build(), doc.build());
    SchemaCompiler.Result r = SchemaCompiler.compile(fd.findMessageTypeByName("Doc"));
    assertEquals("NESTED_UNSPECIFIED", r.rejections().get(0).getCode());
  }

  @Test
  void rejectsVectorWithoutDims() throws Exception {
    DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
    addRepeated(doc, "embedding", 1, FieldDescriptorProto.Type.TYPE_FLOAT,
        SearchField.newBuilder().setType(FieldType.FIELD_TYPE_VECTOR).build());
    FileDescriptor fd = build(doc.build());
    SchemaCompiler.Result r = SchemaCompiler.compile(fd.findMessageTypeByName("Doc"));
    assertEquals("VECTOR_DIMS_MISSING", r.rejections().get(0).getCode());
  }

  @Test
  void rejectsTypeSourceMismatch() throws Exception {
    DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
    addOptionalScalar(doc, "count", 1, FieldDescriptorProto.Type.TYPE_INT64, TEXT);
    FileDescriptor fd = build(doc.build());
    SchemaCompiler.Result r = SchemaCompiler.compile(fd.findMessageTypeByName("Doc"));
    assertEquals("TYPE_SOURCE_MISMATCH", r.rejections().get(0).getCode());
  }

  @Test
  void extensionRegistryTrapIsRealAndHandled() throws Exception {
    DescriptorProto doc = happyDoc();
    FileDescriptorProto file = FileDescriptorProto.newBuilder()
        .setName("user_schema.proto").setPackage("t").setSyntax("proto3")
        .addMessageType(HOLDER_VENDOR).addMessageType(doc).build();
    byte[] bytes = FileDescriptorSet.newBuilder().addFile(file).build().toByteArray();

    // Parsing WITHOUT the extension registry: annotations become unknown
    // fields and the schema silently compiles to nothing.
    FileDescriptorSet naive = FileDescriptorSet.parseFrom(bytes);
    SchemaCompiler.Result blind = SchemaCompiler.compile(naive, "t.Doc");
    assertEquals(0, blind.schema().fields().size(), "trap: options invisible without registry");

    // The helper registers our extensions: annotations survive the round trip.
    FileDescriptorSet parsed = SchemaCompiler.parseDescriptorSet(bytes);
    SchemaCompiler.Result r = SchemaCompiler.compile(parsed, "t.Doc");
    assertTrue(r.ok());
    assertEquals(6, r.schema().fields().size());
  }

  @Test
  void validatorClassifiesChanges() throws Exception {
    DescriptorProto docV1 = happyDoc();
    FileDescriptor fd1 = build(HOLDER_VENDOR, docV1);
    CompiledSchema v1 = SchemaCompiler.compile(fd1.findMessageTypeByName("Doc")).schema();

    // v2: rename title -> headline (same tag), add author, change price to
    // a string keyword on the SAME tag (illegal), tweak search analyzer.
    DescriptorProto.Builder vendor = DescriptorProto.newBuilder().setName("Vendor");
    addOptionalScalar(vendor, "name", 1, FieldDescriptorProto.Type.TYPE_STRING, KEYWORD);
    DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
    addOptionalScalar(doc, "headline", 1, FieldDescriptorProto.Type.TYPE_STRING,
        TEXT.toBuilder()
            .addRepresentations(Representation.newBuilder().setName("raw")
                .setType(FieldType.FIELD_TYPE_KEYWORD).setDocValues(true))
            .build());
    addRepeated(doc, "embedding", 2, FieldDescriptorProto.Type.TYPE_FLOAT,
        VECTOR4.toBuilder()
            .setVector(VECTOR4.getVector().toBuilder()
                .setHnsw(ai.pipestream.search.v1alpha1.HnswOptions.newBuilder()
                    .setMaxConn(32).setBeamWidth(200)))
            .build());
    addOptionalScalar(doc, "price", 3, FieldDescriptorProto.Type.TYPE_STRING, KEYWORD);
    addMessageField(doc, "vendor", 4, ".t.Vendor",
        SearchField.newBuilder().setNested(NestedSemantics.NESTED_SEMANTICS_FLATTEN).build());
    addRepeated(doc, "tags", 5, FieldDescriptorProto.Type.TYPE_STRING, KEYWORD);
    addOptionalScalar(doc, "author", 6, FieldDescriptorProto.Type.TYPE_STRING, KEYWORD);
    FileDescriptor fd2 = build(vendor.build(), doc.build());
    CompiledSchema v2 = SchemaCompiler.compile(fd2.findMessageTypeByName("Doc")).schema();

    List<SchemaChange> changes = SchemaValidator.diff(v1, v2);
    assertEquals(SchemaChange.Classification.CLASSIFICATION_REQUIRES_REINDEX,
        find(changes, "FIELD_RENAMED").getClassification());
    assertEquals(SchemaChange.Classification.CLASSIFICATION_WIRE_SAFE_LIVE,
        find(changes, "NEW_FIELD").getClassification());
    assertEquals(SchemaChange.Classification.CLASSIFICATION_REJECTED,
        find(changes, "TAG_REUSED").getClassification());
    assertEquals(SchemaChange.Classification.CLASSIFICATION_WIRE_SAFE_LIVE,
        find(changes, "HNSW_PARAMS_CHANGED").getClassification());
  }

  private static SchemaChange find(List<SchemaChange> changes, String code) {
    return changes.stream().filter(c -> c.getCode().equals(code)).findFirst()
        .orElseThrow(() -> new AssertionError("missing change " + code + " in " + changes));
  }

  @Test
  void rejectsTypeRedefinedAcrossFiles() throws Exception {
    // Two files each declare t.Doc, with different shapes. Each file is
    // individually valid; whichever resolves first would silently win.
    DescriptorProto.Builder docA = DescriptorProto.newBuilder().setName("Doc");
    addOptionalScalar(docA, "title", 1, FieldDescriptorProto.Type.TYPE_STRING, TEXT);
    DescriptorProto.Builder docB = DescriptorProto.newBuilder().setName("Doc");
    addOptionalScalar(docB, "title", 1, FieldDescriptorProto.Type.TYPE_INT64, LONG);

    FileDescriptorSet set = FileDescriptorSet.newBuilder()
        .addFile(FileDescriptorProto.newBuilder()
            .setName("a.proto").setPackage("t").setSyntax("proto3").addMessageType(docA))
        .addFile(FileDescriptorProto.newBuilder()
            .setName("b.proto").setPackage("t").setSyntax("proto3").addMessageType(docB))
        .build();

    SchemaCompiler.Result r = SchemaCompiler.compile(set, "t.Doc");
    assertEquals(1, r.rejections().size());
    SchemaChange c = r.rejections().get(0);
    assertEquals("TYPE_REDEFINED", c.getCode());
    assertEquals("t.Doc", c.getField());
    assertTrue(c.getDescription().contains("a.proto") && c.getDescription().contains("b.proto"),
        () -> "should name both sources: " + c.getDescription());
    assertEquals(0, r.schema().fields().size(), "no schema is produced on conflict");
  }
}
