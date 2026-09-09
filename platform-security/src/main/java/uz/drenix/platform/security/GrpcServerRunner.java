package uz.drenix.platform.security;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts and stops a gRPC server.
 *
 * <p>Written against plain grpc-java rather than a Spring gRPC starter: this is the only part of
 * the stack where the transport security is configured, and it should not depend on a starter's
 * property names or on its release cadence.
 *
 * <p>Shutdown is graceful with a deadline. Killing in-flight calls on redeploy turns a rolling
 * restart into a burst of client-side errors.
 */
public final class GrpcServerRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerRunner.class);
    private static final int SHUTDOWN_GRACE_SECONDS = 20;

    private final Server server;
    private final int port;

    public GrpcServerRunner(Config config,
                            List<BindableService> services,
                            List<ServerInterceptor> interceptors,
                            Executor executor) {
        this.port = config.port();

        NettyServerBuilder builder = NettyServerBuilder.forPort(config.port())
                .executor(executor)
                .maxInboundMessageSize(config.maxMessageBytes())
                // Reject clients that ping more often than this, so a misbehaving peer cannot
                // turn keepalive into a denial-of-service lever.
                .permitKeepAliveTime(30, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(false);

        services.forEach(builder::addService);
        // Interceptors run in reverse registration order, so register the outermost gate last.
        interceptors.forEach(builder::intercept);

        if (config.tlsEnabled()) {
            builder.sslContext(buildSslContext(config));
        } else {
            log.warn("gRPC server on port {} is starting WITHOUT TLS. Development only.", config.port());
        }

        this.server = builder.build();
    }

    private static SslContext buildSslContext(Config config) {
        try {
            return GrpcSslContexts
                    .configure(GrpcSslContexts.forServer(
                            new File(config.certificatePath()),
                            new File(config.privateKeyPath())),
                            // See InternalTls for why the JDK provider rather than the bundled
                            // BoringSSL one.
                            InternalTls.PROVIDER)
                    // Trust the internal CA only. The JDK's public trust store must never be able
                    // to authenticate an internal peer.
                    .trustManager(new File(config.caPath()))
                    .clientAuth(ClientAuth.REQUIRE)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build gRPC TLS context", e);
        }
    }

    public void start() {
        try {
            server.start();
            log.info("gRPC server listening on {}", port);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start gRPC server on port " + port, e);
        }
    }

    @Override
    public void close() {
        log.info("Shutting down gRPC server on {}", port);
        server.shutdown();
        try {
            if (!server.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("gRPC server did not drain in {}s; forcing shutdown", SHUTDOWN_GRACE_SECONDS);
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
    }

    /** Everything the server needs, so the service modules only supply values. */
    public record Config(
            int port,
            boolean tlsEnabled,
            String certificatePath,
            String privateKeyPath,
            String caPath,
            int maxMessageBytes) {

        public Config {
            if (tlsEnabled && (certificatePath == null || privateKeyPath == null || caPath == null)) {
                throw new IllegalArgumentException("TLS is enabled but certificate paths are missing");
            }
        }
    }
}
