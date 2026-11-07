package org.maglez.eop.domain;

/**
 * Thrown when a play describes a seat other than the one the requesting player occupies.
 *
 * <p>This is impersonation, not a mistake about the rules. Every player is told every other player's
 * seat number and player identifier — the session state has to publish them so that a client can draw
 * the table — so the values needed to describe someone else's play are already in every participant's
 * hands. What stops the impersonation is that the seat a play is judged against is derived from the
 * credential the request presented, and never read out of the request body.
 *
 * <p>An earlier version of {@code Trick.acceptPlay} took the seat from the play it was asked to
 * validate. That let a player claim the seat whose turn it was, play out of turn, and then play a
 * second card from their own hand at their real seat later in the same trick — taking the trick and
 * locking the seat's real occupant out of it, because a seat that has already been played for cannot
 * play again. The per-seat and per-card invariants on the trick could not catch it: two different
 * seats and two different cards is exactly what they permit.
 *
 * <p>The exception carries both seats because a refusal that names them is auditable. A repeated
 * mismatch from one player is someone probing the boundary rather than a client with a bug, and that
 * distinction is only visible if the two numbers are recorded.
 */
public class NotYourSeatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int occupiedSeat;

    private final int claimedSeat;

    /**
     * Creates the exception.
     *
     * @param occupiedSeat the seat the requesting player actually occupies
     * @param claimedSeat the seat the play claimed to come from
     */
    public NotYourSeatException(final int occupiedSeat, final int claimedSeat) {
        super("The requesting player occupies seat " + occupiedSeat
                + ", but the play claims to come from seat " + claimedSeat);
        this.occupiedSeat = occupiedSeat;
        this.claimedSeat = claimedSeat;
    }

    /**
     * The seat the requesting player actually occupies, established from their credential.
     *
     * @return the seat the player really holds
     */
    public int occupiedSeat() {
        return occupiedSeat;
    }

    /**
     * The seat the play claimed to come from.
     *
     * @return the claimed seat
     */
    public int claimedSeat() {
        return claimedSeat;
    }
}
