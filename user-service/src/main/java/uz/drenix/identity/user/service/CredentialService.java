package uz.drenix.identity.user.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.user.domain.UserEntity;
import uz.drenix.identity.user.domain.UserStatus;
import uz.drenix.identity.user.repo.UserRepository;

/**
 * Password verification and lockout. The only place that reads {@code password_hash}.
 *
 * <p>Every failure path returns the same {@link Verdict#INVALID_CREDENTIALS} and burns comparable
 * CPU, so a caller cannot tell "no such user" from "wrong password" by result or by timing.
 */
@Service
public class CredentialService {

    public enum Verdict { OK, INVALID_CREDENTIALS, LOCKED, DISABLED }

    public record Result(Verdict verdict, UserEntity user, Instant lockedUntil, int attemptsRemaining) {

        static Result invalid(int attemptsRemaining) {
            return new Result(Verdict.INVALID_CREDENTIALS, null, null, attemptsRemaining);
        }
    }

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final int maxAttempts;
    private final Duration lockDuration;

    public CredentialService(
            UserRepository users,
            PasswordHasher hasher,
            @Value("${drenix.security.lockout.max-attempts:5}") int maxAttempts,
            @Value("${drenix.security.lockout.duration:PT15M}") Duration lockDuration) {
        this.users = users;
        this.hasher = hasher;
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
    }

    @Transactional
    public Result verify(String username, String password) {
        Instant now = Instant.now();
        Optional<UserEntity> found = users.findByUsernameIgnoreCaseAndDeletedAtIsNull(username);

        if (found.isEmpty()) {
            // Spend the same time as a real Argon2id verification before answering.
            hasher.matchesDummy(password);
            return Result.invalid(maxAttempts);
        }

        UserEntity user = found.get();

        if (user.getStatus() == UserStatus.DISABLED) {
            hasher.matchesDummy(password);
            return new Result(Verdict.DISABLED, null, null, 0);
        }

        if (user.isLocked(now)) {
            hasher.matchesDummy(password);
            return new Result(Verdict.LOCKED, null, user.getLockedUntil(), 0);
        }

        if (!hasher.matches(password, user.getPasswordHash())) {
            return registerFailure(user, now);
        }

        // Correct password. Opportunistically re-hash if the parameters have been raised since.
        if (hasher.needsUpgrade(user.getPasswordHash())) {
            user.setPasswordHash(hasher.hash(password));
        }

        user.setFailedAttempts((short) 0);
        user.setLockedUntil(null);
        if (user.getStatus() == UserStatus.LOCKED) {
            user.setStatus(UserStatus.ACTIVE);
        }
        user.setLastLoginAt(now);
        user.touch();

        return new Result(Verdict.OK, user, null, maxAttempts);
    }

    private Result registerFailure(UserEntity user, Instant now) {
        short attempts = (short) (user.getFailedAttempts() + 1);
        user.setFailedAttempts(attempts);
        user.touch();

        if (attempts >= maxAttempts) {
            Instant until = now.plus(lockDuration);
            user.setLockedUntil(until);
            user.setStatus(UserStatus.LOCKED);
            user.setFailedAttempts((short) 0);
            // Still reported as INVALID_CREDENTIALS rather than LOCKED: announcing the lock on the
            // attempt that caused it confirms the username exists.
            return Result.invalid(0);
        }

        return Result.invalid(maxAttempts - attempts);
    }
}
