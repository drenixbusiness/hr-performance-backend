package uz.drenix.identity.auth.config;

import io.grpc.BindableService;
import io.grpc.ServerInterceptor;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.drenix.platform.security.ExceptionTranslatingInterceptor;
import uz.drenix.platform.security.GrpcServerRunner;
import uz.drenix.platform.security.PeerIdentityInterceptor;

/**
 * auth-service exposes only pre-authentication operations, so there is no token to verify on the
 * way in — by definition, Login and Refresh are what produce one.
 *
 * <p>That makes the mTLS peer check the whole gate here: only edge-gateway may reach this port.
 * Logout is the exception in spirit (the caller is authenticated) but the gateway has already
 * verified that token before forwarding, and the session id it passes is meaningless without it.
 */
@Configuration
@EnableConfigurationProperties(GrpcServerProperties.class)
public class GrpcServerConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    public GrpcServerRunner grpcServerRunner(GrpcServerProperties properties,
                                             List<BindableService> services) {

        List<ServerInterceptor> interceptors = List.of(
                new ExceptionTranslatingInterceptor(),
                new PeerIdentityInterceptor(properties.getAllowedPeers()));

        // Login blocks on Argon2id inside user-service; virtual threads keep the server
        // responsive while those calls are in flight.
        Executor executor = Executors.newVirtualThreadPerTaskExecutor();

        return new GrpcServerRunner(
                new GrpcServerRunner.Config(
                        properties.getPort(),
                        properties.isTlsEnabled(),
                        properties.getCertificate(),
                        properties.getPrivateKey(),
                        properties.getCa(),
                        1024 * 1024),
                services,
                interceptors,
                executor);
    }
}
