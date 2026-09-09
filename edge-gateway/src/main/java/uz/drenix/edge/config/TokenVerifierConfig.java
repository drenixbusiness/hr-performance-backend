package uz.drenix.edge.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.drenix.edge.security.GatewayProperties;
import uz.drenix.platform.security.AccessTokenVerifier;
import uz.drenix.platform.security.InternalJwkSource;

@Configuration
public class TokenVerifierConfig {

    /**
     * The JWKS document is fetched over TLS and verified against the internal CA only. Trusting
     * the public trust store here would mean any publicly issued certificate for the auth-service
     * name could hand this gateway a key set of the attacker's choosing.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(GatewayProperties properties,
                                                @Value("${drenix.tls.ca}") String ca) {
        return InternalJwkSource.create(properties.getJwksUri(), ca);
    }

    @Bean
    public AccessTokenVerifier accessTokenVerifier(JWKSource<SecurityContext> jwkSource,
                                                   GatewayProperties properties) {
        return new AccessTokenVerifier(jwkSource, properties.getIssuer(), properties.getAudience());
    }
}
