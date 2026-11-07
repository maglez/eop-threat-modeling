package org.maglez.eop.application.service;

import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.GameNotCompletedException;

import org.maglez.eop.domain.ScoreSheet;

import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.GameResultNotRecordedException;
import org.maglez.eop.application.port.in.GetLeaderboardPort;
import org.maglez.eop.application.port.in.LeaderboardResult;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.out.GameResultRepository;
import org.maglez.eop.application.port.out.TrickRepository;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Reads the final leaderboard for a completed session.
 *
 * <p>The leaderboard is only available once the session is in
 * {@link SessionStatus#COMPLETED} state. Requesting it while the session is still
 * in progress is a conflict — the resource exists but is not yet in the right state.
 *
 * <p>Any seated player may read the leaderboard. The result is public knowledge once
 * the game ends: every card has been played face up and the scores are derived from
 * those plays. Membership is still required so that a stranger who guesses a session
 * identifier cannot read the result (ADR-024).
 *
 * <p>Returns a {@link LeaderboardResult} carrying the resolved {@link SessionStatus} and the derived
 * {@link ScoreSheet}, so the controller can build the full leaderboard DTO (including the per-suit
 * STRIDE breakdown) from a single use-case call and without inventing the status itself.
 *
 * <p>The persisted {@code GameResult} row is read here but deliberately not returned. Scores are
 * recomputed from the trick history on every read (ADR-030), so the row holds nothing this endpoint
 * serves — it is an existence gate, and absent it the caller gets
 * {@link GameResultNotRecordedException} rather than a leaderboard derived from a game whose result
 * was never recorded. Until EOP-87 it was carried out through {@code LeaderboardResult} as a
 * non-null component that no consumer read.
 *
 * <p>Pure use case: no Spring, no Jakarta imports.
 */
public class GetLeaderboardService implements GetLeaderboardPort {

    private final ResolvePlayerPort resolvePlayerPort;

    private final GameResultRepository gameResultRepository;

    private final TrickRepository trickRepository;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerPort  resolves the acting player from the identity token
     * @param gameResultRepository  reads the persisted game result
     * @param trickRepository       reads the tricks for the STRIDE breakdown
     */
    public GetLeaderboardService(
            final ResolvePlayerPort resolvePlayerPort,
            final GameResultRepository gameResultRepository,
            final TrickRepository trickRepository) {
        this.resolvePlayerPort = Objects.requireNonNull(resolvePlayerPort, "resolvePlayerPort is required");
        this.gameResultRepository = Objects.requireNonNull(gameResultRepository, "gameResultRepository is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
    }

    /**
     * Reads the leaderboard for a completed session.
     *
     * @param sessionId   the session to read the leaderboard for
     * @param playerToken the caller's identity token, as presented
     * @return the leaderboard result carrying the resolved session status and the derived score sheet
     * @throws NullPointerException              if sessionId is null
     * @throws SessionNotFoundException          if no session has that identifier
     * @throws PlayerNotRecognisedException      if the token names nobody at this table
     * @throws GameNotCompletedException         if the session is not yet completed
     * @throws GameResultNotRecordedException    if the session is completed but no result was recorded
     */
    public LeaderboardResult execute(final UUID sessionId, final String playerToken) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        final var resolved = resolvePlayerPort.execute(sessionId, playerToken);
        if (resolved.session().status() != SessionStatus.COMPLETED) {
            throw new GameNotCompletedException(sessionId);
        }
        // Existence gate only: the row carries nothing the response serves, because standings are
        // recomputed from the tricks below on every read (ADR-030).
        if (gameResultRepository.findBySessionId(sessionId).isEmpty()) {
            throw new GameResultNotRecordedException(sessionId);
        }
        final var scoreSheet = ScoreSheet.of(resolved.session().players(),
                trickRepository.findTricks(sessionId));
        return new LeaderboardResult(resolved.session().status(), scoreSheet);
    }
}
