package org.apache.lucene.collab.node;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * DJL Serving REST client. Returns String to avoid Content-Type deserialization issues
 * (DJL may not set Content-Type); parsing done in DjlEmbeddingClient.
 */
@RegisterRestClient(configKey = "djl-api")
public interface DjlService {

    @POST
    @Path("/predictions/bge_m3")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<String> getEmbeddingsRaw(List<String> text);
}
