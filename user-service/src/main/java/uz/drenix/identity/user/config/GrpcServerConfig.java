package uz.drenix.identity.user.config;

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
 * The gRPC server and its two independent gates.
 *
 * <ol>
 *   <li>{@link PeerIdentityInterceptor} — is the calling <em>service</em> allowed here? Answered
 *       by the mTLS peer certificate.</li>
 *   <li>{@link AuthServerInterceptor} — is the calling <em>user</em> allowed? Answered by
 *       verifying the forwarded access token here, not by trusting the gateway.</li>
 * </ol>
 *
 * A stolen gateway certificate gets past the first gate and no further.
 */
@Configuration
@EnableConfigurationProperties(GrpcServerProperties.class)
public class GrpcServerConfig {

    /**
     * The RPCs that run before a user token exists, each pinned to the peer allowed to make them.
     * Their only authentication is the mTLS peer certificate, so this map stays as short as the
     * login and refresh flows require.
     */
    private static final Map<String, Set<String>> TOKENLESS_METHODS = Map.of(
            "drenix.identity.v1.UserService/VerifyCredentials", Set.of("auth-service"),
            // Refresh re-reads the user permissions before minting the next access token, and the
            // expired one it is replacing cannot authorize that read. auth-service only.
            "drenix.identity.v1.UserService/GetUser", Set.of("auth-service"),

            // notification-service runs on a schedule, not inside anybody's request, so it has no
            // user token to forward. These three are reads it needs to decide who should be told
            // what; it can write nothing through them.
            "drenix.identity.v1.UserService/ListUsers",
            Set.of("notification-service", "performance-service"),
            "drenix.identity.v1.UserService/ListAudit", Set.of("notification-service"),
            "drenix.identity.v1.SettingsService/GetActivityStandard",
            Set.of("notification-service"));

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

        // Interceptors run in reverse registration order, so the peer check — registered last —
        // is the outermost and rejects an unknown service before anything else runs.
        List<ServerInterceptor> interceptors = List.of(
                new ExceptionTranslatingInterceptor(),
                new AuthServerInterceptor(verifier, TOKENLESS_METHODS),
                new PeerIdentityInterceptor(properties.getAllowedPeers()));

        // Virtual threads: Argon2id verification blocks for tens of milliseconds, and a fixed
        // pool would queue behind it.
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
