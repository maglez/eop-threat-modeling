package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when the facilitator tries to start with fewer than three players.
 *
 * <p>Three is a rule of the game taken from the whitepaper, not a configurable
 * threshold: with two players the trick-taking play does not work. So this is not
 * a limit somebody can lower for a demo.
 */
public class TooFewPlayersException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    private final int seated;

    private final int required;

    /**
     * Creates the exception for an under-populated table.
     *
     * @param sessionId the session
     * @param seated    how many players are present
     * @param required  how many the game needs
     */
    public TooFewPlayersException(final UUID sessionId, final int seated, final int required) {
        super("Session " + sessionId + " has " + seated + " players and needs at least " + required + " to start");
        this.sessionId = sessionId;
        this.seated = seated;
        this.required = required;
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
     * How many players are present.
     *
     * @return the seated count
     */
    public int seated() {
        return seated;
    }

    /**
     * How many players the game needs.
     *
     * @return the required count
     */
    public int required() {
        return required;
    }
}
