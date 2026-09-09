package uz.drenix.platform.security;

/**
 * Somebody else's service could not be reached.
 *
 * <p>Distinct from an internal error, and it matters which the caller is told: an internal error
 * says "this is broken, report it", while this says "try again shortly, and it is not your
 * request that is wrong". It maps to {@code UNAVAILABLE} and reaches the client as a 503.
 *
 * <p>Lives here rather than in each service so that one mapping covers all of them. Extending it
 * is how monday.com, RingCentral and the language model all get the same treatment without
 * platform-security needing to know any of them exist.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
