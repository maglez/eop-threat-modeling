package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a session exists but is past the point of accepting the request.
 *
 * <p>Covers a join arriving after play started, and a start arriving at a session
 * that has already left the lobby. Both are the same shape of mistake: the
 * request was understood and the session simply is not in a state where it
 * applies, which is a conflict rather than a validation failure.
 */
public class SessionNotJoinableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    private final SessionStatus status;

    /**
     * Creates the exception for a session in the wrong state.
     *
     * @param sessionId the session that refused the request
     * @param status    the state it was actually in
     */
    public SessionNotJoinableException(final UUID sessionId, final SessionStatus status) {
        super("Session " + sessionId + " is " + status + " and does not accept that request");
        this.sessionId = sessionId;
        this.status = status;
    }

    /**
     * The session that refused the request.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }

    /**
     * The state the session was actually in.
     *
     * @return the status
     */
    public SessionStatus status() {
        return status;
    }
}
