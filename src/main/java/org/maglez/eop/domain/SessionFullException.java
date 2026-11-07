package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a seventh player tries to join a table that seats six.
 *
 * <p>Six is a rule of the game rather than a configured limit: the deck runs out.
 * So this is not a capacity error to be raised by tuning a property.
 */
public class SessionFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    private final int capacity;

    /**
     * Creates the exception for a full table.
     *
     * @param sessionId the session that is full
     * @param capacity  the number of seats the game allows
     */
    public SessionFullException(final UUID sessionId, final int capacity) {
        super("Session " + sessionId + " already seats the maximum of " + capacity + " players");
        this.sessionId = sessionId;
        this.capacity = capacity;
    }

    /**
     * The session that is full.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }

    /**
     * The number of seats the game allows.
     *
     * @return the capacity
     */
    public int capacity() {
        return capacity;
    }
}
