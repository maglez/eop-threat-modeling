package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.maglez.eop.domain.DisplayName;
import org.maglez.eop.domain.Standing;

/**
 * The {@code game_result_player} row: one player's final standing in a completed game.
 *
 * <p>Separate from the domain {@link Standing} for the usual reason: the domain type is
 * immutable and framework-free, while JPA requires a mutable class with a no-argument
 * constructor and its own annotations.
 *
 * <p>The {@code position} and {@code tied} fields are derived from the full standings list
 * at the time the result is persisted, so they are stored here for fast retrieval without
 * recomputing the ranking on every leaderboard read.
 *
 * <p>No {@code @Version} column: player result rows are write-once. Once saved they are
 * never updated.
 */
@Entity
@Table(name = "game_result_player")
class GameResultPlayerJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "game_result_id", nullable = false, updatable = false)
    private UUID gameResultId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private UUID playerId;

    @Column(name = "display_name", nullable = false, updatable = false, length = 40)
    private String displayName;

    @Column(name = "seat_order", nullable = false, updatable = false)
    private int seatOrder;

    @Column(name = "score", nullable = false, updatable = false)
    private int score;

    /**
     * Required by JPA. Not for application use.
     */
    protected GameResultPlayerJpaEntity() {
        // JPA populates the fields after construction.
    }

    private GameResultPlayerJpaEntity(
            final UUID id,
            final UUID gameResultId,
            final UUID playerId,
            final String displayName,
            final int seatOrder,
            final int score) {
        this.id = id;
        this.gameResultId = gameResultId;
        this.playerId = playerId;
        this.displayName = displayName;
        this.seatOrder = seatOrder;
        this.score = score;
    }

    /**
     * Builds a row from a domain {@link Standing}.
     *
     * @param rowId        a fresh identifier for this row
     * @param gameResultId the result this player row belongs to
     * @param standing     the standing to persist
     * @return an unsaved entity carrying that standing's data
     */
    static GameResultPlayerJpaEntity fromDomain(
            final UUID rowId, final UUID gameResultId, final Standing standing) {
        return new GameResultPlayerJpaEntity(
                rowId,
                gameResultId,
                standing.playerId(),
                standing.displayName().value(),
                standing.seatOrder(),
                standing.points());
    }

    /**
     * Rebuilds the domain {@link Standing} from this row.
     *
     * <p>{@code position} and {@code tied} are not stored in the database — they are
     * recomputed from the full list of player rows when the leaderboard is assembled.
     * The adapter that calls this method is responsible for computing those values
     * before constructing the final {@link Standing}.
     *
     * @return a standing with position=1 and tied=false as placeholders; the caller
     *         must replace these with the correct values
     */
    Standing toDomain() {
        // position and tied are recomputed by the adapter from the full standings list
        return new Standing(playerId, seatOrder, new DisplayName(displayName), score, 1, false);
    }

    UUID getId() {
        return id;
    }

    UUID getGameResultId() {
        return gameResultId;
    }

    UUID getPlayerId() {
        return playerId;
    }

    int getSeatOrder() {
        return seatOrder;
    }

    int getScore() {
        return score;
    }

    String getDisplayName() {
        return displayName;
    }
}
