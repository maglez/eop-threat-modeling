package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a session that has already been dealt is asked to deal again.
 *
 * <p>Signalled by the persistence layer, from {@code uq_hand_session_seat}
 * rejecting an insert, and translated here so the use case layer never sees a
 * database-specific type.
 *
 * <p>Dealing twice is not a rules mistake a player can make by hand; it is a
 * retried request, or two facilitators pressing start at the same moment. The
 * adapter's own conditional update — which claims the session only while
 * {@code current_leader_seat} is still null — normally wins that race first, and
 * this constraint is what answers the request that got past it. Both are needed:
 * the update serialises the two callers, and the unique index is what makes a
 * second deal impossible rather than merely unlikely.
 *
 * <p>A conflict rather than a bad request, because the caller's request was
 * well-formed and the state is simply further along than they believed. Re-reading
 * the session shows hands already dealt and the caller has nothing to fix.
 *
 * <p>A second deal is worth refusing loudly rather than tolerating: it would either
 * duplicate cards across hands or replace a hand a player has already been shown,
 * and both are unrecoverable in a game where the deck is the score sheet.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class HandAlreadyDealtException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for a session already dealt.
     *
     * @param sessionId the session whose hands already exist
     */
    public HandAlreadyDealtException(final UUID sessionId) {
        super("Hands have already been dealt in session " + sessionId);
        this.sessionId = sessionId;
    }

    /**
     * The session whose hands already exist.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
