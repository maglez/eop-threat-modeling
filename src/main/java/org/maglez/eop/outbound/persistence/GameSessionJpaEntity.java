package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.SessionStatus;

/**
 * The {@code game_session} row.
 *
 * <p>Separate from the domain {@link GameSession} for the same reason
 * {@link CardJpaEntity} is separate from a card: the domain type is immutable and
 * framework-free, while JPA requires a mutable class with a no-argument
 * constructor and its own annotations.
 *
 * <p>This entity deliberately does <em>not</em> declare a {@code @OneToMany} to the
 * players. Seats are read with an explicit ordered query and written with explicit
 * inserts, so no cascade, no orphan removal and no collection-dirty-checking
 * decides when a player row appears or disappears. At six players per table the
 * extra query costs nothing, and in exchange the write path is exactly the
 * statements it looks like.
 *
 * <p>Timestamps are {@link OffsetDateTime} here and {@link Instant} in the domain.
 * The columns are {@code TIMESTAMP WITH TIME ZONE}, and an offset-carrying Java
 * type is the mapping Hibernate validates without argument on both H2 and
 * PostgreSQL. The conversion is confined to this class.
 */
@Entity
@Table(name = "game_session")
class GameSessionJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "join_code", nullable = false, updatable = false, length = JoinCode.LENGTH)
    private String joinCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    /**
     * The seat leading the current trick, or {@code null} before any cards are
     * dealt.
     *
     * <p>Mapped here and nowhere else. The domain {@link GameSession} has no leader
     * field and must not gain one: the current leader is a fact about the trick in
     * progress, and {@code Trick} already derives whose turn it is from its own
     * plays. A copy on the aggregate would be a second authority on a fact the
     * trick owns, which is the objection ADR-023 raises against storing a player id
     * beside a seat.
     *
     * <p>So why store it at all? Because it is the compare-and-set witness. The
     * conditional {@code UPDATE} that guards a deal, a play or a resolution names
     * the leader seat the caller's snapshot showed, and zero rows affected means the
     * snapshot is stale. As with {@code touchWhileInStatus}, the serialisation rests
     * on the row lock that update takes and holds — taken on {@code game_session}
     * before any {@code hand} or {@code trick_play} row is touched, which is both
     * the concurrency guard and the lock order ADR-023 requires.
     *
     * <p>It advances once per trick, not once per play. A play reads it and locks the
     * row without changing it; a deal writes the opening leader; a resolution writes
     * the next one.
     *
     * <p>{@link Integer} rather than {@code int} because the column is nullable, and
     * null is meaningful: it is how "no cards dealt yet" is stored, which is what
     * makes the deal-once guard expressible as {@code current_leader_seat IS NULL}.
     * The range is bounded in the database by
     * {@code chk_game_session_current_leader_seat}.
     */
    @Column(name = "current_leader_seat")
    private Integer currentLeaderSeat;

    /**
     * Change counter for this row. <strong>This is not the concurrency
     * control.</strong>
     *
     * <p>The annotation is mapped, but nothing in this repository handles
     * {@code OptimisticLockingFailureException}, and all five of the conditional
     * writes increment this column by hand in JPQL rather than going through
     * Hibernate's dirty-check path, which is what leaves the annotation inert.
     * Serialisation actually rests on the row lock that whichever of those five
     * statements runs first acquires and holds to the end of the transaction,
     * together with the unique constraints. ADR-020 records why, and warns that
     * {@code touchWhileInStatus} reads like a timestamp bump while being the
     * mechanism the session lifecycle depends on — do not delete it on the grounds
     * that the framework has locking covered. One caveat ADR-020 spells out: a
     * compare-and-set on this row is not automatically a replay guard.
     * {@code advanceLeaderSeat} is idempotent when a trick's leader wins it, so
     * trick resolution is serialised by the trick row instead.
     *
     * <p>The domain aggregate has no version field, because a version is a
     * statement about a row rather than about a game. Keeping it inside this
     * package means the use case layer never has to carry one across the port
     * just to hand it back.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Required by JPA. Not for application use.
     */
    protected GameSessionJpaEntity() {
        // JPA populates the fields after construction.
    }

    private GameSessionJpaEntity(
            final UUID id,
            final String joinCode,
            final SessionStatus status,
            final OffsetDateTime createdAt,
            final OffsetDateTime updatedAt,
            final OffsetDateTime expiresAt) {
        this.id = id;
        this.joinCode = joinCode;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * Builds a row from a freshly opened lobby.
     *
     * <p>The identifier is taken from the aggregate rather than generated here,
     * because the aggregate already exists and already validated it. Assigning an
     * identifier at flush time would mean a domain object could exist without one
     * (ADR-018).
     *
     * @param session the session to persist
     * @return an unsaved entity carrying that session's state
     */
    static GameSessionJpaEntity fromDomain(final GameSession session) {
        return new GameSessionJpaEntity(
                session.sessionId(),
                session.joinCode().value(),
                session.status(),
                session.createdAt().atOffset(ZoneOffset.UTC),
                session.updatedAt().atOffset(ZoneOffset.UTC),
                session.expiresAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * Rebuilds the aggregate from this row and the seats read alongside it.
     *
     * <p>The domain factory revalidates everything, so a row edited outside the
     * application fails here rather than travelling on as a valid-looking session.
     *
     * @param players the seated players, which the caller reads separately
     * @return the reconstituted aggregate
     */
    GameSession toDomain(final List<Player> players) {
        return GameSession.reconstitute(
                id,
                new JoinCode(joinCode),
                status,
                players,
                createdAt.toInstant(),
                updatedAt.toInstant(),
                expiresAt.toInstant());
    }

    UUID getId() {
        return id;
    }

    SessionStatus getStatus() {
        return status;
    }

    /**
     * The seat leading the current trick, or {@code null} before any cards are dealt.
     *
     * @return the current leader's seat, or {@code null} if none has been set
     */
    Integer getCurrentLeaderSeat() {
        return currentLeaderSeat;
    }
}
