package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.maglez.eop.domain.DisplayName;
import org.maglez.eop.domain.GameResult;


/**
 * The {@code game_result} row: the persisted outcome of one completed game session.
 *
 * <p>Separate from the domain {@link GameResult} for the usual reason: the domain type is
 * immutable and framework-free, while JPA requires a mutable class with a no-argument
 * constructor and its own annotations.
 *
 * <p>No {@code @OneToMany} to {@link GameResultPlayerJpaEntity}. Player rows are read with
 * an explicit query and written with explicit inserts, following the pattern established by
 * {@link GameSessionJpaEntity} and {@link HandJpaEntity}.
 *
 * <p>No {@code @Version} column: game results are write-once. Once a result is saved it is
 * never updated, so optimistic locking adds no value and the column would sit permanently at
 * zero.
 */
@Entity
@Table(name = "game_result")
class GameResultJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "game_session_id", nullable = false, updatable = false)
    private UUID gameSessionId;

    @Column(name = "facilitator_display_name", nullable = false, updatable = false, length = 40)
    private String facilitatorDisplayName;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finalised_at", nullable = false, updatable = false)
    private OffsetDateTime finalisedAt;

    /**
     * Required by JPA. Not for application use.
     */
    protected GameResultJpaEntity() {
        // JPA populates the fields after construction.
    }

    private GameResultJpaEntity(
            final UUID id,
            final UUID gameSessionId,
            final String facilitatorDisplayName,
            final OffsetDateTime startedAt,
            final OffsetDateTime finalisedAt) {
        this.id = id;
        this.gameSessionId = gameSessionId;
        this.facilitatorDisplayName = facilitatorDisplayName;
        this.startedAt = startedAt;
        this.finalisedAt = finalisedAt;
    }

    /**
     * Builds a row from a domain {@link GameResult}.
     *
     * @param result the result to persist
     * @return an unsaved entity carrying that result's identity
     */
    static GameResultJpaEntity fromDomain(final GameResult result) {
        return new GameResultJpaEntity(
                result.gameResultId(),
                result.sessionId(),
                result.facilitatorName().value(),
                result.startedAt().atOffset(ZoneOffset.UTC),
                result.finalisedAt().atOffset(ZoneOffset.UTC));
    }

    UUID getId() {
        return id;
    }

    UUID getGameSessionId() {
        return gameSessionId;
    }

    String getFacilitatorDisplayName() {
        return facilitatorDisplayName;
    }

    OffsetDateTime getStartedAt() {
        return startedAt;
    }

    OffsetDateTime getFinalisedAt() {
        return finalisedAt;
    }

    /**
     * Rebuilds the domain object from this row and the pre-ranked standings.
     *
     * @param standings the ranked standings for this result, in seat order
     * @return the reconstituted domain object
     */
    GameResult toDomain(final java.util.List<org.maglez.eop.domain.Standing> standings) {
        return new GameResult(
                id,
                gameSessionId,
                new DisplayName(facilitatorDisplayName),
                startedAt.toInstant(),
                finalisedAt.toInstant(),
                standings);
    }
}
