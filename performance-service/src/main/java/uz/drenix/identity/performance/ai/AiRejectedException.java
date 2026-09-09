package uz.drenix.identity.performance.ai;

/**
 * The model answered, and said no.
 *
 * <p>Separate from {@link AiUnavailableException} because the two need different words: this one
 * is something an operator can fix — a key, a billing balance — and telling them "temporarily
 * unavailable" would send them to look at the network instead.
 */
public class AiRejectedException extends RuntimeException {

    public AiRejectedException(String message) {
        super(message);
    }
}
