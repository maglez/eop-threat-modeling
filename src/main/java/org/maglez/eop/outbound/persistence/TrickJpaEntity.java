package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.TrickPlay;

/**
 * The {@code trick} row.
 *
 * <p>Separate from the domain {@link Trick} for the usual reason: the domain type is
 * immutable and framework-free, and JPA needs a mutable class with a no-argument
 * constructor.
 *
 * <p>No {@code @OneToMany} to {@link TrickPlayJpaEntity}, following
 * {@link GameSessionJpaEntity} and {@link HandJpaEntity}. A play is appended with an
 * explicit insert inside a conditional write, and a collection mapping would put
 * Hibernate's dirty-check between the caller and that insert — which is the one
 * place in this story where the statement issued has to be exactly the statement it
 * looks like, because the row lock taken before it is the concurrency guard
 * (ADR-020).
 *
 * <p>{@code leaderSeat} is this trick's historical record of who led it and never
 * changes once written. It is deliberately not the same thing as
 * {@code game_session.current_leader_seat}, which is the live pointer the
 * compare-and-set write path reads and advances. Changeset {@code 004} states the
 * distinction from the schema side; this class keeps it by marking the column
 * {@code updatable = false}.
 *
 * <p>{@code winnerPlayId} is nullable, and its nullness <em>is</em> the
 * resolved-or-not flag: {@link Trick#reconstitute} documents the argument as "the
 * stored winning play, or null if the trick is not yet resolved". There is no
 * {@code resolved_at} column because {@link Trick} has no timestamp field, and under
 * {@code ddl-auto=validate} an unmapped column would pass startup silently and then
 * sit permanently null.
 *
 * <p>What the column does not guarantee, stated because the foreign key behind it
 * reads like more than it is: {@code fk_trick_winner_play} proves the winner is
 * <em>some</em> play, not a play of <em>this</em> trick. Both holes were measured on
 * the deployed schema, and ADR-023's third Slice C obligation puts the check in the
 * resolve-trick use case, which will be the only check that exists.
 */
@Entity
@Table(name = "trick")
class TrickJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "game_session_id", nullable = false, updatable = false)
    private UUID gameSessionId;

    /**
     * The one-based position of this trick within its session.
     *
     * <p>{@code updatable = false} because renumbering a trick would reorder the
     * history of a game that has already been played. The column is also the second
     * half of {@code uq_trick_session_sequence}, so two requests racing to open the
     * same trick collide there rather than both succeeding.
     */
    @Column(name = "sequence", nullable = false, updatable = false)
    private int sequence;

    @Column(name = "leader_seat", nullable = false, updatable = false)
    private int leaderSeat;

    @Column(name = "winner_play_id")
    private UUID winnerPlayId;

    /**
     * Required by JPA. Not for application use.
     */
    protected TrickJpaEntity() {
        // JPA populates the fields after construction.
    }

    private TrickJpaEntity(
            final UUID id,
            final UUID gameSessionId,
            final int sequence,
            final int leaderSeat,
            final UUID winnerPlayId) {
        this.id = id;
        this.gameSessionId = gameSessionId;
        this.sequence = sequence;
        this.leaderSeat = leaderSeat;
        this.winnerPlayId = winnerPlayId;
    }

    /**
     * Builds a row from a trick.
     *
     * <p>The plays are not written from here. A trick's plays arrive one at a time,
     * each inside its own conditional write, so persisting them as a side effect of
     * saving the trick would collapse several transactions into one and lose the
     * per-play guard.
     *
     * @param gameSessionId the session the trick belongs to
     * @param trick         the trick to persist
     * @return an unsaved entity carrying that trick's state
     */
    static TrickJpaEntity fromDomain(final UUID gameSessionId, final Trick trick) {
        return new TrickJpaEntity(
                trick.trickId(),
                gameSessionId,
                trick.sequence(),
                trick.leaderSeat(),
                trick.winner().map(TrickPlay::trickPlayId).orElse(null));
    }

    /**
     * Rebuilds the trick from this row and the plays read alongside it.
     *
     * <p>The winning play is resolved by the caller rather than here, because this
     * row holds only its identifier while {@link Trick} holds the play itself. The
     * caller already has the plays, so it can match on identity without a second
     * read.
     *
     * @param plays  the plays made into this trick, in play order
     * @param winner the winning play, or {@code null} while the trick is unresolved
     * @return the reconstituted trick
     */
    Trick toDomain(final List<TrickPlay> plays, final TrickPlay winner) {
        return Trick.reconstitute(id, sequence, leaderSeat, plays, winner);
    }

    UUID getId() {
        return id;
    }

    int getSequence() {
        return sequence;
    }

    int getLeaderSeat() {
        return leaderSeat;
    }

    UUID getWinnerPlayId() {
        return winnerPlayId;
    }
}
