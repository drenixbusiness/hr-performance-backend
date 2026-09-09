package uz.drenix.platform.security;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The second half of mTLS: the TLS layer proves the peer holds a key signed by our CA, this
 * interceptor decides whether that particular peer is allowed to call this service at all.
 *
 * <p>Without it, any certificate from the internal CA opens every service. With it, a stolen
 * edge-gateway certificate still cannot call user-service RPCs reserved for auth-service.
 *
 * <p>The accepted common name is published to the call context so that the few RPCs which run
 * before a user token exists can authorize on it — see {@link GrpcAuthContext#requirePeer}.
 */
public final class PeerIdentityInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PeerIdentityInterceptor.class);

    private final Set<String> allowedCommonNames;

    public PeerIdentityInterceptor(Set<String> allowedCommonNames) {
        this.allowedCommonNames = Set.copyOf(allowedCommonNames);
    }

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(
            ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {

        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            return deny(call, "mTLS required");
        }

        String commonName;
        try {
            X509Certificate peer = (X509Certificate) session.getPeerCertificates()[0];
            commonName = commonNameOf(peer.getSubjectX500Principal().getName());
        } catch (SSLPeerUnverifiedException | RuntimeException e) {
            return deny(call, "Peer certificate unusable");
        }

        if (!allowedCommonNames.contains(commonName)) {
            log.warn("Rejected peer CN={} calling {}", commonName, call.getMethodDescriptor().getFullMethodName());
            return deny(call, "Peer not allowed");
        }

        Context ctx = GrpcAuthContext.withPeer(commonName);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private static String commonNameOf(String distinguishedName) {
        for (String part : distinguishedName.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return "";
    }

    private <R, S> ServerCall.Listener<R> deny(ServerCall<R, S> call, String description) {
        call.close(Status.PERMISSION_DENIED.withDescription(description), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
