package org.maglez.eop.outbound.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.DisplayName;
import org.maglez.eop.domain.GameResult;
import org.maglez.eop.application.port.out.IdentifierGenerator;
import org.maglez.eop.domain.Standing;
import org.maglez.eop.application.port.out.GameResultRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and retrieves {@link GameResult} aggregates.
 *
 * <p>A result is write-once: once saved it is never updated. The {@link #save} method
 * inserts the header row and all player rows in a single transaction.
 *
 * <p>On read, the {@link GameResult} is reconstructed with placeholder standings (score=0,
 * position=1, tied=false). The leaderboard controller derives scores, positions and the
 * {@code tied} flag from the live {@link org.maglez.eop.domain.ScoreSheet} (re-read from
 * tricks at request time), satisfying ADR-030: a persisted standing must never be read
 * back to answer the score. The player rows are read only to reconstruct player identity
 * (playerId, seatOrder, displayName) for the {@code GameResult} record.
 */
@Repository
public class GameResultRepositoryAdapter implements GameResultRepository {

    private final GameResultJpaRepository resultRows;

    private final GameResultPlayerJpaRepository playerRows;

    private final IdentifierGenerator identifierGenerator;

    GameResultRepositoryAdapter(
            final GameResultJpaRepository resultRows,
            final GameResultPlayerJpaRepository playerRows,
            final IdentifierGenerator identifierGenerator) {
        this.resultRows = Objects.requireNonNull(resultRows, "resultRows is required");
        this.playerRows = Objects.requireNonNull(playerRows, "playerRows is required");
        this.identifierGenerator =
                Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
    }

    @Override
    @Transactional
    public void save(final GameResult result) {
        Objects.requireNonNull(result, "result is required");
        resultRows.saveAndFlush(GameResultJpaEntity.fromDomain(result));
        for (final Standing standing : result.standings()) {
            playerRows.saveAndFlush(
                    GameResultPlayerJpaEntity.fromDomain(
                            identifierGenerator.nextIdentifier(), result.gameResultId(), standing));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GameResult> findBySessionId(final UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        return resultRows.findFirstByGameSessionIdOrderByFinalisedAtDesc(sessionId).map(this::assemble);
    }

    /**
     * Rebuilds a {@link GameResult} from its header row and player rows.
     *
     * <p>Player rows are read only for identity (playerId, seatOrder, displayName).
     * Scores, positions and the {@code tied} flag are set to placeholder values
     * (score=0, position=1, tied=false) because the leaderboard controller derives
     * them from the live {@link org.maglez.eop.domain.ScoreSheet}, not from stored data
     * (ADR-030).
     *
     * @param header the result header row
     * @return the reconstituted domain object with placeholder standings
     */
    private GameResult assemble(final GameResultJpaEntity header) {
        final var rows = playerRows.findByGameResultIdOrderBySeatOrderAsc(header.getId());
        final var standings = placeholderStandings(rows);
        return header.toDomain(standings);
    }

    /**
     * Converts player rows into placeholder {@link Standing} objects.
     *
     * <p>Only identity fields (playerId, seatOrder, displayName) are populated.
     * Score, position and tied are set to neutral defaults because the leaderboard
     * derives those values from the live score sheet (ADR-030).
     *
     * @param rows the player rows in seat order
     * @return standings with identity fields only
     */
    private static List<Standing> placeholderStandings(final List<GameResultPlayerJpaEntity> rows) {
        return rows.stream()
                .map(row -> new Standing(
                        row.getPlayerId(),
                        row.getSeatOrder(),
                        new DisplayName(row.getDisplayName()),
                        0,
                        1,
                        false))
                .toList();
    }
}
