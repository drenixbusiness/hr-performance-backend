package uz.drenix.identity.performance.ringcentral;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps this service inside RingCentral's request budget.
 *
 * <p>The call log and the message store both sit in RingCentral's "heavy" group, which allows
 * <b>10 requests per rolling 60 seconds</b> — the account confirms it in every response through
 * {@code x-rate-limit-limit} and {@code x-rate-limit-window}. A month of call log is fifteen or
 * more pages, so without this the very first wide request trips the limit and everything after it
 * comes back 429.
 *
 * <p>A rolling window rather than a fixed one, and bursts are allowed: a short range that needs
 * eight requests runs at full speed and waits not at all. Only once the budget is genuinely spent
 * does a caller block, and then only until the oldest request ages out.
 */
public class RateLimiter {

    private final int permits;
    private final long windowMillis;
    private final Deque<Long> taken = new ArrayDeque<>();

    public RateLimiter(int permits, Duration window) {
        this.permits = permits;
        this.windowMillis = window.toMillis();
    }

    /**
     * Blocks until another request may be sent.
     *
     * @throws RingCentralUnavailableException if interrupted while waiting, so the caller sees a
     *         service-level failure rather than a silent half-result.
     */
    public synchronized void acquire() {
        while (true) {
            long now = System.currentTimeMillis();
            while (!taken.isEmpty() && now - taken.peekFirst() >= windowMillis) {
                taken.pollFirst();
            }
            if (taken.size() < permits) {
                taken.addLast(now);
                return;
            }
            long waitFor = windowMillis - (now - taken.peekFirst()) + 50;
            sleep(waitFor);
        }
    }

    /** Records that the budget is spent, so the next caller waits rather than earning a second 429. */
    public synchronized void penalise(Duration retryAfter) {
        long until = System.currentTimeMillis() + retryAfter.toMillis();
        taken.clear();
        for (int i = 0; i < permits; i++) {
            // Back-date the window so it drains exactly when the retry-after expires.
            taken.addLast(until - windowMillis);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(millis, 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RingCentralUnavailableException("Interrupted while waiting for RingCentral", e);
        }
    }
}
