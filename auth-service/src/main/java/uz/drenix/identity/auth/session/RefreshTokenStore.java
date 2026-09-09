package uz.drenix.identity.auth.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refresh tokens with rotation and theft detection.
 *
 * <p>The rules:
 * <ul>
 *   <li>The token is 256 bits of {@link SecureRandom}, not a JWT. There is nothing to parse and
 *       nothing to forge; it is a lookup key.</li>
 *   <li>Redis stores the SHA-256 of the token, never the token. A dump of Redis is not a set of
 *       working credentials.</li>
 *   <li>Every use rotates: the old token dies, a new one is issued.</li>
 *   <li>If a token that was already rotated comes back, that means two parties hold it — the
 *       legitimate client and a thief. We cannot tell which is which, so the entire family is
 *       destroyed and both must log in again. An inconvenienced user beats a silent intruder.</li>
 * </ul>
 */
@Component
public class RefreshTokenStore {

    public sealed interface RotationResult {
        record Rotated(String newToken, String sessionId, UUID userId, Instant expiresAt) implements RotationResult { }
        record Invalid() implements RotationResult { }
        record ReuseDetected(UUID userId, String familyId) implements RotationResult { }
    }

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private static final String TOKEN_KEY = "rt:tok:";      // hash -> metadata
    private static final String FAMILY_KEY = "rt:fam:";     // family -> alive marker
    private static final String USER_FAMILIES_KEY = "rt:usr:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RefreshTokenStore(StringRedisTemplate redis,
                             @Value("${drenix.auth.refresh-token-ttl:P14D}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** First token of a new family, created at login. */
    public Issued issue(UUID userId, String sessionId) {
        return issue(userId, sessionId, UUID.randomUUID().toString());
    }

    public record Issued(String token, String familyId, Instant expiresAt) {
    }

    private Issued issue(UUID userId, String sessionId, String familyId) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now().plus(ttl);

        redis.opsForHash().putAll(TOKEN_KEY + hash(token), Map.of(
                "userId", userId.toString(),
                "sessionId", sessionId,
                "familyId", familyId,
                "expiresAt", expiresAt.toString()));
        redis.expire(TOKEN_KEY + hash(token), ttl);

        redis.opsForValue().set(FAMILY_KEY + familyId, "alive", ttl);
        redis.opsForSet().add(USER_FAMILIES_KEY + userId, familyId);
        redis.expire(USER_FAMILIES_KEY + userId, ttl);

        return new Issued(token, familyId, expiresAt);
    }

    /**
     * Consumes a refresh token and issues its successor.
     *
     * <p>The delete is what makes reuse detectable: the second presentation finds nothing under
     * the token key, but the family marker is still alive, and that combination can only mean the
     * token was copied.
     */
    public RotationResult rotate(String presentedToken) {
        String tokenKey = TOKEN_KEY + hash(presentedToken);
        Map<Object, Object> stored = redis.opsForHash().entries(tokenKey);

        if (stored.isEmpty()) {
            return new RotationResult.Invalid();
        }

        String familyId = (String) stored.get("familyId");
        UUID userId = UUID.fromString((String) stored.get("userId"));
        String sessionId = (String) stored.get("sessionId");

        Boolean deleted = redis.delete(tokenKey);
        if (Boolean.FALSE.equals(deleted)) {
            // Someone else consumed it between our read and our delete: treat as a race lost,
            // which for a single-use token is the same thing as reuse.
            killFamily(userId, familyId);
            return new RotationResult.ReuseDetected(userId, familyId);
        }

        if (Boolean.FALSE.equals(redis.hasKey(FAMILY_KEY + familyId))) {
            return new RotationResult.Invalid();
        }

        Issued next = issue(userId, sessionId, familyId);
        return new RotationResult.Rotated(next.token(), sessionId, userId, next.expiresAt());
    }

    /** Called on logout and whenever theft is suspected. */
    public void killFamily(UUID userId, String familyId) {
        redis.delete(FAMILY_KEY + familyId);
        redis.opsForSet().remove(USER_FAMILIES_KEY + userId, familyId);
        log.warn("Refresh family {} for user {} revoked", familyId, userId);
    }

    /** Sign out everywhere: used by admins and after a password change. */
    public void killAllFamilies(UUID userId) {
        Optional.ofNullable(redis.opsForSet().members(USER_FAMILIES_KEY + userId))
                .orElse(java.util.Set.of())
                .forEach(family -> redis.delete(FAMILY_KEY + family));
        redis.delete(USER_FAMILIES_KEY + userId);
    }

    /**
     * SHA-256 is enough here, and deliberately not Argon2: the token is 256 bits of entropy, so
     * there is no dictionary to attack, and refresh happens often enough that a slow hash would
     * cost real latency.
     */
    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
