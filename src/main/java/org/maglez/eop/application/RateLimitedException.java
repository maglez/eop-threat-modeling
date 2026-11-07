package org.maglez.eop.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Signals that a caller has been refused because it exceeded a rate limit.
 *
 * <p>This exception is deliberately neutral: it does not name the operation that was
 * throttled, so it can be thrown by any rate limiter in the application without
 * producing a misleading problem detail. The caller is told how long to wait; nothing
 * else is disclosed.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005. The application
 * layer does not know that HTTP exists, so it does not know that this becomes 429.
 *
 * <p>See also {@link TooManyJoinAttemptsException}, which is the join-specific
 * predecessor of this class and carries join-specific wording in its message.
 */
public class RateLimitedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    /**
     * Creates the exception.
     *
     * @param retryAfter how long the caller should wait before trying again
     */
    public RateLimitedException(final Duration retryAfter) {
        super("Rate limit exceeded; retry after "
                + Objects.requireNonNull(retryAfter, "retryAfter is required").toSeconds()
                + " seconds");
        this.retryAfter = retryAfter;
    }

    /**
     * Returns how long the caller should wait.
     *
     * @return the retry delay, never negative
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
