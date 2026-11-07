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
import org.maglez.eop.domain.TrickPlay;

/**
 * The {@code trick_play_component} row: one component a play named.
 *
 * <p>Components are an ordered list on {@link TrickPlay}, and the order is the
 * player's own — the Score Card prints them as given. So the table stores an
 * {@code ordinal} and the primary key is {@code (trick_play_id, ordinal)}: the
 * composite is the identity, and no surrogate UUID is invented for a row with
 * nowhere to carry one (ADR-018, as narrowed 2026-08-12). {@link IdClass} rather than
 * {@code @EmbeddedId} for the reason given on {@link HandCardJpaEntity}: the columns
 * stay ordinary fields, so a query reads {@code findByTrickPlayIdOrderByOrdinalAsc}
 * rather than routing every predicate through an embedded object.
 *
 * <p>The ordinal is what makes the list a list rather than a set, and it is
 * range-checked in the database by {@code chk_trick_play_component_ordinal} against
 * {@link TrickPlay#MAX_COMPONENTS}. That constraint is unreachable by any legal play,
 * because the domain rejects an over-long list first, so ADR-023 maps a violation of
 * it to a 500 with a fixed detail: if it fires, the adapter is writing ordinals the
 * domain never produced.
 *
 * <p>{@code component_name} is free text bounded at
 * {@link TrickPlay#MAX_COMPONENT_NAME_LENGTH}, and deliberately not unique — a play
 * may name the same component twice, and deduplicating it here would silently edit
 * what a player wrote.
 */
@Entity
@Table(name = "trick_play_component")
@IdClass(TrickPlayComponentJpaEntity.Key.class)
class TrickPlayComponentJpaEntity {

    @Id
    @Column(name = "trick_play_id", nullable = false, updatable = false)
    private UUID trickPlayId;

    @Id
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "component_name", nullable = false, updatable = false,
            length = TrickPlay.MAX_COMPONENT_NAME_LENGTH)
    private String componentName;

    /**
     * Required by JPA. Not for application use.
     */
    protected TrickPlayComponentJpaEntity() {
        // JPA populates the fields after construction.
    }

    private TrickPlayComponentJpaEntity(final UUID trickPlayId, final int ordinal, final String componentName) {
        this.trickPlayId = trickPlayId;
        this.ordinal = ordinal;
        this.componentName = componentName;
    }

    /**
     * Builds the row for one named component.
     *
     * @param trickPlayId   the play that named it
     * @param ordinal       its zero-based position in the list the player gave
     * @param componentName the component named
     * @return an unsaved entity for that component
     */
    static TrickPlayComponentJpaEntity of(final UUID trickPlayId, final int ordinal, final String componentName) {
        return new TrickPlayComponentJpaEntity(trickPlayId, ordinal, componentName);
    }

    UUID getTrickPlayId() {
        return trickPlayId;
    }

    int getOrdinal() {
        return ordinal;
    }

    String getComponentName() {
        return componentName;
    }

    /**
     * The composite identifier of a {@code trick_play_component} row.
     *
     * <p>{@link Serializable}, no-argument constructor, value equality, field names
     * matching the entity's {@code @Id} fields — JPA's requirements, and the reason
     * this is a class rather than a record is the one given on
     * {@link HandCardJpaEntity.Key}.
     */
    @Embeddable
    static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID trickPlayId;

        private int ordinal;

        /**
         * Required by JPA. Not for application use.
         */
        protected Key() {
            // JPA populates the fields after construction.
        }

        Key(final UUID trickPlayId, final int ordinal) {
            this.trickPlayId = trickPlayId;
            this.ordinal = ordinal;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof final Key that)) {
                return false;
            }
            return ordinal == that.ordinal && Objects.equals(trickPlayId, that.trickPlayId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trickPlayId, ordinal);
        }

        @Override
        public String toString() {
            return "TrickPlayComponentJpaEntity.Key[trickPlayId=" + trickPlayId + ", ordinal=" + ordinal + "]";
        }
    }
}
