package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.Hand;
import org.maglez.eop.domain.Hands;

/**
 * The {@code hand} row: one seat's holding in one session.
 *
 * <p>Separate from the domain {@link Hand} for the reason every entity in this
 * package is separate from its aggregate: the domain type is immutable and
 * framework-free, while JPA requires a mutable class with a no-argument
 * constructor and its own annotations.
 *
 * <p>No {@code @OneToMany} to {@link HandCardJpaEntity}, following
 * {@link GameSessionJpaEntity}. Cards are read with an explicit query and a played
 * card is removed with an explicit delete, so no cascade and no
 * collection-dirty-checking decides when a card leaves a hand. That matters more
 * here than it does for seats: a play removes exactly one row, and an
 * orphan-removal mapping would make the write path a diff of two collections
 * instead of the single statement it should be.
 *
 * <p>The session and the player are held as plain {@code UUID} columns rather than
 * associations, again as {@link PlayerJpaEntity} does. Every read starts from the
 * session, so nothing needs to navigate the other way.
 *
 * <p>This entity carries {@code seatOrder}, which the domain {@link Hand} does not.
 * A hand knows its own identifier, its player and its cards; the seat that holds it
 * belongs to {@link Hands}, which keys hands by seat. So the column is read back
 * through {@link #getSeatOrder()} and used by the adapter to build the map
 * {@link Hands#reconstitute} expects, rather than being pushed into {@code Hand}
 * where it would be a second authority on a fact {@code Hands} already owns.
 *
 * <p>There are no timestamps, because {@code Hand} has no timestamp fields.
 * Changeset {@code 004} records the same decision from the schema side: a NOT NULL
 * timestamp here would have been an insert failure waiting for the first write
 * path.
 */
@Entity
@Table(name = "hand")
class HandJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "game_session_id", nullable = false, updatable = false)
    private UUID gameSessionId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private UUID playerId;

    /**
     * The seat this hand belongs to, assigned when the cards are dealt.
     *
     * <p>{@code updatable = false} for the same reason it is set on
     * {@link PlayerJpaEntity}: play is clockwise, so a seat that moved would
     * silently change whose turn it is. The column is also one half of
     * {@code fk_hand_player_seat}, which binds it to the player's own seat, so a
     * value that drifted from {@code player.seat_order} could not be written at
     * all.
     */
    @Column(name = "seat_order", nullable = false, updatable = false)
    private int seatOrder;

    /**
     * Required by JPA. Not for application use.
     */
    protected HandJpaEntity() {
        // JPA populates the fields after construction.
    }

    private HandJpaEntity(final UUID id, final UUID gameSessionId, final UUID playerId, final int seatOrder) {
        this.id = id;
        this.gameSessionId = gameSessionId;
        this.playerId = playerId;
        this.seatOrder = seatOrder;
    }

    /**
     * Builds a row from a dealt hand and the seat it was dealt to.
     *
     * <p>The identifier comes from the hand rather than being generated here,
     * because the hand already exists and already validated it (ADR-018).
     *
     * @param gameSessionId the session the hand belongs to
     * @param seatOrder     the seat holding the hand
     * @param hand          the hand to persist
     * @return an unsaved entity carrying that hand's identity
     */
    static HandJpaEntity fromDomain(final UUID gameSessionId, final int seatOrder, final Hand hand) {
        return new HandJpaEntity(hand.handId(), gameSessionId, hand.playerId(), seatOrder);
    }

    /**
     * Rebuilds the hand from this row and the cards read alongside it.
     *
     * <p>The domain factory revalidates, so a row edited outside the application
     * fails here rather than travelling on as a valid-looking hand.
     *
     * @param cards the cards held, which the caller reads separately
     * @return the reconstituted hand
     */
    Hand toDomain(final List<Card> cards) {
        return Hand.of(id, playerId, cards);
    }

    UUID getId() {
        return id;
    }

    UUID getPlayerId() {
        return playerId;
    }

    int getSeatOrder() {
        return seatOrder;
    }
}
