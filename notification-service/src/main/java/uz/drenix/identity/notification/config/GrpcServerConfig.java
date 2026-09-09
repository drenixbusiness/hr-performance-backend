package uz.drenix.identity.notification.config;

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
import uz.drenix.platform.security.AccessTokenVerifier;
import uz.drenix.platform.security.AuthServerInterceptor;
import uz.drenix.platform.security.ExceptionTranslatingInterceptor;
import uz.drenix.platform.security.GrpcServerRunner;
import uz.drenix.platform.security.InternalJwkSource;
import uz.drenix.platform.security.PeerIdentityInterceptor;

/**
 * The gRPC server and its two gates, the same pair every service here uses: the peer certificate
 * says which <em>service</em> may call, the access token says which <em>user</em>.
 *
 * <p>Nothing is tokenless. Every RPC this service offers answers "what am I being told about",
 * and the answer depends entirely on who is asking.
 */
@Configuration
@EnableConfigurationProperties(GrpcServerProperties.class)
public class GrpcServerConfig {

    private static final Map<String, Set<String>> TOKENLESS_METHODS = Map.of();

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

        List<ServerInterceptor> interceptors = List.of(
                new ExceptionTranslatingInterceptor(),
                new AuthServerInterceptor(verifier, TOKENLESS_METHODS),
                new PeerIdentityInterceptor(properties.getAllowedPeers()));

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
