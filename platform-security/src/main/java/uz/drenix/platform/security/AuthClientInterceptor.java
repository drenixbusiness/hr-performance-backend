package uz.drenix.platform.security;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.function.Supplier;

/**
 * Forwards the caller's access token and correlation id to the next service.
 *
 * <p>The token supplier is passed in rather than read from a static, so a background job with no
 * inbound request can supply its own service token explicitly instead of accidentally running
 * with whatever principal happened to be on the thread.
 */
public final class AuthClientInterceptor implements ClientInterceptor {

    private final Supplier<String> tokenSupplier;
    private final Supplier<String> correlationIdSupplier;

    public AuthClientInterceptor(Supplier<String> tokenSupplier, Supplier<String> correlationIdSupplier) {
        this.tokenSupplier = tokenSupplier;
        this.correlationIdSupplier = correlationIdSupplier;
    }

    @Override
    public <R, S> ClientCall<R, S> interceptCall(
            MethodDescriptor<R, S> method, CallOptions callOptions, Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<S> responseListener, Metadata headers) {
                String token = tokenSupplier.get();
                if (token != null && !token.isBlank()) {
                    headers.put(GrpcAuthContext.AUTHORIZATION, "Bearer " + token);
                }
                String correlationId = correlationIdSupplier.get();
                if (correlationId != null && !correlationId.isBlank()) {
                    headers.put(GrpcAuthContext.CORRELATION_ID, correlationId);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
