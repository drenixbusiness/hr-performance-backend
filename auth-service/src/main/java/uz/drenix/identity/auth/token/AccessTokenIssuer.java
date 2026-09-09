package uz.drenix.identity.auth.token;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mints access tokens.
 *
 * <p>Ten minutes by default. Short enough that a leaked token is a small window, long enough that
 * refresh traffic stays sane. Anything that must take effect faster than ten minutes — a firing, a
 * compromised account — goes through the session denylist rather than through a shorter TTL.
 */
@Component
public class AccessTokenIssuer {

    public record IssuedToken(String token, String tokenId, Instant expiresAt) {
    }

    private final SigningKeyProvider keys;
    private final String issuer;
    private final String audience;
    private final Duration ttl;

    public AccessTokenIssuer(
            SigningKeyProvider keys,
            @Value("${drenix.auth.issuer}") String issuer,
            @Value("${drenix.auth.audience}") String audience,
            @Value("${drenix.auth.access-token-ttl:PT10M}") Duration ttl) {
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = ttl;
    }

    public IssuedToken issue(UUID userId, String username, String sessionId,
                             Collection<String> permissions, Collection<String> entityScopes) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String tokenId = UUID.randomUUID().toString();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(userId.toString())
                .claim("preferred_username", username)
                .claim("sid", sessionId)
                .claim("perm", List.copyOf(permissions))
                .claim("ent", List.copyOf(entityScopes))
                .jwtID(tokenId)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                            .keyID(keys.currentKey().getKeyID())
                            // Typed tokens: a verifier that demands at+jwt cannot be handed a
                            // token minted for another purpose.
                            .type(new JOSEObjectType("at+jwt"))
                            .build(),
                    claims);
            jwt.sign(new Ed25519Signer(keys.currentKey()));
            return new IssuedToken(jwt.serialize(), tokenId, expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign access token", e);
        }
    }

    public Duration ttl() {
        return ttl;
    }
}
