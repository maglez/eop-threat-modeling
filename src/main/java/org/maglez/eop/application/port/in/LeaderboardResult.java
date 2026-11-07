package org.maglez.eop.application.port.in;

import java.util.Objects;
import org.maglez.eop.domain.ScoreSheet;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.GameResultNotRecordedException;

/**
 * Carries what the leaderboard endpoint needs: the status of the session it read, and the
 * {@link ScoreSheet} derived from that session's trick history.
 *
 * <p>The status is here so the adapter does not have to invent it. Before EOP-87
 * {@code GameOverController} wrote the literal {@code "COMPLETED"} into its response, which was
 * correct only by accident: {@link GetLeaderboardPort} refuses any other status with
 * {@code GameNotCompletedException}, so the literal happened to agree with the row. That is temporal
 * coupling to another class's guard clause — the literal starts lying the moment the guard is
 * relaxed, and it lies silently, because nothing compares it to anything. Reading the status from
 * the session the use case already resolved costs no extra lookup and cannot drift.
 *
 * <p>Only {@link SessionStatus#COMPLETED} can reach a caller today, for the reason just given. The
 * point is not that the value varies now but that it is now <em>derived</em>, so it will still be
 * right if the guard changes.
 *
 * <p>This record deliberately does <strong>not</strong> carry the persisted {@code GameResult}. It
 * used to, and its javadoc claimed the endpoint needed it "for the standings and metadata" — untrue:
 * no consumer ever read the component, because standings are recomputed from the {@code ScoreSheet}
 * on every read (ADR-030) and the response carries no session metadata. The row is still fetched
 * inside {@link GetLeaderboardPort}, where it serves as the existence gate that raises
 * {@link GameResultNotRecordedException}, but a gate belongs at the gate. Carrying it out through a
 * {@code Objects.requireNonNull} component that nothing reads only advertised a dependency the code
 * did not have. Reviving it means giving it a reader and a field in the response contract first.
 *
 * <p>Returning the score sheet from the same call keeps the controller free of
 * {@link GetScorePort} as a second dependency.
 *
 * <p>Pure value type: no Spring, no Jakarta imports.
 *
 * @param sessionStatus the status of the session the leaderboard was read from, as resolved by the use case
 * @param scoreSheet the score sheet derived from that session's tricks, holding the per-suit STRIDE breakdown
 */
public record LeaderboardResult(SessionStatus sessionStatus, ScoreSheet scoreSheet) {

    /**
     * Creates a leaderboard result.
     *
     * @param sessionStatus the resolved status of the session
     * @param scoreSheet the derived score sheet for the same session
     */
    public LeaderboardResult {
        Objects.requireNonNull(sessionStatus, "sessionStatus is required");
        Objects.requireNonNull(scoreSheet, "scoreSheet is required");
    }
}
