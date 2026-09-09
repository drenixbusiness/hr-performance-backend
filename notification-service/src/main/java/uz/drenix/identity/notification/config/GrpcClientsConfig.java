package uz.drenix.identity.notification.config;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.drenix.identity.grpc.v1.PerformanceServiceGrpc;
import uz.drenix.identity.grpc.v1.SettingsServiceGrpc;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.platform.security.DeadlineClientInterceptor;
import uz.drenix.platform.security.InternalTls;

/**
 * Outbound channels.
 *
 * <p>No token is forwarded on these calls, and there is nobody to forward one from: the generators
 * run on a schedule, not inside somebody's request. They authenticate as a <em>service</em>, with
 * the mTLS certificate, and the RPCs they use are allowlisted for this peer on the other side.
 *
 * <p>That is why this service reads and never writes through them. A peer-authenticated call
 * carries no user, so there is no one whose permissions could bound a write.
 */
@Configuration
public class GrpcClientsConfig {

    @Bean
    public SslContext internalSslContext(
            @Value("${drenix.grpc.certificate}") String certificate,
            @Value("${drenix.grpc.private-key}") String privateKey,
            @Value("${drenix.grpc.ca}") String ca) throws Exception {
        return InternalTls.clientContext(certificate, privateKey, ca).build();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel userServiceChannel(SslContext sslContext, UpstreamProperties upstream) {
        return channel(sslContext, upstream.getUserService(), "user-service");
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel performanceServiceChannel(SslContext sslContext,
                                                    UpstreamProperties upstream) {
        return channel(sslContext, upstream.getPerformanceService(), "performance-service");
    }

    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userServiceStub(ManagedChannel userServiceChannel) {
        return UserServiceGrpc.newBlockingStub(userServiceChannel)
                .withInterceptors(new DeadlineClientInterceptor(Duration.ofSeconds(20)));
    }

    @Bean
    public SettingsServiceGrpc.SettingsServiceBlockingStub settingsStub(
            ManagedChannel userServiceChannel) {
        return SettingsServiceGrpc.newBlockingStub(userServiceChannel)
                .withInterceptors(new DeadlineClientInterceptor(Duration.ofSeconds(10)));
    }

    @Bean
    public PerformanceServiceGrpc.PerformanceServiceBlockingStub performanceStub(
            ManagedChannel performanceServiceChannel) {
        return PerformanceServiceGrpc.newBlockingStub(performanceServiceChannel)
                // A cold shift is paged out of RingCentral at ten requests a minute. Nobody is
                // waiting on this — it runs at half past four in the morning.
                .withInterceptors(new DeadlineClientInterceptor(Duration.ofMinutes(5)));
    }

    private static ManagedChannel channel(SslContext sslContext,
                                          UpstreamProperties.Endpoint endpoint,
                                          String authority) {
        return NettyChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .negotiationType(NegotiationType.TLS)
                .sslContext(sslContext)
                .overrideAuthority(authority)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build();
    }
}
