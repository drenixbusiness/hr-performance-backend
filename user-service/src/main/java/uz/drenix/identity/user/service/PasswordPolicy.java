package uz.drenix.identity.user.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Password rules, applied wherever a password enters the system: admin creation, admin reset,
 * and the user's own change.
 *
 * <p>Deliberately follows NIST SP 800-63B rather than the older "one upper, one digit, one
 * symbol, rotate every 90 days" habit: length and a blocklist stop far more real attacks than
 * composition rules, which mostly produce {@code Password1!}. No forced expiry either — expiry
 * drives people to write passwords down and to increment a trailing digit.
 */
@Component
public class PasswordPolicy {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 256;   // bound the Argon2 work an attacker can request

    /** Trimmed for readability; load the full list from a file in production. */
    private static final Set<String> BLOCKLIST = Set.of(
            "password", "123456789012", "qwertyuiop12", "drenix123456",
            "changeme2026", "admin1234567", "letmein12345");

    public void validate(String password, String username, String fullName) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new PolicyViolationException("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw new PolicyViolationException("Password must be at most " + MAX_LENGTH + " characters");
        }

        String normalised = Normalizer.normalize(password, Normalizer.Form.NFKC).toLowerCase();
        if (BLOCKLIST.contains(normalised)) {
            throw new PolicyViolationException("This password is too common");
        }
        if (username != null && normalised.contains(username.toLowerCase())) {
            throw new PolicyViolationException("Password must not contain the username");
        }
        for (String part : namePartsOf(fullName)) {
            if (part.length() >= 4 && normalised.contains(part)) {
                throw new PolicyViolationException("Password must not contain your name");
            }
        }
        if (isSingleRepeatedCharacter(password)) {
            throw new PolicyViolationException("Password must not be a single repeated character");
        }
    }

    private static List<String> namePartsOf(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return List.of();
        }
        return List.of(fullName.toLowerCase().split("\\s+"));
    }

    private static boolean isSingleRepeatedCharacter(String password) {
        return password.chars().distinct().count() == 1;
    }

    /**
     * Extends IllegalArgumentException on purpose: the gRPC exception interceptor maps that to
     * INVALID_ARGUMENT, so a rejected password reaches the caller as "your password is too
     * short" rather than as a generic internal error.
     */
    public static class PolicyViolationException extends IllegalArgumentException {
        public PolicyViolationException(String message) {
            super(message);
        }
    }
}
