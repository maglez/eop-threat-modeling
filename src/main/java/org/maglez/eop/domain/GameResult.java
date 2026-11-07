package org.maglez.eop.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The final result of one completed game: who played, what they scored, and when it happened.
 *
 * <p>A {@code GameResult} is a snapshot taken at the moment the game ends. It is immutable and
 * carries the metadata needed to identify the game (session, facilitator, timestamps) and the
 * per-player standings derived from the score sheet at the time of completion.
 *
 * <p>The session itself is referenced only by identifier, so the result can outlive the session
 * row if the session is later swept.
 *
 * <p>The {@link Standing} list is the leaderboard in presentation order — best first, with a
 * seat-order tiebreak for stability. Positions are competition-ranked: two players on the same
 * total share a position and the next player is pushed past them. {@link Standing#tied()} makes
 * that visible without requiring the client to compare totals.
 *
 * <p>The STRIDE breakdown per player is <em>not</em> stored here. It is derived on demand from
 * the trick history by {@code GetLeaderboardPort}, per ADR-030. That type is named in prose
 * rather than linked because it lives in the application layer, and an entity may not import
 * outwards; see .opencode/rules/hexagonal-architecture.md.
 *
 * <p>Pure domain type: no Spring, no Jakarta, no persistence annotations.
 *
 * @param gameResultId        stable identifier for this result record
 * @param sessionId           the session this result belongs to
 * @param facilitatorName     the facilitator's display name at the time the game ended
 * @param startedAt           when the session moved from LOBBY to IN_PROGRESS
 * @param finalisedAt         when the last trick resolved and the session moved to COMPLETED
 * @param standings           the leaderboard rows, best first
 */
public record GameResult(
        UUID gameResultId,
        UUID sessionId,
        DisplayName facilitatorName,
        Instant startedAt,
        Instant finalisedAt,
        List<Standing> standings) {

    /**
     * Rejects a result that could not describe a completed game.
     */
    public GameResult {
        Objects.requireNonNull(gameResultId, "gameResultId is required");
        Objects.requireNonNull(sessionId, "sessionId is required");
        Objects.requireNonNull(facilitatorName, "facilitatorName is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(finalisedAt, "finalisedAt is required");
        Objects.requireNonNull(standings, "standings is required");
        if (standings.isEmpty()) {
            throw new IllegalArgumentException("A game result must have at least one standing");
        }
        if (finalisedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finalisedAt must not be before startedAt");
        }
        standings = List.copyOf(standings);
    }

    /**
     * Builds a {@code GameResult} from a completed session and its final score sheet.
     *
     * <p>The facilitator is the player whose role is {@link PlayerRole#FACILITATOR}. If no
     * facilitator is found among the players, the first player's name is used as a fallback —
     * this should not happen in a well-formed session, but the domain must not throw here
     * because the result is being recorded at the moment the game ends.
     *
     * @param gameResultId the identifier to assign to this result
     * @param session      the completed session
     * @param scoreSheet   the final score sheet
     * @param startedAt    when the session moved to IN_PROGRESS
     * @param finalisedAt  when the session moved to COMPLETED
     * @return the game result
     */
    public static GameResult of(
            final UUID gameResultId,
            final GameSession session,
            final ScoreSheet scoreSheet,
            final Instant startedAt,
            final Instant finalisedAt) {
        Objects.requireNonNull(gameResultId, "gameResultId is required");
        Objects.requireNonNull(session, "session is required");
        Objects.requireNonNull(scoreSheet, "scoreSheet is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(finalisedAt, "finalisedAt is required");

        final DisplayName facilitatorName = session.players().stream()
                .filter(p -> p.role() == PlayerRole.FACILITATOR)
                .map(Player::displayName)
                .findFirst()
                .orElseGet(() -> session.players().get(0).displayName());

        return new GameResult(
                gameResultId,
                session.sessionId(),
                facilitatorName,
                startedAt,
                finalisedAt,
                scoreSheet.standings());
    }
}
