package uz.drenix.identity.auth.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Throttles login attempts before they ever reach the password hash.
 *
 * <p>Two counters, because they stop different attacks:
 * <ul>
 *   <li><b>Per username</b> — someone hammering one account. The account lockout in user-service
 *       also covers this, but the limiter stops the traffic earlier and cheaper.</li>
 *   <li><b>Per client IP</b> — password spraying, where one attempt is made against each of a
 *       thousand usernames so that no single account ever locks. The username counter is blind to
 *       this; the IP counter is not.</li>
 * </ul>
 *
 * <p>Argon2id costs ~19 MiB and real CPU per verification, so an unthrottled login endpoint is
 * also a memory-exhaustion lever. This runs first for that reason too.
 */
@Component
public class LoginRateLimiter {

    public record Decision(boolean allowed, Instant retryAfter) {
    }

    private static final String USER_KEY = "rl:login:user:";
    private static final String IP_KEY = "rl:login:ip:";

    private final StringRedisTemplate redis;
    private final int perUsernameLimit;
    private final int perIpLimit;
    private final Duration window;

    public LoginRateLimiter(
            StringRedisTemplate redis,
            @Value("${drenix.auth.rate-limit.per-username:10}") int perUsernameLimit,
            @Value("${drenix.auth.rate-limit.per-ip:30}") int perIpLimit,
            @Value("${drenix.auth.rate-limit.window:PT5M}") Duration window) {
        this.redis = redis;
        this.perUsernameLimit = perUsernameLimit;
        this.perIpLimit = perIpLimit;
        this.window = window;
    }

    public Decision check(String username, String clientIp) {
        Decision byUser = bump(USER_KEY + normalise(username), perUsernameLimit);
        if (!byUser.allowed()) {
            return byUser;
        }
        if (clientIp == null || clientIp.isBlank()) {
            return byUser;
        }
        return bump(IP_KEY + clientIp, perIpLimit);
    }

    /** A successful login clears the username counter so a legitimate user is not punished. */
    public void reset(String username) {
        redis.delete(USER_KEY + normalise(username));
    }

    private Decision bump(String key, int limit) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        if (count != null && count > limit) {
            Duration remaining = Optional.ofNullable(redis.getExpire(key))
                    .map(Duration::ofSeconds)
                    .orElse(window);
            return new Decision(false, Instant.now().plus(remaining));
        }
        return new Decision(true, null);
    }

    private static String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
