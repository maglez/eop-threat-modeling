package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a trick number a session already has is opened a second time.
 *
 * <p>Signalled by the persistence layer, from {@code uq_trick_session_sequence}
 * rejecting an insert, and translated here so the use case layer never sees a
 * database-specific type.
 *
 * <p>The situation is two requests both deciding that trick <em>N+1</em> is next.
 * Both read a session whose latest trick is <em>N</em>, both compute the same
 * sequence, and the unique index lets exactly one of them create it. The loser has
 * nothing to fix and everything to re-read: the trick it wanted now exists, opened
 * by the other request, and the state that resolves the conflict has already
 * arrived. That is a 409.
 *
 * <p>Kept distinct from {@link AlreadyPlayedInTrickException} even though both are
 * 409s from a unique index, because no play is involved in this one. Opening a
 * trick and playing into a trick fail for different reasons and a client that wants
 * to distinguish "somebody else opened it" from "you already played" cannot do so
 * from a shared type. ADR-023's translation table omitted this constraint when
 * first written, which would have left the loser of an ordinary race with a 500;
 * the amendment at the end of that ADR records the correction.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class TrickAlreadyOpenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    private final int sequence;

    /**
     * Creates the exception for a trick number already taken.
     *
     * @param sessionId the session
     * @param sequence  the trick number another request opened first
     */
    public TrickAlreadyOpenException(final UUID sessionId, final int sequence) {
        super("Trick " + sequence + " is already open in session " + sessionId);
        this.sessionId = sessionId;
        this.sequence = sequence;
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
     * The trick number another request opened first.
     *
     * @return the contested sequence number
     */
    public int sequence() {
        return sequence;
    }
}
