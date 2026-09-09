package uz.drenix.platform.security;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Applies a per-call deadline.
 *
 * <p>{@code stub.withDeadlineAfter(...)} looks like it does this, but a gRPC deadline is an
 * <em>absolute</em> point in time computed when it is set. On a stub held as a singleton bean that
 * point is fixed at startup, so every call made after it passes fails with DEADLINE_EXCEEDED. The
 * deadline has to be attached when the call is created, which is what this interceptor does.
 */
public final class DeadlineClientInterceptor implements ClientInterceptor {

    private final long timeoutMillis;

    public DeadlineClientInterceptor(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Deadline must be positive");
        }
        this.timeoutMillis = timeout.toMillis();
    }

    @Override
    public <R, S> ClientCall<R, S> interceptCall(
            MethodDescriptor<R, S> method, CallOptions callOptions, Channel next) {

        // A caller that already set a tighter deadline keeps it.
        CallOptions withDeadline = callOptions.getDeadline() == null
                ? callOptions.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS)
                : callOptions;
        return next.newCall(method, withDeadline);
    }
}
