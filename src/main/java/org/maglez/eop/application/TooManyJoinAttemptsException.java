package org.maglez.eop.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Signals that a caller has made too many failed join attempts to be allowed
 * another one yet.
 *
 * <p>This exception lives in the application layer rather than beside the domain
 * exceptions, because being rate limited is not a violation of a rule of the game.
 * Nothing about a session is wrong; the request is simply refused for now.
 *
 * <p>It carries a retry delay so that the caller can be told when to come back,
 * and carries nothing else. In particular it does not carry the code that was
 * attempted: repeating a guess back to the guesser, or into a log, is how a
 * limiter turns into an oracle.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005. The application
 * layer does not know that HTTP exists, so it does not know that this becomes 429.
 */
public class TooManyJoinAttemptsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    /**
     * Creates the exception.
     *
     * @param retryAfter how long the caller should wait before trying again
     */
    public TooManyJoinAttemptsException(final Duration retryAfter) {
        super("Too many join attempts; retry after " + Objects.requireNonNull(retryAfter, "retryAfter is required").toSeconds()
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
