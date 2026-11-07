package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a request carries no usable identity token, or one that belongs to
 * nobody at this table.
 *
 * <p>Deliberately one exception for both cases. A missing token and an unknown
 * token get the same answer, because telling the two apart tells a caller whether
 * a token they guessed happens to exist.
 *
 * <p>Carries the session but never the token or its digest: an exception message
 * ends up in logs, and a credential in a log is a credential leaked.
 */
public class PlayerNotRecognisedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for an unrecognised caller.
     *
     * @param sessionId the session the caller tried to act on
     */
    public PlayerNotRecognisedException(final UUID sessionId) {
        super("The supplied identity token does not belong to a player in session " + sessionId);
        this.sessionId = sessionId;
    }

    /**
     * The session the caller tried to act on.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
