package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.maglez.eop.domain.Standing;

/**
 * One player's place in the game, as it goes over the wire.
 *
 * <p>Positions are competition ranked, so totals of 7, 5, 5 and 2 hold positions 1, 2, 2 and 4. A
 * tie is shown as a tie and never broken: the game has no tie-break rule, and inventing one here
 * would report a result the table did not play.
 *
 * <p>{@code tied} is derivable from the standings taken together and is published anyway, so that a
 * client showing one row does not have to recompute it and cannot get it wrong.
 *
 * @param playerId    the player this standing belongs to
 * @param seatOrder   the seat they hold
 * @param displayName the name they chose, for display only
 * @param points      their total across every row of the Score Card
 * @param position    their place, competition ranked
 * @param tied        whether another player holds the same total
 */
@Schema(name = "Standing", description = "One player's place in the game, competition ranked")
public record StandingDto(
        UUID playerId,
        int seatOrder,
        String displayName,
        int points,
        int position,
        boolean tied) {

    /**
     * Converts a standing into its wire form.
     *
     * @param standing the standing
     * @return the corresponding response body fragment
     */
    public static StandingDto from(final Standing standing) {
        return new StandingDto(
                standing.playerId(),
                standing.seatOrder(),
                standing.displayName().value(),
                standing.points(),
                standing.position(),
                standing.tied());
    }
}
