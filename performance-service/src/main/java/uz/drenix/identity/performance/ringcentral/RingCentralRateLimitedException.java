package uz.drenix.identity.performance.ringcentral;

import java.time.Duration;

/**
 * RingCentral refused the request because the account's budget for the minute is spent.
 *
 * <p>Distinct from {@link RingCentralUnavailableException}: nothing is broken and retrying later
 * will work, so the caller deserves to be told to wait rather than shown a server fault.
 */
public class RingCentralRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public RingCentralRateLimitedException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
