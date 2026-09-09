package uz.drenix.identity.user.service;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id, with the OWASP-recommended parameters (19 MiB memory, 2 iterations, 1 lane).
 *
 * <p>Argon2id rather than bcrypt: memory hardness is what makes GPU and ASIC cracking expensive,
 * and bcrypt's 72-byte input truncation is a sharp edge nobody should have to remember.
 *
 * <p>{@link #matchesDummy} exists so a login for a username that does not exist still burns the
 * same CPU as a real one. Without it, response time tells an attacker which usernames are valid,
 * which turns a password-spraying campaign into a targeted one.
 */
@Component
public class PasswordHasher {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;

    private final Argon2PasswordEncoder encoder =
            new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);

    /** A real hash of a value nobody knows, used only to spend time on unknown usernames. */
    private final String dummyHash;

    public PasswordHasher() {
        this.dummyHash = encoder.encode("dummy-" + java.util.UUID.randomUUID());
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedHash) {
        return encoder.matches(rawPassword, encodedHash);
    }

    /** Always false. Call it when the user was not found, then return the same generic error. */
    public boolean matchesDummy(String rawPassword) {
        encoder.matches(rawPassword, dummyHash);
        return false;
    }

    /** True when a stored hash was produced with weaker parameters and should be re-hashed on login. */
    public boolean needsUpgrade(String encodedHash) {
        return encoder.upgradeEncoding(encodedHash);
    }
}
