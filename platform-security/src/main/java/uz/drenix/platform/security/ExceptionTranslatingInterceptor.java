package uz.drenix.platform.security;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns exceptions thrown by service methods into gRPC statuses.
 *
 * <p>Only messages we wrote are sent back. Anything unexpected collapses to a bare INTERNAL:
 * a stack trace or a SQL fragment in an error response is a free map of the system.
 *
 * <p>Uses plain grpc-java interfaces rather than a framework's exception-handler abstraction, so
 * it cannot break when that framework reorganises its packages.
 */
public final class ExceptionTranslatingInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ExceptionTranslatingInterceptor.class);

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(
            ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {

        AtomicBoolean closed = new AtomicBoolean(false);
        ServerCall<R, S> guarded = new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                closed.set(true);
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<R> delegate = next.startCall(guarded, headers);

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onHalfClose() {
                run(super::onHalfClose);
            }

            @Override
            public void onMessage(R message) {
                run(() -> super.onMessage(message));
            }

            @Override
            public void onReady() {
                run(super::onReady);
            }

            private void run(Runnable action) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    // If the service already closed the call, closing again throws; that path is
                    // a lost race, not an error worth surfacing.
                    if (closed.compareAndSet(false, true)) {
                        guarded.close(translate(e), new Metadata());
                    }
                }
            }
        };
    }

    private static Status translate(RuntimeException exception) {
        return switch (exception) {
            // A service that threw a StatusRuntimeException already decided what this is. Passing
            // it through is the whole point of having chosen; translating it again would flatten
            // every deliberate RESOURCE_EXHAUSTED and FAILED_PRECONDITION into a bare INTERNAL.
            case io.grpc.StatusRuntimeException e -> e.getStatus();
            case UnauthenticatedException e ->
                    Status.UNAUTHENTICATED.withDescription("Authentication required");
            case AccessDeniedException e ->
                    Status.PERMISSION_DENIED.withDescription(e.getMessage());
            case AlreadyExistsException e ->
                    Status.ALREADY_EXISTS.withDescription(e.getMessage());
            case NoSuchElementException e ->
                    Status.NOT_FOUND.withDescription(e.getMessage());
            case IllegalArgumentException e ->
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage());
            // Somebody else's service is down. The description is written by us, not by the
            // failure, so it is safe to pass through — and the caller needs to know it is worth
            // retrying rather than worth reporting.
            case UpstreamUnavailableException e ->
                    Status.UNAVAILABLE.withDescription(e.getMessage());
            default -> {
                log.error("Unhandled error in gRPC call", exception);
                yield Status.INTERNAL.withDescription("Internal error");
            }
        };
    }
}
