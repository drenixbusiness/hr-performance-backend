package uz.drenix.platform.security;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies the bearer token on every inbound RPC and publishes the principal to the call context.
 *
 * <p>Services do not trust the gateway to have done authorization: the original access token is
 * forwarded and re-verified here. A compromised gateway therefore cannot mint authority it was
 * never given, which is the difference between a perimeter and zero trust.
 */
public final class AuthServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthServerInterceptor.class);
    private static final String BEARER = "Bearer ";

    private final AccessTokenVerifier verifier;
    /** method full name -> the peer common names that may call it without a user token. */
    private final Map<String, Set<String>> tokenlessMethods;

    /**
     * @param tokenlessMethods fully qualified method names that may be called without a token,
     *                         each mapped to the peers allowed to do so. Keep this map short and
     *                         explicit: an entry here removes the user-level gate entirely and
     *                         leaves only the peer certificate.
     */
    public AuthServerInterceptor(AccessTokenVerifier verifier, Map<String, Set<String>> tokenlessMethods) {
        this.verifier = verifier;
        Map<String, Set<String>> copy = new HashMap<>();
        tokenlessMethods.forEach((method, peers) -> copy.put(method, Set.copyOf(peers)));
        this.tokenlessMethods = Map.copyOf(copy);
    }

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(
            ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {

        String method = call.getMethodDescriptor().getFullMethodName();
        String correlationId = headers.get(GrpcAuthContext.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        String header = headers.get(GrpcAuthContext.AUTHORIZATION);
        boolean hasToken = header != null && header.startsWith(BEARER);

        // A token, when one is present, always decides — even on a method that may also be called
        // without one. Reading the tokenless map first would make the exemption an override rather
        // than a fallback: an RPC listed there would ignore a perfectly good token and be judged
        // on the peer certificate alone, so the gateway forwarding a user's token would be refused
        // for not being auth-service.
        if (hasToken) {
            try {
                AuthPrincipal principal = verifier.verify(header.substring(BEARER.length()));
                Context ctx = GrpcAuthContext.withPrincipal(principal, correlationId);
                return Contexts.interceptCall(ctx, call, headers, next);
            } catch (UnauthenticatedException e) {
                log.debug("Token rejected on {} [{}]: {}", method, correlationId, e.getMessage());
                return deny(call, "Invalid token");
            }
        }

        // No token. A few RPCs run before one can exist — login, and the permission re-read during
        // a refresh — and for those the mTLS peer certificate is the whole authentication. The
        // allowlist is per RPC, so being an accepted peer of this service is not enough on its own.
        Set<String> tokenlessPeers = tokenlessMethods.get(method);
        if (tokenlessPeers == null) {
            return deny(call, "Missing bearer token");
        }
        String peer = GrpcAuthContext.peerCommonName();
        if (peer == null || !tokenlessPeers.contains(peer)) {
            log.warn("Peer CN={} may not call {} without a token", peer, method);
            return deny(call, "Missing bearer token");
        }
        Context ctx = Context.current().withValue(GrpcAuthContext.CORRELATION, correlationId);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private <R, S> ServerCall.Listener<R> deny(ServerCall<R, S> call, String description) {
        call.close(Status.UNAUTHENTICATED.withDescription(description), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
