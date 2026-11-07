package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a session with no hands dealt is asked to do something that only a
 * dealt session can do.
 *
 * <p>The mirror of {@link HandAlreadyDealtException}, and it exists for the same
 * reason: the two conditions are opposite states of one column, and a vocabulary
 * that names only one of them forces the other to be reported as something it is
 * not.
 *
 * <p>Signalled by the persistence layer when a conditional update on the session
 * matches no row and the reason turns out to be that {@code current_leader_seat} is
 * still null. Opening a trick, appending a play or resolving a trick all carry an
 * expected leader seat as their turn-order witness, and none of them can be
 * satisfied before a deal has established what that seat is.
 *
 * <p>A conflict rather than a bad request, on the same reasoning as its mirror: the
 * request was well-formed and the state is simply earlier than the caller believed.
 * Re-reading the session shows no hands and the caller has nothing to fix in what
 * they sent.
 *
 * <p>What this type is for, concretely, is saying so truthfully. Before it existed
 * the adapter answered this branch with {@code SessionNotJoinableException}, which
 * told the caller a session was not joinable while handing back the status that says
 * it is — an accurate status code carrying a self-contradicting explanation. The
 * status was never the defect; the absence of a type that could name the condition
 * was.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class HandNotDealtException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for a session whose hands have not been dealt.
     *
     * @param sessionId the session with no hands
     */
    public HandNotDealtException(final UUID sessionId) {
        super("No hands have been dealt in session " + sessionId);
        this.sessionId = sessionId;
    }

    /**
     * The session with no hands.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
