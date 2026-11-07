package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.TrickPlay;

/**
 * The {@code trick_play} row: one card played into one trick.
 *
 * <p>Separate from the domain {@link TrickPlay} for the usual reason, with one extra
 * force behind it here: {@code TrickPlay} holds a whole {@link Card}, while the row
 * holds only {@code card_id}. The card is seeded reference data, so storing it by
 * identifier and resolving it on read is what makes {@link Card} single-sourced —
 * and it is also the security property behind ADR-023's first obligation, since a
 * play cannot carry a suit or rank the catalogue does not agree with.
 *
 * <p>{@code seat_order} is stored alongside {@code player_id} even though
 * {@code player} already relates the two. That is not redundancy left in by
 * accident: {@code fk_trick_play_player_seat} binds the pair to
 * {@code player (id, seat_order)}, so the two columns cannot disagree, and the
 * composite key is what makes a forged seat unwritable rather than merely unusual.
 * The backstop is measured — {@code TrickPlayForeignKeyTest} asserts SQLSTATE
 * {@code 23506} — and if it ever fires it means a use-case check was missed, which
 * is why the adapter logs that translation at WARN.
 *
 * <p>{@code threat_linked} being false is an ordinary legal outcome, not an error: a
 * player who cannot connect their card to the system still plays it, the card still
 * competes for the trick, and the play simply scores nothing (PRD §3.3). Nothing in
 * this mapping treats false as a missing value.
 *
 * <p>No {@code @ElementCollection} for the components. They are their own table with
 * their own ordinal, mapped by {@link TrickPlayComponentJpaEntity} and written with
 * explicit inserts, for the same reason no other collection in this package is
 * mapped as one.
 *
 * <p>{@code playedAt} is {@link OffsetDateTime} here and {@link Instant} in the
 * domain, exactly as {@link GameSessionJpaEntity} does it: the column is
 * {@code TIMESTAMP WITH TIME ZONE} and an offset-carrying Java type is what
 * Hibernate validates without argument on both H2 and PostgreSQL. The conversion is
 * confined to this class.
 */
@Entity
@Table(name = "trick_play")
class TrickPlayJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "trick_id", nullable = false, updatable = false)
    private UUID trickId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private UUID playerId;

    @Column(name = "seat_order", nullable = false, updatable = false)
    private int seatOrder;

    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @Column(name = "threat_linked", nullable = false, updatable = false)
    private boolean threatLinked;

    @Column(name = "notes", length = TrickPlay.MAX_NOTES_LENGTH, updatable = false)
    private String notes;

    @Column(name = "played_at", nullable = false, updatable = false)
    private OffsetDateTime playedAt;

    /**
     * Required by JPA. Not for application use.
     */
    protected TrickPlayJpaEntity() {
        // JPA populates the fields after construction.
    }

    private TrickPlayJpaEntity(
            final UUID id,
            final UUID trickId,
            final UUID playerId,
            final int seatOrder,
            final UUID cardId,
            final boolean threatLinked,
            final String notes,
            final OffsetDateTime playedAt) {
        this.id = id;
        this.trickId = trickId;
        this.playerId = playerId;
        this.seatOrder = seatOrder;
        this.cardId = cardId;
        this.threatLinked = threatLinked;
        this.notes = notes;
        this.playedAt = playedAt;
    }

    /**
     * Builds a row from a play.
     *
     * <p>Every column except {@code trick_id} comes from the play itself, including
     * the timestamp: {@link TrickPlay} carries its own {@code playedAt}, and taking
     * a second timestamp from the adapter would make two authorities on one fact.
     *
     * @param trickId the trick the play was made into
     * @param play    the play to persist
     * @return an unsaved entity carrying that play's state
     */
    static TrickPlayJpaEntity fromDomain(final UUID trickId, final TrickPlay play) {
        return new TrickPlayJpaEntity(
                play.trickPlayId(),
                trickId,
                play.playerId(),
                play.seatOrder(),
                play.card().cardId(),
                play.threatLinked(),
                play.notes(),
                play.playedAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * Rebuilds the play from this row, the card it named and the components read
     * alongside it.
     *
     * <p>The card is passed in rather than looked up here, so that one catalogue
     * read serves every play in a trick. The domain constructor revalidates, so a
     * row edited outside the application fails here rather than travelling on as a
     * valid-looking play.
     *
     * @param card       the card played, resolved from the catalogue
     * @param components the components this play named, in ordinal order
     * @return the reconstituted play
     */
    TrickPlay toDomain(final Card card, final List<String> components) {
        return new TrickPlay(id, playerId, seatOrder, card, threatLinked, components, notes, playedAt.toInstant());
    }

    UUID getId() {
        return id;
    }

    UUID getTrickId() {
        return trickId;
    }

    int getSeatOrder() {
        return seatOrder;
    }

    UUID getCardId() {
        return cardId;
    }
}
