package ai.pipestream.search.schema;

import ai.pipestream.search.v1alpha1.SchemaPin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The persisted schema plane: one registered proto schema per collection,
 * shared by the admin, index, and search services.
 *
 * <p>Persists the {@code FileDescriptorSet} bytes <b>exactly as received</b>
 * (protobuf serialization is not canonical across protoc versions, so the
 * digest is only stable over the original bytes), plus the root/chunk message
 * names. The compiled form is rebuilt from those bytes on startup.
 *
 * <p>Layout, next to {@code collection.json}:
 * <pre>
 *   {dataDir}/collections/{name}/schema.pb    raw FileDescriptorSet bytes
 *   {dataDir}/collections/{name}/schema.json  { rootMessage, chunkMessage }
 * </pre>
 */
@ApplicationScoped
public class SchemaStore {

    private static final Logger LOG = Logger.getLogger(SchemaStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "knn.data.dir", defaultValue = "data")
    String dataDir;

    /** Everything the engine knows about one collection's registered schema. */
    public record StoredSchema(String rootMessage, String chunkMessage,
                               byte[] descriptorBytes, byte[] descriptorDigest,
                               byte[] planDigest, CompiledSchema compiled) {

        public SchemaPin toPin() {
            return SchemaPin.newBuilder()
                    .setDescriptorDigest(ByteString.copyFrom(descriptorDigest))
                    .setPlanDigest(ByteString.copyFrom(planDigest))
                    .build();
        }
    }

    private final Map<String, StoredSchema> schemas = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        Path collectionsDir = Path.of(dataDir, "collections");
        if (!Files.exists(collectionsDir)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(collectionsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path pb = dir.resolve("schema.pb");
                Path meta = dir.resolve("schema.json");
                if (!Files.exists(pb) || !Files.exists(meta)) {
                    return;
                }
                String collection = dir.getFileName().toString();
                try {
                    byte[] bytes = Files.readAllBytes(pb);
                    SchemaMetaJson json = MAPPER.readValue(meta.toFile(), SchemaMetaJson.class);
                    schemas.put(collection, build(bytes, json.rootMessage,
                            json.chunkMessage == null ? "" : json.chunkMessage));
                    LOG.infof("Loaded registered schema for collection %s (root=%s)",
                            collection, json.rootMessage);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to load registered schema for collection %s", collection);
                }
            });
        } catch (IOException e) {
            LOG.errorf(e, "Failed to scan collections directory for schemas: %s", collectionsDir);
        }
    }

    /**
     * Compiles, persists, and caches a schema submission. The caller is
     * responsible for API-level gating (rejections → INVALID_ARGUMENT); this
     * method still refuses to store a schema that carries rejections or that
     * names a missing root/chunk message.
     */
    public StoredSchema register(String collection, byte[] descriptorBytes,
                                 String rootMessage, String chunkMessage) throws IOException {
        StoredSchema stored;
        try {
            stored = build(descriptorBytes, rootMessage, chunkMessage);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Schema compilation failed: " + e.getMessage(), e);
        }

        Path dir = Path.of(dataDir, "collections", collection);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Collection directory does not exist: " + dir);
        }
        writeAtomic(dir.resolve("schema.pb"), descriptorBytes);
        Path metaTmp = dir.resolve("schema.json.tmp");
        MAPPER.writeValue(metaTmp.toFile(), new SchemaMetaJson(rootMessage, chunkMessage));
        Files.move(metaTmp, dir.resolve("schema.json"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        schemas.put(collection, stored);
        return stored;
    }

    public Optional<StoredSchema> get(String collection) {
        return Optional.ofNullable(schemas.get(collection));
    }

    /** Evicts the cache entry; the files live inside the collection dir and go with it. */
    public void delete(String collection) {
        schemas.remove(collection);
    }

    private static StoredSchema build(byte[] descriptorBytes, String rootMessage,
                                      String chunkMessage) throws Exception {
        FileDescriptorSet set = SchemaCompiler.parseDescriptorSet(descriptorBytes);
        SchemaCompiler.Result result = SchemaCompiler.compile(set, rootMessage);
        if (!result.rejections().isEmpty()) {
            throw new IllegalArgumentException(
                    "Schema has " + result.rejections().size() + " rejected fields");
        }
        if (result.schema().fields().isEmpty()) {
            throw new IllegalArgumentException("Schema compiled to zero indexable fields");
        }
        if (chunkMessage != null && !chunkMessage.isEmpty() && !containsMessage(set, chunkMessage)) {
            throw new IllegalArgumentException(
                    "chunk_message '" + chunkMessage + "' is not defined in the descriptor set");
        }
        byte[] descriptorDigest = sha256(descriptorBytes);
        // v1 plan canonicalization: the compiled schema's deterministic wire
        // projection. The effective ChunkSpec folds in with mode-A ingest.
        byte[] planDigest = sha256(result.schema().toProto().toByteArray());
        return new StoredSchema(rootMessage, chunkMessage == null ? "" : chunkMessage,
                descriptorBytes, descriptorDigest, planDigest, result.schema());
    }

    /** True when the set defines the fully qualified message (nested included). */
    static boolean containsMessage(FileDescriptorSet set, String fqName) {
        for (FileDescriptorProto file : set.getFileList()) {
            String prefix = file.getPackage().isEmpty() ? "" : file.getPackage() + ".";
            for (DescriptorProto message : file.getMessageTypeList()) {
                if (containsMessage(prefix + message.getName(), message, fqName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsMessage(String fqn, DescriptorProto message, String target) {
        if (fqn.equals(target)) {
            return true;
        }
        for (DescriptorProto nested : message.getNestedTypeList()) {
            if (containsMessage(fqn + "." + nested.getName(), nested, target)) {
                return true;
            }
        }
        return false;
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static class SchemaMetaJson {
        public String rootMessage;
        public String chunkMessage;

        public SchemaMetaJson() {}

        public SchemaMetaJson(String rootMessage, String chunkMessage) {
            this.rootMessage = rootMessage;
            this.chunkMessage = chunkMessage;
        }
    }
}
