package uz.drenix.platform.security;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import java.io.File;

/**
 * One place to decide how internal mTLS contexts are built, so client and server cannot drift.
 *
 * <p>The provider is pinned to {@link SslProvider#JDK} rather than the BoringSSL build that
 * grpc-netty-shaded bundles. With the native provider the server aborts every handshake:
 *
 * <pre>
 *   SSLHandshakeException: Unable to find key material for auth method(s):
 *       [ECDHE_ECDSA, ECDHE_RSA, ...]
 * </pre>
 *
 * <p>Netty's native path asks the JDK key manager for the legacy composite key types
 * ({@code EC_EC}, {@code EC_RSA}) through {@code chooseEngineServerAlias}, passing its own
 * non-JSSE engine. On this JDK that lookup comes back empty even though the key manager holds a
 * perfectly good EC key — the client then sees a bare {@code TLSV1_ALERT_INTERNAL_ERROR} with no
 * hint of the cause. The JDK provider runs the whole handshake inside JSSE and selects the key
 * itself, which works.
 *
 * <p>The cost is throughput: JSSE is slower than BoringSSL. That is an acceptable trade for
 * internal calls, and the alternative is a transport that does not connect at all.
 */
public final class InternalTls {

    public static final SslProvider PROVIDER = SslProvider.JDK;

    private InternalTls() {
    }

    /**
     * A client context presenting this service's own certificate and trusting the internal CA
     * only. The public trust store is deliberately not consulted: a certificate from any public
     * CA must not be able to impersonate an internal service.
     */
    public static SslContextBuilder clientContext(String certificate, String privateKey, String ca) {
        return GrpcSslContexts.configure(GrpcSslContexts.forClient(), PROVIDER)
                .keyManager(new File(certificate), new File(privateKey))
                .trustManager(new File(ca));
    }
}
