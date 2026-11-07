package org.maglez.eop.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Where one player stands in the game: their running total and their place in the order.
 *
 * <p>A position is a competition ranking, so equal totals share a position and the next player down is pushed past them: four players on
 * 7, 5, 5 and 2 points hold positions 1, 2, 2 and 4. A tie is therefore reported as a tie and never broken arbitrarily, which the game
 * requires — two players who scored the same have scored the same, and inventing a tiebreak would be the server asserting something the
 * rules do not say. {@link #tied()} makes that visible to a client rendering an ordinal, which would otherwise have to compare totals
 * itself to discover that "2nd" is shared.</p>
 *
 * <p>{@code tied} is derivable from the other standings, and is published anyway for the same reason the trick state publishes both the
 * seat to play and the next leader: the interesting part of a standing is easy to miss, and a client that has to recompute it is a client
 * that can get it wrong.</p>
 *
 * <p>The display name is carried for display only. It is free text, unverified and deliberately not unique — two people may pick the same
 * name and the humans on the call disambiguate them — so a standing must be keyed on {@code playerId} or {@code seatOrder}, never on the
 * name.</p>
 *
 * @param playerId    identifier of the player
 * @param seatOrder   the player's seat, zero-based
 * @param displayName the player's chosen name, for display only
 * @param points      the player's running total, never negative
 * @param position    place in the order, one-based, shared with anyone on the same total
 * @param tied        whether at least one other player holds the same total
 */
public record Standing(UUID playerId, int seatOrder, DisplayName displayName, int points, int position, boolean tied) {

    /**
     * Rejects a standing that could not describe a seated player.
     */
    public Standing {
        Objects.requireNonNull(playerId, "playerId is required");
        Objects.requireNonNull(displayName, "displayName is required");
        if (seatOrder < 0 || seatOrder >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException("A seat is 0 through " + (GameSession.MAXIMUM_PLAYERS - 1) + ", was " + seatOrder);
        }
        if (points < 0) {
            throw new IllegalArgumentException("A total is never negative, was " + points);
        }
        if (position < 1) {
            throw new IllegalArgumentException("A position is one-based, was " + position);
        }
    }
}
