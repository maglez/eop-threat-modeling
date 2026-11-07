package org.maglez.eop.domain;

/**
 * Raised when a freshly generated join code turns out to be in use already.
 *
 * <p>Signalled by the persistence layer from the
 * {@code uq_game_session_join_code} constraint. Collision detection is the
 * constraint rather than a preceding {@code SELECT}, because a check-then-insert
 * has a race window and a unique index does not (ADR-019).
 *
 * <p>Expected and recoverable: the caller generates another code and retries a
 * bounded number of times. Carries no code, because the value is of no use to a
 * caller and every string in an exception message is a string in a log.
 */
public class JoinCodeUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public JoinCodeUnavailableException() {
        super("The generated join code is already in use");
    }
}
