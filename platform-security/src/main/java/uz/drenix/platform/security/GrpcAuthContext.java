package uz.drenix.platform.security;

import io.grpc.Context;
import io.grpc.Metadata;
import java.util.Set;

/**
 * Carries the verified caller across a gRPC call.
 *
 * <p>{@link Context} rather than a ThreadLocal: gRPC hands work to different threads between the
 * interceptor and the service method, and Context is the only thing that survives that hop
 * (including across virtual threads).
 */
public final class GrpcAuthContext {

    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> CORRELATION_ID =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    static final Context.Key<AuthPrincipal> PRINCIPAL = Context.key("drenix.principal");
    static final Context.Key<String> CORRELATION = Context.key("drenix.correlationId");
    static final Context.Key<String> PEER = Context.key("drenix.peer");

    private GrpcAuthContext() {
    }

    /** @throws UnauthenticatedException when the call arrived without a verified principal. */
    public static AuthPrincipal current() {
        AuthPrincipal principal = PRINCIPAL.get();
        if (principal == null) {
            throw new UnauthenticatedException("No authenticated principal on this call");
        }
        return principal;
    }

    /**
     * The principal, or {@code null} on a call that carried no user token. Only the handful of
     * RPCs that legitimately run before a token exists should use this; everything else goes
     * through {@link #require(String)}.
     */
    public static AuthPrincipal principalOrNull() {
        return PRINCIPAL.get();
    }

    public static String correlationId() {
        String id = CORRELATION.get();
        return id == null ? "-" : id;
    }

    /** The common name from the peer certificate, or {@code null} when the call was not over mTLS. */
    public static String peerCommonName() {
        return PEER.get();
    }

    /**
     * The single authorization gate. Every mutating RPC calls this as its first statement, so
     * an unguarded method is visible in review as a missing line rather than as a subtle
     * annotation that silently did nothing.
     */
    public static AuthPrincipal require(String permission) {
        AuthPrincipal principal = current();
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException(permission);
        }
        return principal;
    }

    /**
     * Any one of several permissions is enough.
     *
     * <p>For reads that several tiers of a hierarchy may perform: an HR reading their own figures
     * and a CEO reading everybody's are the same RPC, differing only in whose numbers the caller
     * was allowed to name.
     */
    public static AuthPrincipal requireAny(String... permissions) {
        AuthPrincipal principal = current();
        for (String permission : permissions) {
            if (principal.hasPermission(permission)) {
                return principal;
            }
        }
        throw new AccessDeniedException(String.join(" or ", permissions));
    }

    /**
     * A user permission, or an allowlisted internal peer.
     *
     * <p>For reads that a person makes through the gateway and a background service also makes on
     * its own schedule. A peer-authenticated call carries no principal, and it only reached this
     * method because { AuthServerInterceptor} found this exact RPC in the tokenless map for
     * this exact peer — so there is nothing further to check here.
     *
     * <p>Only ever use this on a read. A peer call has no user, so there is nobody whose
     * permissions could bound a write, and nobody to name in the audit log for it.
     */
    public static void requirePermissionOrPeer(String permission) {
        AuthPrincipal principal = PRINCIPAL.get();
        if (principal == null) {
            return;
        }
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException(permission);
        }
    }

    /** Permission plus company scope, for data that belongs to one entity. */
    public static AuthPrincipal require(String permission, AuthPrincipal.EntityScope entity) {
        AuthPrincipal principal = require(permission);
        if (!principal.canActOn(entity)) {
            throw new AccessDeniedException(permission + "@" + entity);
        }
        return principal;
    }

    /**
     * Authorizes a service-to-service call by peer certificate rather than by user token, for the
     * few RPCs that run before a token exists. The allowlist is per RPC, so being an allowed peer
     * of this service is not by itself enough.
     */
    public static void requirePeer(Set<String> allowedCommonNames) {
        String peer = PEER.get();
        if (peer == null || !allowedCommonNames.contains(peer)) {
            throw new AccessDeniedException("peer:" + (peer == null ? "unknown" : peer));
        }
    }

    public static Context withPrincipal(AuthPrincipal principal, String correlationId) {
        return Context.current().withValue(PRINCIPAL, principal).withValue(CORRELATION, correlationId);
    }

    public static Context withPeer(String commonName) {
        return Context.current().withValue(PEER, commonName);
    }
}
