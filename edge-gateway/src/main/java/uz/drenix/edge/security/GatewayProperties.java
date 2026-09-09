package uz.drenix.edge.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "drenix.gateway")
public class GatewayProperties {

    /** Exact origins allowed to call the API from a browser. No wildcards. */
    private List<String> allowedOrigins = List.of("http://localhost:3005");

    private String issuer = "https://auth.drenix.uz";
    private String audience = "drenix-internal";
    private String jwksUri = "https://auth-service:9443/.well-known/jwks.json";

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getJwksUri() { return jwksUri; }
    public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }
}
