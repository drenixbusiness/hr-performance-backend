package uz.drenix.identity.notification.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drenix.grpc")
public class GrpcServerProperties {

    private int port = 9096;
    private boolean tlsEnabled = true;
    private String certificate = "/etc/drenix/tls/notification-service.crt";
    private String privateKey = "/etc/drenix/tls/notification-service.key";
    private String ca = "/etc/drenix/tls/internal-ca.crt";

    /** Certificate common names permitted to call this service. */
    private Set<String> allowedPeers = new LinkedHashSet<>(Set.of("edge-gateway"));

    private String jwksUri = "https://auth-service:9443/.well-known/jwks.json";
    private String issuer = "https://auth.drenix.uz";
    private String audience = "drenix-internal";

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }
    public String getCertificate() { return certificate; }
    public void setCertificate(String certificate) { this.certificate = certificate; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public String getCa() { return ca; }
    public void setCa(String ca) { this.ca = ca; }
    public Set<String> getAllowedPeers() { return allowedPeers; }
    public void setAllowedPeers(Set<String> allowedPeers) { this.allowedPeers = allowedPeers; }
    public String getJwksUri() { return jwksUri; }
    public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
}
