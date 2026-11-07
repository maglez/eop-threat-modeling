package org.maglez.eop.outbound.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link GameResultJpaEntity}.
 *
 * <p>Queries by {@code game_session_id} rather than by the result's own primary key,
 * because the application always looks up a result by the session it belongs to.
 *
 * <p>A session may accumulate multiple result rows when the facilitator starts a new game
 * after the first completes (ADR-039 §4). The finder returns the most recently finalised
 * row so that the leaderboard always reflects the latest completed game.
 */
interface GameResultJpaRepository extends JpaRepository<GameResultJpaEntity, UUID> {

    /**
     * Finds the most recently finalised result for a given session.
     *
     * <p>Returns the row with the latest {@code finalised_at} timestamp, so that after a
     * new-game reset the leaderboard reflects the second (or later) completed game rather
     * than the first. Returns empty if no result has been saved yet.
     *
     * @param gameSessionId the session identifier
     * @return the most recently finalised result row, or empty if none exists
     */
    Optional<GameResultJpaEntity> findFirstByGameSessionIdOrderByFinalisedAtDesc(UUID gameSessionId);
}
