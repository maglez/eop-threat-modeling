package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a session whose every dealt card has been played is asked to play another.
 *
 * <p>The third state of {@code current_leader_seat} to earn a name, after
 * {@link HandNotDealtException} and {@link HandAlreadyDealtException} named the other two.
 * Those two are opposite answers to "has the deal happened"; this one is what the column
 * says once the deal has happened and been played out: no seat leads, because no seat holds
 * a card to lead with.
 *
 * <p>It exists because the truthful refusal was otherwise unavailable. Without it the last
 * card being played is reported by whichever check happens to fire first, and neither of the
 * two candidates describes the situation. {@code Hand.resolve} answers
 * {@link CardNotInHandException} and a 422, which tells the caller it named a card it does
 * not hold, when the honest answer is that it holds no cards at all and no card would have
 * worked. The persistence layer answers {@link HandNotDealtException} on a null leader seat,
 * which tells the caller the deal has not happened when it has happened and finished. Both
 * would send a client looking for a mistake in its own request.
 *
 * <p>A conflict rather than a rejected argument, on the same reasoning as its two siblings:
 * the request was well formed and would have succeeded earlier in the session. The state is
 * later than the caller believed rather than wrong, and re-reading the state of play shows
 * why — {@code handComplete} is set and no seat is to play.
 *
 * <p>Running out of cards is one of the three ways the game ends in PRD §3.3. This type says
 * the cards are gone; it deliberately says nothing about the score, and the session status
 * does not become {@code COMPLETED} on the strength of it, because that word is reserved for
 * a hand whose score is final and scoring is EOP-15's work.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class HandCompleteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for a session whose dealt cards have all been played.
     *
     * @param sessionId the session whose hands are exhausted
     */
    public HandCompleteException(final UUID sessionId) {
        super("Every card dealt in session " + sessionId + " has been played");
        this.sessionId = sessionId;
    }

    /**
     * The session whose hands are exhausted.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
