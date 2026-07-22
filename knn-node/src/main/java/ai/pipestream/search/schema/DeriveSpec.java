package ai.pipestream.search.schema;

import ai.pipestream.search.v1alpha1.ChunkAndEmbed;
import ai.pipestream.search.v1alpha1.Representation;
import ai.pipestream.search.v1alpha1.SchemaOptionsProto;
import ai.pipestream.search.v1alpha1.SearchField;
import com.google.protobuf.Descriptors;

import java.util.ArrayList;
import java.util.List;

/**
 * A server-side chunk-and-embed derivation declared by the schema: one TEXT
 * source field fanning out into one VECTOR representation.
 *
 * @param sourceField root-level source field name (the text to chunk)
 * @param vectorField Lucene field the chunk vectors land on ("field#rep")
 * @param vectorDims  declared dims of the representation
 * @param config      the pinned derivation (model, spec, storage policy)
 */
public record DeriveSpec(String sourceField, String vectorField, int vectorDims,
                         ChunkAndEmbed config) {

    /** Representation suffix ("rep" of "field#rep"). */
    public String representationName() {
        int hash = vectorField.indexOf('#');
        return hash < 0 ? vectorField : vectorField.substring(hash + 1);
    }

    /** Resolves every derivation the root message declares. */
    public static List<DeriveSpec> resolve(Descriptors.Descriptor root) {
        List<DeriveSpec> specs = new ArrayList<>();
        for (Descriptors.FieldDescriptor field : root.getFields()) {
            if (!field.getOptions().hasExtension(SchemaOptionsProto.field)) {
                continue;
            }
            SearchField searchField = field.getOptions().getExtension(SchemaOptionsProto.field);
            for (Representation rep : searchField.getRepresentationsList()) {
                if (!rep.hasDerive()) {
                    continue;
                }
                specs.add(new DeriveSpec(
                        field.getName(),
                        field.getName() + "#" + rep.getName(),
                        rep.getVector().getDims(),
                        rep.getDerive()));
            }
        }
        return specs;
    }
}
