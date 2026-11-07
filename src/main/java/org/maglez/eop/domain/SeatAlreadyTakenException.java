package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a seat that looked free was taken between reading the table and
 * writing to it.
 *
 * <p>Signalled by the persistence layer, from the {@code uq_player_session_seat}
 * constraint rejecting an insert, and translated into this domain exception so
 * the use case layer never sees a database-specific type.
 *
 * <p>This is an expected outcome, not a failure. Two players submitting a join at
 * the same instant both compute the same next seat; one insert wins and the other
 * arrives here, re-reads the table and takes the next seat. The alternative —
 * computing the seat in Java and trusting it — is a race that only shows up when
 * a real lobby fills, which is exactly when it matters (ADR-019).
 */
public class SeatAlreadyTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    private final int seatOrder;

    /**
     * Creates the exception for a contested seat.
     *
     * @param sessionId the session
     * @param seatOrder the seat somebody else took first
     */
    public SeatAlreadyTakenException(final UUID sessionId, final int seatOrder) {
        super("Seat " + seatOrder + " in session " + sessionId + " was taken by another player");
        this.sessionId = sessionId;
        this.seatOrder = seatOrder;
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
     * The seat somebody else took first.
     *
     * @return the contested seat
     */
    public int seatOrder() {
        return seatOrder;
    }
}
