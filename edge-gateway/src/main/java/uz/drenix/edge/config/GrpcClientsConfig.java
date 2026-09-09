package uz.drenix.edge.config;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import uz.drenix.identity.grpc.v1.AuthServiceGrpc;
import uz.drenix.identity.grpc.v1.PerformanceServiceGrpc;
import uz.drenix.identity.grpc.v1.NotificationServiceGrpc;
import uz.drenix.identity.grpc.v1.ReportEventServiceGrpc;
import uz.drenix.identity.grpc.v1.RoleServiceGrpc;
import uz.drenix.identity.grpc.v1.SettingsServiceGrpc;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.platform.security.AuthClientInterceptor;
import uz.drenix.platform.security.DeadlineClientInterceptor;
import uz.drenix.platform.security.InternalTls;

/**
 * Outbound channels to the internal services.
 *
 * <p>Each stub carries an {@link AuthClientInterceptor} that forwards the end user's own access
 * token. The gateway therefore acts <em>as</em> the caller rather than with standing privileges of
 * its own — there is no service account here that could be borrowed to do more than the user can.
 */
@Configuration
public class GrpcClientsConfig {

    @Bean
    public SslContext internalSslContext(
            @Value("${drenix.tls.certificate}") String certificate,
            @Value("${drenix.tls.private-key}") String privateKey,
            @Value("${drenix.tls.ca}") String ca) throws Exception {
        return InternalTls.clientContext(certificate, privateKey, ca).build();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel userServiceChannel(SslContext sslContext,
                                             @Value("${drenix.clients.user-service.host}") String host,
                                             @Value("${drenix.clients.user-service.port}") int port) {
        return channel(sslContext, host, port, "user-service");
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel performanceServiceChannel(
            SslContext sslContext,
            @Value("${drenix.clients.performance-service.host}") String host,
            @Value("${drenix.clients.performance-service.port}") int port) {
        return channel(sslContext, host, port, "performance-service");
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel notificationServiceChannel(
            SslContext sslContext,
            @Value("${drenix.clients.notification-service.host}") String host,
            @Value("${drenix.clients.notification-service.port}") int port) {
        return channel(sslContext, host, port, "notification-service");
    }

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel authServiceChannel(SslContext sslContext,
                                             @Value("${drenix.clients.auth-service.host}") String host,
                                             @Value("${drenix.clients.auth-service.port}") int port) {
        return channel(sslContext, host, port, "auth-service");
    }

    // Deadlines are attached per call by DeadlineClientInterceptor. `stub.withDeadlineAfter` on a
    // singleton stub would compute one absolute instant at startup, after which every call fails
    // with DEADLINE_EXCEEDED.

    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userServiceStub(ManagedChannel userServiceChannel) {
        return UserServiceGrpc.newBlockingStub(userServiceChannel)
                .withInterceptors(forwardingInterceptor(), deadline(Duration.ofSeconds(10)));
    }

    @Bean
    public RoleServiceGrpc.RoleServiceBlockingStub roleServiceStub(ManagedChannel userServiceChannel) {
        return RoleServiceGrpc.newBlockingStub(userServiceChannel)
                .withInterceptors(forwardingInterceptor(), deadline(Duration.ofSeconds(10)));
    }

    @Bean
    public SettingsServiceGrpc.SettingsServiceBlockingStub settingsStub(ManagedChannel userServiceChannel) {
        return SettingsServiceGrpc.newBlockingStub(userServiceChannel)
                .withInterceptors(forwardingInterceptor(), deadline(Duration.ofSeconds(10)));
    }

    @Bean
    public AuthServiceGrpc.AuthServiceBlockingStub authServiceStub(ManagedChannel authServiceChannel) {
        return AuthServiceGrpc.newBlockingStub(authServiceChannel)
                // Login runs Argon2id downstream; give it room but not forever.
                .withInterceptors(forwardingInterceptor(), deadline(Duration.ofSeconds(15)));
    }

    @Bean
    public NotificationServiceGrpc.NotificationServiceBlockingStub notificationStub(
            ManagedChannel notificationServiceChannel) {
        return NotificationServiceGrpc.newBlockingStub(notificationServiceChannel)
                // One indexed read from a small table. If this is slow, something is wrong.
                .withInterceptors(forwardingInterceptor(), deadline(Duration.ofSeconds(10)));
    }

    @Bean
    public ReportEventServiceGrpc.ReportEventServiceBlockingStub reportEventStub(
            ManagedChannel notificationServiceChannel) {
        return ReportEventServiceGrpc.newBlockingStub(notificationServiceChannel)
                // One insert. It is called after the PDF is already built, and the caller does not
                // wait on it for anything they can see.
                .withInterceptors(forwardingInterceptor(), deadline(Duration.ofSeconds(10)));
    }

    @Bean
    public PerformanceServiceGrpc.PerformanceServiceBlockingStub performanceStub(
            ManagedChannel performanceServiceChannel) {
        return PerformanceServiceGrpc.newBlockingStub(performanceServiceChannel)
                // A cold cache means reading a few hundred rows out of monday.com; the board is
                // somebody else's service and occasionally slow.
                // A cold month of RingCentral is paged at ten requests a minute, so this one is
                // allowed to be slow. Warm calls come back in a quarter of a second.
                .withInterceptors(forwardingInterceptor(), deadline(Duration.ofMinutes(3)));
    }

    private static DeadlineClientInterceptor deadline(Duration timeout) {
        return new DeadlineClientInterceptor(timeout);
    }

    private static AuthClientInterceptor forwardingInterceptor() {
        return new AuthClientInterceptor(
                () -> {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    // Credentials hold the raw token exactly as presented. Anonymous calls
                    // (login, refresh) return null and travel without one.
                    return authentication == null ? null : (String) authentication.getCredentials();
                },
                () -> MDC.get("correlationId"));
    }

    private static ManagedChannel channel(SslContext sslContext, String host, int port, String authority) {
        return NettyChannelBuilder.forAddress(host, port)
                .negotiationType(NegotiationType.TLS)
                .sslContext(sslContext)
                .overrideAuthority(authority)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build();
    }
}
