package uz.drenix.identity.auth.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jose.jwk.Curve;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the Ed25519 signing key and publishes the public half as a JWK set.
 *
 * <p>Ed25519 (EdDSA) rather than RSA: 64-byte signatures instead of 256, constant-time by
 * construction, and no key-size or padding decisions to get wrong. Tokens stay small, which
 * matters when every internal call carries one.
 *
 * <p>Rotation: {@code previousKeys} stay in the JWK set so tokens signed by a retired key keep
 * verifying until they expire. New tokens always use the current key. Rotating therefore means
 * "add a new current key, keep the old one for one access-token lifetime, then drop it".
 */
@Component
public class SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyProvider.class);

    private final OctetKeyPair currentKey;
    private final JWKSet publicJwks;

    public SigningKeyProvider(
            @Value("${drenix.auth.signing-key:}") String currentKeyJson,
            @Value("${drenix.auth.previous-keys:}") String previousKeysJson) throws Exception {

        this.currentKey = currentKeyJson.isBlank() ? generateDevelopmentKey() : OctetKeyPair.parse(currentKeyJson);

        List<com.nimbusds.jose.jwk.JWK> published = new ArrayList<>();
        published.add(currentKey.toPublicJWK());
        for (String previous : splitKeys(previousKeysJson)) {
            published.add(OctetKeyPair.parse(previous).toPublicJWK());
        }
        this.publicJwks = new JWKSet(published);
    }

    public OctetKeyPair currentKey() {
        return currentKey;
    }

    /** Public halves only — {@link JWKSet#toJSONObject()} defaults to the public view. */
    public JWKSet publicJwks() {
        return publicJwks;
    }

    private static OctetKeyPair generateDevelopmentKey() throws Exception {
        log.warn("""
                 No drenix.auth.signing-key configured; generating an ephemeral Ed25519 key.
                 Every restart invalidates all tokens. Never run production like this — \
                 generate a key with ./scripts/gen-signing-key.sh and pass it as a secret.""");
        return new OctetKeyPairGenerator(Curve.Ed25519)
                .keyID("dev-" + System.currentTimeMillis())
                .generate();
    }

    private static List<String> splitKeys(String json) throws ParseException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        // One JWK per line keeps the secret injectable as a single environment variable.
        return List.of(json.split("\\R")).stream().filter(s -> !s.isBlank()).toList();
    }
}
