package uz.drenix.identity.performance.config;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.platform.security.DeadlineClientInterceptor;
import uz.drenix.platform.security.InternalTls;

/**
 * A read-only channel to user-service.
 *
 * <p>Only the snapshot job uses it, and only to answer "whose numbers am I recording". It runs on
 * a schedule rather than inside somebody's request, so there is no user token to forward: it
 * authenticates as a <em>service</em> with the mTLS certificate, and {@code ListUsers} is
 * allowlisted for this peer on the other side.
 */
@Configuration
public class UserDirectoryClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel userServiceChannel(
            @Value("${drenix.grpc.certificate}") String certificate,
            @Value("${drenix.grpc.private-key}") String privateKey,
            @Value("${drenix.grpc.ca}") String ca,
            @Value("${drenix.clients.user-service.host:user-service}") String host,
            @Value("${drenix.clients.user-service.port:9090}") int port) throws Exception {

        SslContext sslContext = InternalTls.clientContext(certificate, privateKey, ca).build();
        return NettyChannelBuilder.forAddress(host, port)
                .negotiationType(NegotiationType.TLS)
                .sslContext(sslContext)
                .overrideAuthority("user-service")
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build();
    }

    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userServiceStub(ManagedChannel userServiceChannel) {
        return UserServiceGrpc.newBlockingStub(userServiceChannel)
                .withInterceptors(new DeadlineClientInterceptor(Duration.ofSeconds(20)));
    }
}
