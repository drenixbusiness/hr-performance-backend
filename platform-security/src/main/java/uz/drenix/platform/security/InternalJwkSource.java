package uz.drenix.platform.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds the JWKS source every service uses to verify access tokens.
 *
 * <p>The document is fetched over TLS from auth-service, and the trust anchor is the internal CA
 * only — never the JDK's public trust store. A JWKS document is the thing that decides which
 * signatures are valid, so anyone able to substitute it can mint tokens; trusting several hundred
 * public CAs for that fetch would undo the rest of the token design.
 *
 * <p>The source caches and rate limits, so a burst of unknown key ids cannot become a request
 * flood against auth-service.
 */
public final class InternalJwkSource {

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 3_000;
    private static final int SIZE_LIMIT_BYTES = 128 * 1024;

    private InternalJwkSource() {
    }

    /**
     * @param jwksUri the JWKS endpoint; {@code https} is expected, and then {@code caPath} must
     *                point at the internal CA certificate.
     * @param caPath  PEM file holding the internal CA certificate.
     */
    public static JWKSource<SecurityContext> create(String jwksUri, String caPath) {
        URL url;
        try {
            url = URI.create(jwksUri).toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JWKS URI: " + jwksUri, e);
        }

        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS,
                SIZE_LIMIT_BYTES,
                true,
                "https".equalsIgnoreCase(url.getProtocol()) ? socketFactory(caPath) : null);

        return JWKSourceBuilder.<SecurityContext>create(url, retriever)
                .retrying(true)
                .rateLimited(true)
                .build();
    }

    private static SSLSocketFactory socketFactory(String caPath) {
        if (caPath == null || caPath.isBlank()) {
            throw new IllegalStateException("An https JWKS URI needs the internal CA certificate path");
        }
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);

            CertificateFactory certificates = CertificateFactory.getInstance("X.509");
            try (InputStream in = Files.newInputStream(Path.of(caPath))) {
                Collection<? extends java.security.cert.Certificate> chain =
                        certificates.generateCertificates(in);
                if (chain.isEmpty()) {
                    throw new IllegalStateException("No certificate found in " + caPath);
                }
                int index = 0;
                for (java.security.cert.Certificate certificate : chain) {
                    String alias = certificate instanceof X509Certificate x509
                            ? x509.getSubjectX500Principal().getName() + "-" + index
                            : "ca-" + index;
                    trustStore.setCertificateEntry(alias, certificate);
                    index++;
                }
            }

            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers.getTrustManagers(), null);
            return sslContext.getSocketFactory();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to trust the internal CA at " + caPath, e);
        }
    }
}
