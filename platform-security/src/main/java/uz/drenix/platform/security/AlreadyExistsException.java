package uz.drenix.platform.security;

/**
 * A value that must be unique is already taken.
 *
 * <p>Distinct from {@link IllegalArgumentException}, which means the request was malformed. This
 * one says the request was well formed and the answer is still no — the caller should be told 409
 * and shown which field collided, not 400 and asked to check their syntax.
 */
public class AlreadyExistsException extends RuntimeException {

    public AlreadyExistsException(String message) {
        super(message);
    }
}
