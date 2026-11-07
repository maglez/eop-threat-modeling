package org.maglez.eop.application.port.out;

import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.GameResult;

/**
 * Port through which game results are persisted and retrieved.
 *
 * <p>A game result is written once, when the last trick resolves, and read on demand
 * for the leaderboard. There is no update path: the result is immutable once recorded.
 */
public interface GameResultRepository {

    /**
     * Persists a game result.
     *
     * <p>Called at most once per session. A second call for the same session is a
     * programming error and the implementation may throw.
     *
     * @param result the result to persist
     */
    void save(GameResult result);

    /**
     * Reads the game result for a completed session.
     *
     * @param sessionId the session to look up
     * @return the result, or empty if no result has been recorded for that session
     */
    Optional<GameResult> findBySessionId(UUID sessionId);
}
