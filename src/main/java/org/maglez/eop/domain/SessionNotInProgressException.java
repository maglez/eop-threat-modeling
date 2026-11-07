package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when an operation requires a session to be in progress but it is not.
 *
 * <p>Covers an end-session request arriving at a session that has already
 * completed or was never started. The request was understood; the session
 * simply is not in a state where it applies — a conflict rather than a
 * validation failure.
 */
public class SessionNotInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    private final SessionStatus status;

    /**
     * Creates the exception for a session in the wrong state.
     *
     * @param sessionId the session that refused the request
     * @param status    the state it was actually in
     */
    public SessionNotInProgressException(final UUID sessionId, final SessionStatus status) {
        super("Session " + sessionId + " is " + status + " and cannot be completed from that state");
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
