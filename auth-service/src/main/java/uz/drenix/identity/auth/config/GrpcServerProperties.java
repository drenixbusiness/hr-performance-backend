package uz.drenix.identity.auth.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drenix.grpc")
public class GrpcServerProperties {

    private int port = 9092;
    private boolean tlsEnabled = true;
    private String certificate = "/etc/drenix/tls/auth-service.crt";
    private String privateKey = "/etc/drenix/tls/auth-service.key";
    private String ca = "/etc/drenix/tls/internal-ca.crt";

    /** Only the gateway may ask this service to authenticate someone. */
    private Set<String> allowedPeers = new LinkedHashSet<>(Set.of("edge-gateway"));

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
}
