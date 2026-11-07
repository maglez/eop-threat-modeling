package org.maglez.eop.application.port.in;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.maglez.eop.domain.Trick;

/**
 * The state of play at a table: the trick in front of the players, whose turn it is, and what
 * happens when the trick is done.
 *
 * <p>This type exists because none of it can be read off a {@link Trick}. Whose turn it is, whether
 * the trick is complete and which seat leads next all depend on which seats still hold cards, and
 * after an uneven deal that is a fact about the whole session rather than about one trick (ADR-023).
 * A {@link Trick} is silent on all three deliberately; asking it to answer them would mean handing it
 * a collection every caller had to fetch first and could get wrong.
 *
 * <p>Three of the five parts are genuinely absent at ordinary moments, so they are carried as
 * {@link Optional} and {@link OptionalInt} rather than as nullable fields. The absences are the
 * interesting part of this answer — there is no trick between the deal and the first lead, no seat
 * may play into a complete trick that has not been resolved, and no seat leads next once the hand is
 * played out — and a caller that forgets a null check on any of them produces a plausible lie about
 * the table.
 *
 * <p>{@code seatToPlay} and {@code nextLeaderSeat} are both published even though they agree
 * whenever both are present. They are not the same measurement: {@code seatToPlay} comes from the
 * cards on the table, and {@code nextLeaderSeat} from the seat the session records as leading. Two
 * authorities that should agree are worth more than one that cannot be checked, so they are reported
 * as they are found and never reconciled here.
 *
 * <p>Nothing here names a card any seat is holding. Every field is already public at the table:
 * cards played face up, seats, and turn order.
 *
 * @param trick the trick currently in front of the players, resolved or not, and empty only between
 *     the deal and the first card led
 * @param seatToPlay the seat entitled to play the next card, and empty when no card may be played —
 *     either the trick is complete and waiting to be resolved, or the hand is played out
 * @param complete whether every seat that still held a card has played into the trick, which is
 *     false when there is no trick at all and stays true once the trick has been resolved
 * @param nextLeaderSeat the seat that leads the next trick, present only once the trick has been
 *     resolved and only while some seat still holds a card
 * @param handComplete whether every card dealt has been played, which is one of the three ways a
 *     game of Elevation of Privilege ends (PRD §3.3) and says nothing about the score
 */
public record TrickState(
        Optional<Trick> trick,
        OptionalInt seatToPlay,
        boolean complete,
        OptionalInt nextLeaderSeat,
        boolean handComplete) {

    /**
     * Validates that an absence is stated rather than left null.
     *
     * @throws NullPointerException if trick, seatToPlay or nextLeaderSeat is null
     */
    public TrickState {
        Objects.requireNonNull(trick, "trick is required");
        Objects.requireNonNull(seatToPlay, "seatToPlay is required");
        Objects.requireNonNull(nextLeaderSeat, "nextLeaderSeat is required");
    }
}
