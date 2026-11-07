package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Thrown when a play names a player who does not occupy the seat the play is being made from.
 *
 * <p>This is the sibling of {@link NotYourSeatException}, and like it this is impersonation rather
 * than a mistake about the rules. The seat a play is made from is derived from the requesting
 * player's credential and is never read from the request, so by the time this check runs the seat is
 * already trusted; the player identifier is the one thing left that the caller supplied. It is
 * checked against the identifier of the player who actually holds that seat's hand rather than
 * believed.
 *
 * <p>It has its own type rather than sharing {@code IllegalArgumentException} with genuine
 * programming errors for two reasons. The first is that an impersonation attempt and a malformed
 * argument deserve different answers at the boundary: one is a refusal to act on someone else's
 * behalf, the other is a bad request. Sharing a type would have meant the boundary could not tell
 * them apart without matching on message text. The second is disclosure — the message an
 * {@code IllegalArgumentException} carries becomes the problem detail a caller reads, and this
 * condition is only describable by naming two player identifiers. Carrying them as fields lets the
 * boundary report the refusal without putting either identifier in the response body.
 *
 * <p>Which is why the message names neither of them. Interpolating both into the
 * message and then arguing the fields exist to keep them out of a response would
 * be an argument the class itself defeats: the established way to render a domain
 * refusal here is to copy its message into the problem detail, so an identifier in
 * the message is an identifier one careless handler away from the wire. The seat is
 * named because every player already receives every seat number; the identifiers
 * are reachable through the accessors, for a log line or an incident, and nowhere
 * else.
 *
 * <p>The condition it guards is not reachable through ordinary play, because the seat determines
 * which hand is fetched and a hand carries the identifier of the player it was dealt to. It becomes
 * reachable if hands are ever filed against the wrong seats, and then this exception is the
 * difference between a loud refusal and two players silently playing each other's cards.
 */
public class PlayerMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int seatOrder;
    private final UUID occupant;
    private final UUID namedPlayer;

    /**
     * Creates the exception.
     *
     * @param seatOrder the seat the play was made from, derived from the requesting player's credential
     * @param occupant the identifier of the player who actually holds that seat's hand
     * @param namedPlayer the identifier the play claimed to come from
     */
    public PlayerMismatchException(final int seatOrder, final UUID occupant, final UUID namedPlayer) {
        super("The play made from seat " + seatOrder + " names a player who does not hold that seat");
        this.seatOrder = seatOrder;
        this.occupant = occupant;
        this.namedPlayer = namedPlayer;
    }

    /**
     * Returns the seat the play was made from.
     *
     * @return the seat order
     */
    public int seatOrder() {
        return seatOrder;
    }

    /**
     * Returns the identifier of the player who actually holds that seat's hand.
     *
     * @return the occupying player's identifier
     */
    public UUID occupant() {
        return occupant;
    }

    /**
     * Returns the identifier the play claimed to come from.
     *
     * @return the named player's identifier
     */
    public UUID namedPlayer() {
        return namedPlayer;
    }
}
