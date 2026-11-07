package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a participant tries to do the one thing only the facilitator may
 * do, which is start play.
 *
 * <p>Distinct from {@link PlayerNotRecognisedException} in the domain even though
 * both become the same HTTP status, because they are different facts: one caller
 * is a stranger, the other is a known player asking for something not theirs to
 * ask. Collapsing them here would lose that in the logs.
 */
public class NotFacilitatorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    private final UUID playerId;

    /**
     * Creates the exception for a participant overreaching.
     *
     * @param sessionId the session
     * @param playerId  the player who asked
     */
    public NotFacilitatorException(final UUID sessionId, final UUID playerId) {
        super("Player " + playerId + " is not the facilitator of session " + sessionId);
        this.sessionId = sessionId;
        this.playerId = playerId;
    }

    /**
     * The session.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }

    /**
     * The player who asked.
     *
     * @return the player identifier
     */
    public UUID playerId() {
        return playerId;
    }
}
