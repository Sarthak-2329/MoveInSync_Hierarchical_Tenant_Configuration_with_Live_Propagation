package com.moveinsync.config.client.sync;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages exponential backoff with Full Jitter.
 *
 * <p><strong>Thundering Herd / Reconnect Storm Mitigation:</strong>
 * When 500+ clients lose connection simultaneously (e.g. during a server restart or network hiccup),
 * fixed-interval or standard exponential retries produce synchronous spikes of incoming traffic
 * at periodic intervals (1s, 2s, 4s...).
 *
 * <p>Full Jitter spreads client retries uniformly across the entire backoff window:
 * <pre>
 *   ceiling = min(maxDelay, baseDelay * (2 ^ attempt))
 *   sleepTime = random_uniform(0, ceiling)
 * </pre>
 * This breaks up lock-step synchronization and eliminates queue saturation on the server.
 */
public final class BackoffManager {

    private final long baseDelayMillis;
    private final long maxDelayMillis;

    public BackoffManager() {
        this(Duration.ofMillis(200), Duration.ofSeconds(10));
    }

    public BackoffManager(Duration baseDelay, Duration maxDelay) {
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay cannot be less than baseDelay");
        }
        this.baseDelayMillis = baseDelay.toMillis();
        this.maxDelayMillis = maxDelay.toMillis();
    }

    /**
     * Calculates the jittered backoff delay for the given retry attempt number (0-based).
     *
     * @param attempt 0 for first retry, 1 for second, etc.
     * @return Duration to wait before the next attempt
     */
    public Duration calculateDelay(int attempt) {
        // Guard against integer overflow in shift: cap at 30
        int effectiveAttempt = Math.min(attempt, 30);
        long multiplier = 1L << effectiveAttempt; // 2^attempt
        long exponentialCeiling;

        if (multiplier > maxDelayMillis / baseDelayMillis) {
            exponentialCeiling = maxDelayMillis;
        } else {
            exponentialCeiling = Math.min(maxDelayMillis, baseDelayMillis * multiplier);
        }

        // Full jitter: uniformly random between 0 and exponentialCeiling
        long jitteredMillis = ThreadLocalRandom.current().nextLong(exponentialCeiling + 1);
        return Duration.ofMillis(jitteredMillis);
    }

    /**
     * Convenience method to execute a sleep for the calculated backoff period.
     */
    public void backoff(int attempt) throws InterruptedException {
        Duration delay = calculateDelay(attempt);
        Thread.sleep(delay.toMillis());
    }

    public long getBaseDelayMillis() {
        return baseDelayMillis;
    }

    public long getMaxDelayMillis() {
        return maxDelayMillis;
    }
}
