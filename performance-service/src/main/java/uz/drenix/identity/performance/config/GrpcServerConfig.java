package uz.drenix.identity.performance.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.grpc.BindableService;
import io.grpc.ServerInterceptor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.drenix.identity.performance.monday.MondayProperties;
import uz.drenix.identity.performance.ringcentral.RingCentralProperties;
import uz.drenix.platform.security.AccessTokenVerifier;
import uz.drenix.platform.security.AuthServerInterceptor;
import uz.drenix.platform.security.ExceptionTranslatingInterceptor;
import uz.drenix.platform.security.GrpcServerRunner;
import uz.drenix.platform.security.InternalJwkSource;
import uz.drenix.platform.security.PeerIdentityInterceptor;

/**
 * The same two gates every internal service uses: the mTLS peer allowlist decides which service
 * may call, and the forwarded access token decides which person may.
 *
 * <p>Nothing is exempt here. Unlike user-service, this service has no RPC that runs before a token
 * exists, so the tokenless map is empty and stays that way.
 */
@Configuration
@EnableConfigurationProperties({GrpcServerProperties.class, MondayProperties.class,
        uz.drenix.identity.performance.ai.AiProperties.class,
                              RingCentralProperties.class})
public class GrpcServerConfig {

    /**
     * notification-service reads the activity report on a schedule, so there is no user token to
     * forward. It authenticates as a peer over mTLS, and only for this one read.
     */
    private static final Map<String, Set<String>> TOKENLESS_METHODS = Map.of(
            "drenix.identity.v1.PerformanceService/GetHrActivity", Set.of("notification-service"));

    @Bean
    public JWKSource<SecurityContext> jwkSource(GrpcServerProperties properties) {
        return InternalJwkSource.create(properties.getJwksUri(), properties.getCa());
    }

    @Bean
    public AccessTokenVerifier accessTokenVerifier(JWKSource<SecurityContext> jwkSource,
                                                   GrpcServerProperties properties) {
        return new AccessTokenVerifier(jwkSource, properties.getIssuer(), properties.getAudience());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public GrpcServerRunner grpcServerRunner(GrpcServerProperties properties,
                                             List<BindableService> services,
                                             AccessTokenVerifier verifier) {

        // Reverse registration order: the peer check is registered last and so runs first.
        List<ServerInterceptor> interceptors = List.of(
                new ExceptionTranslatingInterceptor(),
                new AuthServerInterceptor(verifier, TOKENLESS_METHODS),
                new PeerIdentityInterceptor(properties.getAllowedPeers()));

        // Virtual threads: a chart request may block on monday.com for a second or two, and a
        // fixed pool would queue every other caller behind it.
        Executor executor = Executors.newVirtualThreadPerTaskExecutor();

        return new GrpcServerRunner(
                new GrpcServerRunner.Config(
                        properties.getPort(),
                        properties.isTlsEnabled(),
                        properties.getCertificate(),
                        properties.getPrivateKey(),
                        properties.getCa(),
                        4 * 1024 * 1024),
                services,
                interceptors,
                executor);
    }
}
