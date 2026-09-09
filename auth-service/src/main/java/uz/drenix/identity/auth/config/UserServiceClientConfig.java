package uz.drenix.identity.auth.config;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.platform.security.DeadlineClientInterceptor;
import uz.drenix.platform.security.InternalTls;

/**
 * The channel to user-service, built by hand so the TLS material is explicit and reviewable.
 *
 * <p>mTLS both ways: we present auth-service's certificate and we verify user-service against the
 * internal CA only. The public trust store is deliberately not used — a certificate from any
 * public CA must not be able to impersonate an internal service.
 */
@Configuration
public class UserServiceClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel userServiceChannel(
            @Value("${drenix.clients.user-service.host}") String host,
            @Value("${drenix.clients.user-service.port}") int port,
            @Value("${drenix.tls.certificate}") String certificate,
            @Value("${drenix.tls.private-key}") String privateKey,
            @Value("${drenix.tls.ca}") String ca) throws Exception {

        SslContext sslContext = InternalTls.clientContext(certificate, privateKey, ca).build();

        return NettyChannelBuilder.forAddress(host, port)
                .negotiationType(NegotiationType.TLS)
                .sslContext(sslContext)
                // The certificate CN must match this name; without it a valid-but-wrong internal
                // certificate would be accepted.
                .overrideAuthority("user-service")
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build();
    }

    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userServiceStub(ManagedChannel userServiceChannel) {
        // No AuthClientInterceptor here: VerifyCredentials and the refresh-time GetUser both run
        // before a token exists and are authenticated by the peer certificate instead.
        //
        // The deadline is applied per call by the interceptor. Setting it on the stub would fix
        // one absolute instant at startup, after which every call fails DEADLINE_EXCEEDED.
        return UserServiceGrpc.newBlockingStub(userServiceChannel)
                .withInterceptors(new DeadlineClientInterceptor(Duration.ofSeconds(5)));
    }
}
