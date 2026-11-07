package org.maglez.eop.domain;

/**
 * Raised when a player plays a card while it is somebody else's turn.
 *
 * <p>Turn order is clockwise from the seat leading the trick. It is checked on
 * the server against the identity the request presented, not against a player
 * identifier the request supplied: otherwise a caller could play on another
 * player's behalf simply by naming them.
 *
 * <p>Carries seat numbers rather than player identifiers because a seat is the
 * value turn order is actually computed from (ADR-019), and because a rejection
 * telling a caller who is next is a rejection that does not need a second
 * request to act on.
 */
public class OutOfTurnException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int expectedSeat;

    private final int attemptedSeat;

    /**
     * Creates the exception for a play made out of turn.
     *
     * @param expectedSeat  the seat whose turn it is
     * @param attemptedSeat the seat that tried to play
     */
    public OutOfTurnException(final int expectedSeat, final int attemptedSeat) {
        super("It is seat " + expectedSeat + "'s turn to play, not seat " + attemptedSeat + "'s");
        this.expectedSeat = expectedSeat;
        this.attemptedSeat = attemptedSeat;
    }

    /**
     * The seat whose turn it is.
     *
     * @return the expected seat
     */
    public int expectedSeat() {
        return expectedSeat;
    }

    /**
     * The seat that tried to play.
     *
     * @return the attempted seat
     */
    public int attemptedSeat() {
        return attemptedSeat;
    }
}
