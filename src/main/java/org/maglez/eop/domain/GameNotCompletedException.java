package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Thrown when a game result is requested for a session that has not yet completed.
 *
 * <p>The leaderboard is only available once the session is in {@link SessionStatus#COMPLETED}
 * state. Requesting it while the session is still {@link SessionStatus#IN_PROGRESS} or in
 * {@link SessionStatus#LOBBY} is a conflict — the resource exists but is not yet in the
 * right state to serve the request.
 */
public final class GameNotCompletedException extends RuntimeException {

    private final UUID sessionId;

    /**
     * Creates the exception.
     *
     * @param sessionId the session that is not yet completed
     */
    public GameNotCompletedException(final UUID sessionId) {
        super("Session " + sessionId + " is not yet completed");
        this.sessionId = sessionId;
    }

    /**
     * The session that is not yet completed.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
