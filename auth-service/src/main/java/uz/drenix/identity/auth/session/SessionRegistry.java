package uz.drenix.identity.auth.session;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Live sessions and the access-token denylist.
 *
 * <p>An access token is self-contained, so it stays valid until it expires — unless something
 * says otherwise. This registry is that something. The gateway checks it on every request, which
 * closes the gap between "admin disabled the account" and "the token would have expired anyway".
 *
 * <p>Denylist entries expire on their own after one access-token lifetime; keeping them longer
 * grows the set forever for no benefit.
 */
@Component
public class SessionRegistry {

    private static final String SESSION_KEY = "sess:";
    private static final String DENY_KEY = "deny:";
    private static final String USER_SESSIONS_KEY = "sess:usr:";

    private final StringRedisTemplate redis;
    private final Duration sessionTtl;
    private final Duration accessTokenTtl;

    public SessionRegistry(StringRedisTemplate redis,
                           @Value("${drenix.auth.refresh-token-ttl:P14D}") Duration sessionTtl,
                           @Value("${drenix.auth.access-token-ttl:PT10M}") Duration accessTokenTtl) {
        this.redis = redis;
        this.sessionTtl = sessionTtl;
        this.accessTokenTtl = accessTokenTtl;
    }

    public String open(UUID userId, String username, String clientIp, String userAgent) {
        String sessionId = UUID.randomUUID().toString();
        redis.opsForHash().putAll(SESSION_KEY + sessionId, Map.of(
                "userId", userId.toString(),
                "username", username,
                "clientIp", clientIp == null ? "" : clientIp,
                "userAgent", userAgent == null ? "" : userAgent,
                "openedAt", Instant.now().toString()));
        redis.expire(SESSION_KEY + sessionId, sessionTtl);
        redis.opsForSet().add(USER_SESSIONS_KEY + userId, sessionId);
        redis.expire(USER_SESSIONS_KEY + userId, sessionTtl);
        return sessionId;
    }

    public boolean isActive(String sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(SESSION_KEY + sessionId));
    }

    public void close(String sessionId) {
        Object userId = redis.opsForHash().get(SESSION_KEY + sessionId, "userId");
        redis.delete(SESSION_KEY + sessionId);
        if (userId != null) {
            redis.opsForSet().remove(USER_SESSIONS_KEY + userId, sessionId);
        }
        // The access token issued for this session may still be within its TTL.
        redis.opsForValue().set(DENY_KEY + "sid:" + sessionId, "revoked", accessTokenTtl);
    }

    public void closeAll(UUID userId) {
        var sessions = redis.opsForSet().members(USER_SESSIONS_KEY + userId);
        if (sessions != null) {
            sessions.forEach(this::close);
        }
        redis.delete(USER_SESSIONS_KEY + userId);
    }

    /** Checked by the gateway on every request, before the call is forwarded. */
    public boolean isRevoked(String sessionId, String tokenId) {
        return Boolean.TRUE.equals(redis.hasKey(DENY_KEY + "sid:" + sessionId))
               || Boolean.TRUE.equals(redis.hasKey(DENY_KEY + "jti:" + tokenId));
    }

    public void denyToken(String tokenId) {
        redis.opsForValue().set(DENY_KEY + "jti:" + tokenId, "revoked", accessTokenTtl);
    }

    /** What a session looks like to an administrator inspecting somebody else's account. */
    public record Session(String sessionId, String clientIp, String userAgent, Instant openedAt) {
    }

    /**
     * Every live session of one user, newest first.
     *
     * <p>The per-user set can outlive the session hashes it points at — a hash expires on its own
     * TTL while the set entry remains — so an id with nothing behind it is dropped here rather
     * than reported as a session that no longer exists.
     */
    public List<Session> listSessions(UUID userId) {
        Set<String> sessionIds = redis.opsForSet().members(USER_SESSIONS_KEY + userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }

        List<Session> sessions = new ArrayList<>(sessionIds.size());
        for (String sessionId : sessionIds) {
            Map<Object, Object> fields = redis.opsForHash().entries(SESSION_KEY + sessionId);
            if (fields.isEmpty()) {
                continue;
            }
            sessions.add(new Session(
                    sessionId,
                    text(fields.get("clientIp")),
                    text(fields.get("userAgent")),
                    parseInstant(text(fields.get("openedAt")))));
        }
        sessions.sort(Comparator.comparing(
                Session::openedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return sessions;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
