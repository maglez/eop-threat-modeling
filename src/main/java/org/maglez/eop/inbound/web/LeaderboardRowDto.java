package org.maglez.eop.inbound.web;

import java.util.Map;
import java.util.UUID;

/**
 * A single row in the game-over leaderboard.
 *
 * <p>Carries the player's final score, their competition-ranking position, whether they
 * share that position with another player, and a breakdown of how many cards they captured
 * in each STRIDE category.
 *
 * @param playerId       the player's unique identifier
 * @param seatOrder      the seat the player occupied (1-based)
 * @param displayName    the name the player chose when joining
 * @param points         total points scored (threat points + trick points)
 * @param position       competition-ranking position (1 = winner; ties share the same position)
 * @param tied           {@code true} if at least one other player shares this position
 * @param capturedBySuit number of cards captured per STRIDE category; keys are the
 *                       {@link org.maglez.eop.domain.StrideCategory} name strings
 */
public record LeaderboardRowDto(
        UUID playerId,
        int seatOrder,
        String displayName,
        int points,
        int position,
        boolean tied,
        Map<String, Integer> capturedBySuit) {

    /** Defensive copy — prevents external mutation of the suit-capture map. */
    public LeaderboardRowDto {
        capturedBySuit = Map.copyOf(capturedBySuit);
    }
}
