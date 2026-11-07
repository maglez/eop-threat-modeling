package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.maglez.eop.domain.ConnectionStatus;
import org.maglez.eop.domain.DisplayName;
import org.maglez.eop.domain.IdentityTokenHash;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerRole;

/**
 * The {@code player} row.
 *
 * <p>The owning session is held as a plain {@code UUID} column rather than a
 * {@code @ManyToOne} association. Nothing in this adapter needs to navigate from a
 * seat to its session — every read starts from the session — and a plain column
 * means seating a player is one insert with no proxy, no lazy load and no
 * bidirectional collection to keep in step.
 *
 * <p>The column is {@code player_role} rather than {@code role}, for the same
 * reason the card table has {@code card_rank}: the shorter word is reserved or
 * near-reserved on enough engines that the migration is not worth the argument.
 */
@Entity
@Table(name = "player")
class PlayerJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "game_session_id", nullable = false, updatable = false)
    private UUID gameSessionId;

    @Column(name = "display_name", nullable = false, length = DisplayName.MAX_LENGTH)
    private String displayName;

    /**
     * Assigned once, when the player joins, and never updated.
     *
     * <p>{@code updatable = false} is the second guard behind
     * {@code uq_player_session_seat}. Play is clockwise, so a seat that moved
     * would silently change whose turn it is; a reconnect must leave every player
     * exactly where they were.
     */
    @Column(name = "seat_order", nullable = false, updatable = false)
    private int seatOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "player_role", nullable = false, updatable = false, length = 16)
    private PlayerRole playerRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false, length = 16)
    private ConnectionStatus connectionStatus;

    /**
     * The SHA-256 digest of the player's identity credential, in lower-case hex.
     *
     * <p>The credential itself is never stored and never logged. This column is the whole of
     * what the application keeps (ADR-015).
     *
     * <p>The {@code uq_player_identity_token_hash} constraint on this column exists to enforce
     * uniqueness, and it is never used as a lookup path: no query in
     * {@link PlayerJpaRepository} selects by this column, so a digest is never resolved by an
     * index hit. Matching happens in memory instead, in
     * {@link org.maglez.eop.domain.Player#isIdentifiedBy}, which is why
     * {@link IdentityTokenHash} compares digests in constant time rather than relying on the
     * database to do the comparison. Do not describe this constraint as serving a credential
     * lookup — an earlier version of this comment did, and EOP-120 removed that claim because
     * it named a mechanism the code does not contain.
     */
    @Column(name = "identity_token_hash", nullable = false, updatable = false, length = IdentityTokenHash.HEX_LENGTH)
    private String identityTokenHash;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    /**
     * Required by JPA. Not for application use.
     */
    protected PlayerJpaEntity() {
        // JPA populates the fields after construction.
    }

    private PlayerJpaEntity(
            final UUID id,
            final UUID gameSessionId,
            final String displayName,
            final int seatOrder,
            final PlayerRole playerRole,
            final ConnectionStatus connectionStatus,
            final String identityTokenHash,
            final OffsetDateTime joinedAt) {
        this.id = id;
        this.gameSessionId = gameSessionId;
        this.displayName = displayName;
        this.seatOrder = seatOrder;
        this.playerRole = playerRole;
        this.connectionStatus = connectionStatus;
        this.identityTokenHash = identityTokenHash;
        this.joinedAt = joinedAt;
    }

    /**
     * Builds a row for a player about to take a seat.
     *
     * @param gameSessionId the session the player is joining
     * @param player        the domain player, already validated
     * @return an unsaved entity carrying that player's state
     */
    static PlayerJpaEntity fromDomain(final UUID gameSessionId, final Player player) {
        return new PlayerJpaEntity(
                player.playerId(),
                gameSessionId,
                player.displayName().value(),
                player.seatOrder(),
                player.role(),
                player.connectionStatus(),
                player.identityTokenHash().value(),
                player.joinedAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * Rebuilds the domain player from this row.
     *
     * <p>The domain constructor revalidates the display name, the seat range and
     * the digest format, so a row corrupted outside the application fails here.
     *
     * @return the reconstituted player
     */
    Player toDomain() {
        return new Player(
                id,
                new DisplayName(displayName),
                seatOrder,
                playerRole,
                connectionStatus,
                new IdentityTokenHash(identityTokenHash),
                joinedAt.toInstant());
    }

    UUID getId() {
        return id;
    }

    int getSeatOrder() {
        return seatOrder;
    }
}
