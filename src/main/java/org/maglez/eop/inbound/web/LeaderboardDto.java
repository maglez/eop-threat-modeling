package org.maglez.eop.inbound.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maglez.eop.domain.ScoreSheet;
import org.maglez.eop.domain.Standing;
import org.maglez.eop.domain.StrideCategory;

/**
 * The final leaderboard for a completed game.
 *
 * <p>Carries one row per seated player, ordered by competition-ranking position (best first,
 * seat order as tiebreak), plus the session status string for the client to confirm the game
 * is over.
 *
 * @param rows          one row per player, best first
 * @param sessionStatus the session status at the time the leaderboard was read
 */
public record LeaderboardDto(List<LeaderboardRowDto> rows, String sessionStatus) {

    /** Defensive copy. */
    public LeaderboardDto {
        rows = List.copyOf(rows);
    }

    /**
     * Builds the leaderboard from the live {@link ScoreSheet}.
     *
     * <p>Scores, positions and the {@code tied} flag are always derived from the live
     * {@link ScoreSheet} (re-read from tricks at request time), satisfying ADR-030.
     *
     * <p>The per-player STRIDE breakdown is computed by {@link ScoreSheet#capturedBySuitByPlayer()},
     * which groups plays by trick boundary and attributes all cards in a trick to the winner.
     * The winner may appear anywhere in the trick's play order (first, middle or last).
     *
     * @param scoreSheet    the score sheet derived from the session's tricks
     * @param sessionStatus the session status string
     * @return the leaderboard response body
     */
    public static LeaderboardDto from(final ScoreSheet scoreSheet, final String sessionStatus) {

        final Map<UUID, Map<StrideCategory, Integer>> capturedBySuit =
                scoreSheet.capturedBySuitByPlayer();

        final List<LeaderboardRowDto> rows = scoreSheet.standings().stream()
                .map(standing -> toRow(standing, capturedBySuit))
                .toList();

        return new LeaderboardDto(rows, sessionStatus);
    }

    private static LeaderboardRowDto toRow(
            final Standing standing, final Map<UUID, Map<StrideCategory, Integer>> capturedBySuit) {

        final Map<StrideCategory, Integer> byCategory =
                capturedBySuit.getOrDefault(standing.playerId(), Map.of());

        // Build a string-keyed map with all six STRIDE categories present (zero if none captured)
        final Map<String, Integer> capturedMap = new HashMap<>();
        for (final StrideCategory category : StrideCategory.values()) {
            capturedMap.put(category.name(), byCategory.getOrDefault(category, 0));
        }

        return new LeaderboardRowDto(
                standing.playerId(),
                standing.seatOrder(),
                standing.displayName().value(),
                standing.points(),
                standing.position(),
                standing.tied(),
                Map.copyOf(capturedMap));
    }
}
