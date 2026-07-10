package ai.pipestream.search.schema;

import ai.pipestream.search.v1alpha1.CollectionDefaults;
import ai.pipestream.search.v1alpha1.FieldType;
import ai.pipestream.search.v1alpha1.NestedSemantics;
import ai.pipestream.search.v1alpha1.Representation;
import ai.pipestream.search.v1alpha1.SchemaChange;
import ai.pipestream.search.v1alpha1.SchemaOptionsProto;
import ai.pipestream.search.v1alpha1.SearchField;
import ai.pipestream.search.v1alpha1.VectorOptions;
import ai.pipestream.search.v1alpha1.VectorSimilarity;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The descriptor crawler: compiles a user-authored, option-annotated proto
 * schema into a {@link CompiledSchema}, rejecting anything the index cannot
 * honor. Pure function — reusable verbatim in the engine's RegisterSchema
 * gate, a CLI, or a CI check. Rules per docs/rfc/SCHEMA_AS_PROTO.md.
 */
public final class SchemaCompiler {

  private SchemaCompiler() {}

  /** Compilation output: the schema (possibly partial) plus rejections. */
  public record Result(CompiledSchema schema, List<SchemaChange> rejections) {
    public boolean ok() {
      return rejections.isEmpty();
    }
  }

  /**
   * Parses FileDescriptorSet bytes with our custom options registered.
   * Without this registry the annotations silently arrive as unknown fields
   * and every field would compile as "not indexed" — the trap documented in
   * the RFC annex.
   */
  public static FileDescriptorSet parseDescriptorSet(byte[] bytes) throws InvalidProtocolBufferException {
    ExtensionRegistry registry = ExtensionRegistry.newInstance();
    SchemaOptionsProto.registerAllExtensions(registry);
    return FileDescriptorSet.parseFrom(bytes, registry);
  }

  /**
   * Rejects any fully qualified type redefined with a different shape across
   * the files of the set. Each file may be individually valid, so buildFrom
   * accepts them; whichever definition resolves first would silently win.
   * Byte-equality on compiled declarations ignores comments/whitespace.
   * (Same check as ProtobufFqnConflictDetector in Apicurio and
   * ProtoTypeConflictDetector in quarkus-grpc-zero.)
   */
  public static List<SchemaChange> checkTypeConflicts(FileDescriptorSet set) {
    List<SchemaChange> conflicts = new ArrayList<>();
    Map<String, byte[]> seenBytes = new HashMap<>();
    Map<String, String> seenSource = new HashMap<>();
    for (FileDescriptorProto file : set.getFileList()) {
      String prefix = file.getPackage().isEmpty() ? "" : file.getPackage() + ".";
      for (com.google.protobuf.DescriptorProtos.DescriptorProto m : file.getMessageTypeList()) {
        checkMessageDeclarations(prefix + m.getName(), m, file.getName(), seenBytes, seenSource, conflicts);
      }
      for (com.google.protobuf.DescriptorProtos.EnumDescriptorProto e : file.getEnumTypeList()) {
        checkDeclaration(prefix + e.getName(), e.toByteArray(), file.getName(), seenBytes, seenSource, conflicts);
      }
    }
    return conflicts;
  }

  private static void checkMessageDeclarations(
      String fqn,
      com.google.protobuf.DescriptorProtos.DescriptorProto message,
      String source,
      Map<String, byte[]> seenBytes,
      Map<String, String> seenSource,
      List<SchemaChange> conflicts) {
    checkDeclaration(fqn, message.toByteArray(), source, seenBytes, seenSource, conflicts);
    for (com.google.protobuf.DescriptorProtos.DescriptorProto nested : message.getNestedTypeList()) {
      checkMessageDeclarations(
          fqn + "." + nested.getName(), nested, source, seenBytes, seenSource, conflicts);
    }
    for (com.google.protobuf.DescriptorProtos.EnumDescriptorProto nestedEnum : message.getEnumTypeList()) {
      checkDeclaration(
          fqn + "." + nestedEnum.getName(),
          nestedEnum.toByteArray(),
          source,
          seenBytes,
          seenSource,
          conflicts);
    }
  }

  private static void checkDeclaration(String fqn, byte[] bytes, String source,
      Map<String, byte[]> seenBytes, Map<String, String> seenSource, List<SchemaChange> conflicts) {
    byte[] existing = seenBytes.putIfAbsent(fqn, bytes);
    if (existing == null) {
      seenSource.put(fqn, source);
    } else if (!java.util.Arrays.equals(existing, bytes)) {
      conflicts.add(reject(fqn, "TYPE_REDEFINED",
          "type declared in " + seenSource.get(fqn) + " is redefined differently in " + source));
    }
  }

  /** Compiles the root message of an in-memory descriptor set. */
  public static Result compile(FileDescriptorSet set, String rootMessageFullName) throws Descriptors.DescriptorValidationException {
    List<SchemaChange> conflicts = checkTypeConflicts(set);
    if (!conflicts.isEmpty()) {
      return new Result(new CompiledSchema(rootMessageFullName, "standard", List.of()), conflicts);
    }
    Map<String, FileDescriptor> built = new HashMap<>();
    List<FileDescriptorProto> pending = new ArrayList<>(set.getFileList());
    while (pending.isEmpty() == false) {
      boolean madeProgress = false;
      for (java.util.Iterator<FileDescriptorProto> it = pending.iterator(); it.hasNext(); ) {
        FileDescriptorProto fdp = it.next();
        List<FileDescriptor> deps = new ArrayList<>();
        boolean unresolved = false;
        for (String dep : fdp.getDependencyList()) {
          FileDescriptor d = built.get(dep);
          if (d == null && dep.equals("google/protobuf/descriptor.proto")) {
            d = com.google.protobuf.DescriptorProtos.getDescriptor();
          }
          if (d == null && dep.equals("google/protobuf/timestamp.proto")) {
            d = com.google.protobuf.TimestampProto.getDescriptor();
          }
          if (d == null) {
            unresolved = true;
            break;
          }
          deps.add(d);
        }
        if (unresolved) {
          continue;
        }
        FileDescriptor descriptor = FileDescriptor.buildFrom(fdp, deps.toArray(new FileDescriptor[0]));
        built.put(fdp.getName(), descriptor);
        it.remove();
        madeProgress = true;
      }
      if (madeProgress == false) {
        throw new IllegalArgumentException(
            "descriptor set has unresolved or cyclic dependencies: "
                + pending.stream().map(FileDescriptorProto::getName).toList());
      }
    }
    Descriptor root = null;
    for (FileDescriptor fd : built.values()) {
      Descriptor d = findMessage(fd, rootMessageFullName);
      if (d != null) {
        root = d;
        break;
      }
    }
    if (root == null) {
      throw new IllegalArgumentException("root message not found: " + rootMessageFullName);
    }
    return compile(root);
  }

  /** Compiles a root message descriptor into the internal schema. */
  public static Result compile(Descriptor root) {
    List<SchemaChange> rejections = new ArrayList<>();
    List<CompiledField> fields = new ArrayList<>();
    CollectionDefaults defaults = root.getOptions().getExtension(SchemaOptionsProto.collectionDefaults);
    String defaultAnalyzer = defaults.getDefaultAnalyzer().isEmpty() ? "standard" : defaults.getDefaultAnalyzer();

    Deque<String> visiting = new ArrayDeque<>();
    walk(root, "", CompiledSchema.JoinScope.ROOT, "", visiting, fields, rejections);
    return new Result(new CompiledSchema(root.getFullName(), defaultAnalyzer, List.copyOf(fields)), rejections);
  }

  private static void walk(
      Descriptor message,
      String path,
      CompiledSchema.JoinScope scope,
      String blockPath,
      Deque<String> visiting,
      List<CompiledField> out,
      List<SchemaChange> rejections) {
    if (visiting.contains(message.getFullName())) {
      rejections.add(reject(path, "MESSAGE_CYCLE",
          "recursive message type " + message.getFullName() + " cannot be indexed"));
      return;
    }
    visiting.push(message.getFullName());
    try {
      for (FieldDescriptor fd : message.getFields()) {
        String fieldPath = path.isEmpty() ? fd.getName() : path + "." + fd.getName();
        if (!fd.getOptions().hasExtension(SchemaOptionsProto.field)) {
          continue; // unannotated: neither indexed nor stored
        }
        SearchField opt = fd.getOptions().getExtension(SchemaOptionsProto.field);

        if (fd.isMapField()) {
          rejections.add(reject(fieldPath, "MAP_FIELD",
              "map fields are not supported in v1 (see RFC: key-in-term flattening is a possible v2 feature)"));
          continue;
        }

        boolean isTimestamp = fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE
            && fd.getMessageType().getFullName().equals("google.protobuf.Timestamp");

        if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !isTimestamp) {
          compileMessageField(fd, opt, fieldPath, scope, blockPath, visiting, out, rejections);
          continue;
        }

        compileLeaf(fd, opt, fieldPath, path, scope, blockPath, isTimestamp, out, rejections);
      }
    } finally {
      visiting.pop();
    }
  }

  private static void compileMessageField(
      FieldDescriptor fd,
      SearchField opt,
      String fieldPath,
      CompiledSchema.JoinScope scope,
      String blockPath,
      Deque<String> visiting,
      List<CompiledField> out,
      List<SchemaChange> rejections) {
    if (opt.getType() != FieldType.FIELD_TYPE_UNSPECIFIED) {
      rejections.add(reject(fieldPath, "MESSAGE_WITH_LEAF_TYPE",
          "message-typed field declares a leaf FieldType; use `nested` instead"));
      return;
    }
    switch (opt.getNested()) {
      case NESTED_SEMANTICS_FLATTEN ->
          walk(fd.getMessageType(), fieldPath, scope, blockPath, visiting, out, rejections);
      case NESTED_SEMANTICS_BLOCK_JOIN ->
          walk(fd.getMessageType(), fieldPath, CompiledSchema.JoinScope.BLOCK_CHILD, fieldPath, visiting, out, rejections);
      default ->
          rejections.add(reject(fieldPath, "NESTED_UNSPECIFIED",
              "message-typed field must declare nested = FLATTEN or BLOCK_JOIN explicitly"));
    }
  }

  private static void compileLeaf(
      FieldDescriptor fd,
      SearchField opt,
      String fieldPath,
      String parentPath,
      CompiledSchema.JoinScope scope,
      String blockPath,
      boolean isTimestamp,
      List<CompiledField> out,
      List<SchemaChange> rejections) {
    FieldType type = opt.getType();
    if (type == FieldType.FIELD_TYPE_UNSPECIFIED) {
      rejections.add(reject(fieldPath, "LEAF_TYPE_MISSING", "annotated leaf field must declare a FieldType"));
      return;
    }
    if (opt.getNested() != NestedSemantics.NESTED_SEMANTICS_UNSPECIFIED) {
      rejections.add(reject(fieldPath, "NESTED_ON_LEAF", "`nested` is only valid on message-typed fields"));
      return;
    }

    // THE PRESENCE RULE: indexable proto3 scalars must have explicit presence.
    if (!fd.isRepeated() && !fd.hasPresence()) {
      rejections.add(reject(fieldPath, "IMPLICIT_PRESENCE",
          "indexable scalar must be declared `optional` (proto3 cannot distinguish absent from zero)"));
      return;
    }

    if (!sourceCompatible(fd, type, isTimestamp)) {
      rejections.add(reject(fieldPath, "TYPE_SOURCE_MISMATCH",
          "FieldType " + type + " is not derivable from proto type " + fd.getType()
              + (fd.isRepeated() ? " (repeated)" : "")));
      return;
    }

    if (!opt.getAnalyzer().isEmpty() && type != FieldType.FIELD_TYPE_TEXT) {
      rejections.add(reject(fieldPath, "ANALYZER_ON_NON_TEXT", "analyzer is only valid for TEXT fields"));
      return;
    }

    if (type == FieldType.FIELD_TYPE_VECTOR && opt.getVector().getDims() <= 0) {
      rejections.add(reject(fieldPath, "VECTOR_DIMS_MISSING", "VECTOR fields must declare vector.dims > 0"));
      return;
    }

    out.add(leaf(fd, fieldPath, parentPath, "", type, opt.getAnalyzer(), opt.getSearchAnalyzer(),
        opt.hasStored() && opt.getStored(), resolveDocValues(opt.hasDocValues(), opt.getDocValues(), type),
        opt.getIndexOptions(), opt.getVector(), scope, blockPath));

    Set<String> repNames = new HashSet<>();
    for (Representation rep : opt.getRepresentationsList()) {
      if (!repNames.add(rep.getName()) || rep.getName().isEmpty()) {
        rejections.add(reject(fieldPath + "#" + rep.getName(), "REPRESENTATION_DUP",
            "representation names must be non-empty and unique within the field"));
        continue;
      }
      if (rep.getType() == FieldType.FIELD_TYPE_VECTOR && rep.getVector().getDims() <= 0) {
        rejections.add(reject(fieldPath + "#" + rep.getName(), "VECTOR_DIMS_MISSING",
            "VECTOR representations must declare vector.dims > 0"));
        continue;
      }
      out.add(leaf(fd, fieldPath, parentPath, rep.getName(), rep.getType(), rep.getAnalyzer(),
          rep.getSearchAnalyzer(), false,
          resolveDocValues(rep.hasDocValues(), rep.getDocValues(), rep.getType()),
          rep.getIndexOptions(), rep.getVector(), scope, blockPath));
    }
  }

  private static CompiledField leaf(
      FieldDescriptor fd,
      String fieldPath,
      String parentPath,
      String repName,
      FieldType type,
      String analyzer,
      String searchAnalyzer,
      boolean stored,
      boolean docValues,
      ai.pipestream.search.v1alpha1.IndexGranularity granularity,
      VectorOptions vector,
      CompiledSchema.JoinScope scope,
      String blockPath) {
    String indexName = repName.isEmpty() ? fieldPath : fieldPath + "#" + repName;
    return new CompiledField(
        indexName,
        fieldPath,
        parentPath,
        fd.getNumber(),
        repName,
        fd.getType(),
        kind(type),
        analyzer,
        searchAnalyzer,
        stored,
        docValues,
        granularity,
        vector.getDims(),
        similarity(vector),
        vector.getHnsw().getMaxConn(),
        vector.getHnsw().getBeamWidth(),
        fd.isRepeated(),
        scope,
        blockPath);
  }

  private static boolean sourceCompatible(FieldDescriptor fd, FieldType type, boolean isTimestamp) {
    FieldDescriptor.JavaType j = fd.getJavaType();
    return switch (type) {
      case FIELD_TYPE_KEYWORD -> j == FieldDescriptor.JavaType.STRING || j == FieldDescriptor.JavaType.ENUM;
      case FIELD_TYPE_TEXT -> j == FieldDescriptor.JavaType.STRING;
      case FIELD_TYPE_LONG -> j == FieldDescriptor.JavaType.INT || j == FieldDescriptor.JavaType.LONG;
      case FIELD_TYPE_DOUBLE -> j == FieldDescriptor.JavaType.FLOAT || j == FieldDescriptor.JavaType.DOUBLE;
      case FIELD_TYPE_DATE -> isTimestamp || j == FieldDescriptor.JavaType.INT || j == FieldDescriptor.JavaType.LONG;
      case FIELD_TYPE_BOOL -> j == FieldDescriptor.JavaType.BOOLEAN;
      case FIELD_TYPE_VECTOR -> fd.isRepeated() && j == FieldDescriptor.JavaType.FLOAT;
      case FIELD_TYPE_STORED_ONLY -> true; // any leaf, including bytes
      default -> false;
    };
  }

  private static boolean resolveDocValues(boolean has, boolean value, FieldType type) {
    if (has) {
      return value;
    }
    return switch (type) {
      case FIELD_TYPE_KEYWORD, FIELD_TYPE_LONG, FIELD_TYPE_DOUBLE, FIELD_TYPE_DATE, FIELD_TYPE_BOOL -> true;
      default -> false;
    };
  }

  private static CompiledSchema.Kind kind(FieldType t) {
    return switch (t) {
      case FIELD_TYPE_KEYWORD -> CompiledSchema.Kind.KEYWORD;
      case FIELD_TYPE_TEXT -> CompiledSchema.Kind.TEXT;
      case FIELD_TYPE_LONG -> CompiledSchema.Kind.LONG;
      case FIELD_TYPE_DOUBLE -> CompiledSchema.Kind.DOUBLE;
      case FIELD_TYPE_DATE -> CompiledSchema.Kind.DATE;
      case FIELD_TYPE_BOOL -> CompiledSchema.Kind.BOOL;
      case FIELD_TYPE_VECTOR -> CompiledSchema.Kind.VECTOR;
      default -> CompiledSchema.Kind.STORED_ONLY;
    };
  }

  private static VectorSimilarity similarity(VectorOptions v) {
    return switch (v.getSimilarity()) {
      case SIMILARITY_DOT_PRODUCT -> VectorSimilarity.VECTOR_SIMILARITY_DOT_PRODUCT;
      case SIMILARITY_EUCLIDEAN -> VectorSimilarity.VECTOR_SIMILARITY_EUCLIDEAN;
      case SIMILARITY_MAX_INNER_PRODUCT -> VectorSimilarity.VECTOR_SIMILARITY_MAX_INNER_PRODUCT;
      default -> VectorSimilarity.VECTOR_SIMILARITY_COSINE;
    };
  }

  private static Descriptor findMessage(FileDescriptor fd, String fullName) {
    for (Descriptor d : fd.getMessageTypes()) {
      if (d.getFullName().equals(fullName)) {
        return d;
      }
      Descriptor nested = findNested(d, fullName);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static Descriptor findNested(Descriptor d, String fullName) {
    for (Descriptor n : d.getNestedTypes()) {
      if (n.getFullName().equals(fullName)) {
        return n;
      }
      Descriptor deeper = findNested(n, fullName);
      if (deeper != null) {
        return deeper;
      }
    }
    return null;
  }

  static SchemaChange reject(String field, String code, String description) {
    return SchemaChange.newBuilder()
        .setClassification(SchemaChange.Classification.CLASSIFICATION_REJECTED)
        .setField(field)
        .setCode(code)
        .setDescription(description)
        .build();
  }
}
