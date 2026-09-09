package uz.drenix.identity.auth.web;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.identity.auth.token.SigningKeyProvider;

import java.time.Duration;

/**
 * Publishes the public signing keys so every other service can verify tokens without ever
 * contacting auth-service on the request path.
 *
 * <p>Only public key material is exposed — {@link com.nimbusds.jose.jwk.JWKSet#toJSONObject()}
 * emits the public view by default, and the private {@code d} parameter is never serialised here.
 * The endpoint is served on the internal mTLS port; it is not published to the internet.
 */
@RestController
public class JwksController {

    private final SigningKeyProvider keys;

    public JwksController(SigningKeyProvider keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                // Long enough to keep traffic low, short enough that a rotation propagates
                // within one cache window.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keys.publicJwks().toJSONObject());
    }
}
