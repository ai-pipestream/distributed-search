package ai.pipestream.search.grpc;

import io.grpc.*;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * gRPC ServerInterceptor that propagates client cancellation down to Mutiny/Context
 * listeners and logs cancelled queries.
 */
@GlobalInterceptor
@ApplicationScoped
public class QueryCancellationInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(QueryCancellationInterceptor.class);

    public static final Context.Key<Boolean> CANCELLED_KEY = Context.key("cancelled");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        Context context = Context.current();
        ServerCall.Listener<ReqT> listener = next.startCall(call, headers);

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onCancel() {
                LOG.debugf("gRPC call cancelled by remote client: %s", call.getMethodDescriptor().getFullMethodName());
                super.onCancel();
            }
        };
    }
}
