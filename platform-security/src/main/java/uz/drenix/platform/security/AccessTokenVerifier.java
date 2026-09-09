package uz.drenix.platform.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies access tokens against the auth-service JWKS.
 *
 * <p>Decisions that matter:
 * <ul>
 *   <li>The algorithm is pinned to EdDSA. Trusting the header's {@code alg} is how "alg: none"
 *       and RS256/HS256 confusion attacks work.</li>
 *   <li>The type header must be {@code at+jwt}, so a token minted for another purpose cannot be
 *       replayed as an access token.</li>
 *   <li>Issuer, audience and the claims we depend on are required, not optional.</li>
 *   <li>Clock skew is 30 seconds. A wider window extends the life of a revoked token.</li>
 * </ul>
 *
 * <p>The signature check is written out here rather than delegated to {@code DefaultJWTProcessor}.
 * That processor selects keys through {@code JWSKeySelector}, whose contract returns
 * {@code java.security.Key}, and an Ed25519 JWK cannot become one: nimbus answers
 * {@code OctetKeyPair.toPublicKey()} with "Export to java.security.PublicKey not supported" (still
 * true in 9.40, 9.48 and 10.5). The key is then silently dropped and every token is refused as
 * "no matching key(s) found" — a signature failure indistinguishable from a real one. Selecting
 * the {@link OctetKeyPair} directly and handing it to {@link Ed25519Verifier} is the supported
 * path for OKP keys, and it keeps each check visible in one method.
 */
public final class AccessTokenVerifier {

    private static final JOSEObjectType ACCESS_TOKEN_TYPE = new JOSEObjectType("at+jwt");
    private static final int MAX_CLOCK_SKEW_SECONDS = 30;
    private static final Set<String> REQUIRED_CLAIMS =
            Set.of("sub", "sid", "jti", "exp", "iat", "perm");

    private final JWKSource<SecurityContext> jwkSource;
    private final DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier;

    public AccessTokenVerifier(JWKSource<SecurityContext> jwkSource, String issuer, String audience) {
        this.jwkSource = jwkSource;

        // Neither set may be a Set.of(...) here. To decide whether a token with no audience is
        // acceptable, nimbus asks the sets whether they contain null, and the immutable sets
        // from Set.of throw NullPointerException on contains(null) instead of answering false.
        this.claimsVerifier = new DefaultJWTClaimsVerifier<>(
                Collections.singleton(audience),
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                new HashSet<>(REQUIRED_CLAIMS),
                null);
        this.claimsVerifier.setMaxClockSkew(MAX_CLOCK_SKEW_SECONDS);
    }

    /**
     * @throws UnauthenticatedException when the signature, type, timing or claim set is wrong.
     *         The cause is kept for logs, but the message stays generic: telling a caller
     *         <em>why</em> their token failed helps them forge a better one.
     */
    public AuthPrincipal verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();

            if (!JWSAlgorithm.EdDSA.equals(header.getAlgorithm())) {
                throw new IllegalArgumentException("Unexpected JWS algorithm: " + header.getAlgorithm());
            }
            if (!ACCESS_TOKEN_TYPE.equals(header.getType())) {
                throw new IllegalArgumentException("Not an access token: typ=" + header.getType());
            }
            if (!verifySignature(jwt, header)) {
                throw new IllegalArgumentException("Signature does not verify");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            claimsVerifier.verify(claims, null);
            return toPrincipal(claims);
        } catch (Exception e) {
            throw new UnauthenticatedException("Access token rejected", e);
        }
    }

    /**
     * True when the signature verifies under one of the published keys. Several can match while a
     * signing key is being rotated, so each candidate is tried before giving up.
     */
    private boolean verifySignature(SignedJWT jwt, JWSHeader header) throws Exception {
        // forJWSHeader pins key type, use, algorithm, curve and key id together, so a key
        // published for another purpose cannot be borrowed to validate an access token.
        JWKMatcher matcher = new JWKMatcher.Builder()
                .keyType(com.nimbusds.jose.jwk.KeyType.OKP)
                .curves(Curve.Ed25519)
                .keyUses(com.nimbusds.jose.jwk.KeyUse.SIGNATURE, null)
                .algorithms(JWSAlgorithm.EdDSA, null)
                .keyID(header.getKeyID())
                .build();

        List<JWK> candidates = jwkSource.get(new JWKSelector(matcher), null);
        for (JWK candidate : candidates) {
            if (candidate instanceof OctetKeyPair okp
                    && jwt.verify(new Ed25519Verifier(okp.toPublicJWK()))) {
                return true;
            }
        }
        return false;
    }

    private static AuthPrincipal toPrincipal(JWTClaimsSet claims) throws Exception {
        List<String> permissionList = claims.getStringListClaim("perm");
        Set<String> permissions = permissionList == null ? Set.of() : Set.copyOf(permissionList);

        List<String> rawScopes = claims.getStringListClaim("ent");
        Set<AuthPrincipal.EntityScope> scopes;
        if (rawScopes == null || rawScopes.isEmpty()) {
            // No entity claim means no company access, not "everything". Failing closed is the point.
            scopes = Set.of();
        } else {
            Set<AuthPrincipal.EntityScope> parsed = new LinkedHashSet<>();
            for (String scope : rawScopes) {
                parsed.add(AuthPrincipal.EntityScope.valueOf(scope));
            }
            scopes = Set.copyOf(parsed);
        }

        return new AuthPrincipal(
                UUID.fromString(claims.getSubject()),
                claims.getStringClaim("preferred_username"),
                claims.getStringClaim("sid"),
                claims.getJWTID(),
                permissions,
                scopes);
    }
}
