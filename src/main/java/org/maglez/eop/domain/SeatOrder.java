package org.maglez.eop.domain;

import java.util.Collection;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The one implementation of "the next seat clockwise", shared by every rule that needs it.
 *
 * <p>Play is clockwise, which is ascending seat order wrapping back to zero (ADR-019). Two separate
 * rules need to walk that circle and skip seats: working out whose turn it is within a trick, and
 * working out who leads the next trick when the seat that won this one has run out of cards. Both
 * used to carry their own copy of the same loop, which is how two implementations of one rule end up
 * disagreeing several stories later — and this is the rule ADR-023 exists to protect, because its
 * naive form is wrong only on the last trick, and only at the player counts whose deal is uneven
 * (68 cards divide evenly at 4 players but not at 3, 5 or 6).
 *
 * <p>The starting seat is considered <em>last</em>, not skipped. Stepping a full lap arrives back
 * where it started, so a seat that is the only one left eligible is returned rather than reported
 * absent. That is the right answer for the lead passing to a winner who is the last player holding
 * cards, and it is not a special case.
 *
 * <p>Deliberately package-private and stateless: this is an implementation detail of the entity
 * package's turn-order rules, not part of the domain's published vocabulary.
 */
final class SeatOrder {

    private SeatOrder() {
        // Static helper: the circle of seats is a rule, not a thing anyone owns an instance of.
    }

    /**
     * The first eligible seat clockwise from the given seat, considering that seat last.
     *
     * <p>Seats that do not exist at this table are simply absent from {@code eligibleSeats}, so the
     * same test that skips a seat which has run out of cards skips a seat which was never dealt one.
     * That is why the method never needs to be told how many players are at the table.
     *
     * @param fromSeat the seat to walk clockwise from
     * @param eligibleSeats the seats that may be returned
     * @return the next eligible seat, or empty if no seat is eligible
     */
    static OptionalInt nextClockwise(final int fromSeat, final Collection<Integer> eligibleSeats) {
        Objects.requireNonNull(eligibleSeats, "eligibleSeats is required");

        for (int step = 1; step <= GameSession.MAXIMUM_PLAYERS; step++) {
            final int candidate = (fromSeat + step) % GameSession.MAXIMUM_PLAYERS;
            if (eligibleSeats.contains(candidate)) {
                return OptionalInt.of(candidate);
            }
        }
        return OptionalInt.empty();
    }
}
