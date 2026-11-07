package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code hand_card} row: one card sitting in one hand.
 *
 * <p>The table has no surrogate key. Its primary key is {@code (hand_id, card_id)},
 * which changeset {@code 004} chose deliberately — the pair <em>is</em> the
 * identity, and it is also what stops the same card appearing twice in one hand
 * without any application check. Inventing a UUID for these rows would have added a
 * column that identifies nothing (ADR-018, as narrowed 2026-08-12).
 *
 * <p>So the mapping needs a composite identifier, and it uses {@link IdClass} rather
 * than {@code @EmbeddedId}. Both express the same key; the difference is what the
 * rest of the adapter has to write. With {@code @IdClass} the two columns stay
 * ordinary fields on the entity, so a derived query reads
 * {@code deleteByHandIdAndCardId}. With {@code @EmbeddedId} the same query is
 * {@code deleteByIdHandIdAndIdCardId}, and every JPQL predicate gains a hop through
 * the embedded object. The key class exists only to satisfy JPA and is never handed
 * to a caller.
 *
 * <p>Neither column is an association. A played card is removed with one delete
 * keyed on the pair, which is exactly the statement {@code pk_hand_card} indexes,
 * and {@link HandJpaEntity} deliberately holds no collection of these rows for the
 * reason recorded there.
 *
 * <p>{@code card_id} points into the seeded catalogue, and changeset {@code 004}
 * gives {@code fk_hand_card_card} no {@code onDelete} precisely so that a card
 * sitting in someone's hand cannot be deleted. Nothing in this class should be read
 * as owning a card: it owns the fact that a card is in a hand.
 */
@Entity
@Table(name = "hand_card")
@IdClass(HandCardJpaEntity.Key.class)
class HandCardJpaEntity {

    @Id
    @Column(name = "hand_id", nullable = false, updatable = false)
    private UUID handId;

    @Id
    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    /**
     * Required by JPA. Not for application use.
     */
    protected HandCardJpaEntity() {
        // JPA populates the fields after construction.
    }

    private HandCardJpaEntity(final UUID handId, final UUID cardId) {
        this.handId = handId;
        this.cardId = cardId;
    }

    /**
     * Builds the row that puts a card in a hand.
     *
     * @param handId the hand holding the card
     * @param cardId the card held
     * @return an unsaved entity for that pair
     */
    static HandCardJpaEntity of(final UUID handId, final UUID cardId) {
        return new HandCardJpaEntity(handId, cardId);
    }

    UUID getHandId() {
        return handId;
    }

    UUID getCardId() {
        return cardId;
    }

    /**
     * The composite identifier of a {@code hand_card} row.
     *
     * <p>Required to be {@link Serializable} with a no-argument constructor and
     * value equality, and its field names must match the {@code @Id} fields on the
     * entity. It is a class rather than a record because {@code @Embeddable}
     * identifier classes are the one place in this codebase where JPA's
     * requirements win over immutability: Hibernate instantiates the key and
     * populates it reflectively.
     */
    @Embeddable
    static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID handId;

        private UUID cardId;

        /**
         * Required by JPA. Not for application use.
         */
        protected Key() {
            // JPA populates the fields after construction.
        }

        Key(final UUID handId, final UUID cardId) {
            this.handId = handId;
            this.cardId = cardId;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof final Key that)) {
                return false;
            }
            return Objects.equals(handId, that.handId) && Objects.equals(cardId, that.cardId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(handId, cardId);
        }

        @Override
        public String toString() {
            return "HandCardJpaEntity.Key[handId=" + handId + ", cardId=" + cardId + "]";
        }
    }
}
