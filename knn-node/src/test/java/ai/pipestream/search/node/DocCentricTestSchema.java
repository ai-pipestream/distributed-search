package ai.pipestream.search.node;

import ai.pipestream.search.v1alpha1.FieldType;
import ai.pipestream.search.v1alpha1.SchemaOptionsProto;
import ai.pipestream.search.v1alpha1.SearchField;
import ai.pipestream.search.v1alpha1.VectorOptions;
import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

/**
 * Shared annotated schema fixture for document-centric tests:
 * t.Doc { optional string title [TEXT, stored]; repeated float embedding
 * [VECTOR dims=4 COSINE]; } plus chunk message t.DocChunk { optional string
 * text; }.
 */
final class DocCentricTestSchema {

    private DocCentricTestSchema() {
    }

    private static FieldOptions searchField(SearchField sf) {
        return FieldOptions.newBuilder().setExtension(SchemaOptionsProto.field, sf).build();
    }

    static FileDescriptorProto schemaFile() {
        DescriptorProto.Builder doc = DescriptorProto.newBuilder().setName("Doc");
        doc.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_title"));
        doc.addField(FieldDescriptorProto.newBuilder()
                .setName("title").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setProto3Optional(true).setOneofIndex(0)
                .setOptions(searchField(SearchField.newBuilder()
                        .setType(FieldType.FIELD_TYPE_TEXT).setStored(true).build())));
        doc.addField(FieldDescriptorProto.newBuilder()
                .setName("embedding").setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_FLOAT)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .setOptions(searchField(SearchField.newBuilder()
                        .setType(FieldType.FIELD_TYPE_VECTOR)
                        .setVector(VectorOptions.newBuilder().setDims(4)
                                .setSimilarity(VectorOptions.Similarity.SIMILARITY_COSINE))
                        .build())));

        DescriptorProto.Builder chunk = DescriptorProto.newBuilder().setName("DocChunk");
        chunk.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_text"));
        chunk.addField(FieldDescriptorProto.newBuilder()
                .setName("text").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setProto3Optional(true).setOneofIndex(0));

        return FileDescriptorProto.newBuilder()
                .setName("doc_schema.proto").setPackage("t").setSyntax("proto3")
                .addMessageType(doc.build())
                .addMessageType(chunk.build())
                .build();
    }

    /** Serialize + naive re-parse: exactly what the gRPC boundary does. */
    static FileDescriptorSet wireDescriptorSet() throws Exception {
        byte[] bytes = FileDescriptorSet.newBuilder().addFile(schemaFile()).build().toByteArray();
        return FileDescriptorSet.parseFrom(bytes);
    }

    static Descriptors.FileDescriptor buildFile() throws Exception {
        return Descriptors.FileDescriptor.buildFrom(schemaFile(), new Descriptors.FileDescriptor[0]);
    }

    static Any docPayload(Descriptors.FileDescriptor file, String title) {
        Descriptors.Descriptor doc = file.findMessageTypeByName("Doc");
        DynamicMessage message = DynamicMessage.newBuilder(doc)
                .setField(doc.findFieldByName("title"), title)
                .build();
        return Any.newBuilder()
                .setTypeUrl("type.googleapis.com/t.Doc")
                .setValue(message.toByteString())
                .build();
    }

    static Any chunkPayload(Descriptors.FileDescriptor file, String text) {
        Descriptors.Descriptor chunk = file.findMessageTypeByName("DocChunk");
        DynamicMessage message = DynamicMessage.newBuilder(chunk)
                .setField(chunk.findFieldByName("text"), text)
                .build();
        return Any.newBuilder()
                .setTypeUrl("type.googleapis.com/t.DocChunk")
                .setValue(message.toByteString())
                .build();
    }
}
