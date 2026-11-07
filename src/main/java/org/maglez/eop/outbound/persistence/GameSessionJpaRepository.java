package org.maglez.eop.outbound.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the session table.
 *
 * <p>Package private, and not the application's port.
 * {@link SessionRepositoryAdapter} wraps it, so nothing above this package can
 * reach a Spring Data type.
 *
 * <p>All five update methods are deliberately conditional. Each carries the status
 * the caller believed the session was in, and reports how many rows it changed, so
 * a caller that read {@code LOBBY} and acted on it cannot have its write applied to
 * a session that has since started. Zero rows changed is not a failure of the
 * statement — it is the answer that the world moved underneath the request, and the
 * adapter turns it into a domain exception.
 */
interface GameSessionJpaRepository extends JpaRepository<GameSessionJpaEntity, UUID> {

    /**
     * Looks a session up by its shareable join code.
     *
     * @param joinCode the canonical upper-case code, exactly as stored
     * @return the row, or empty when no session carries that code
     */
    Optional<GameSessionJpaEntity> findByJoinCode(String joinCode);

    /**
     * Advances the modification timestamp, but only while the session is in the
     * expected status.
     *
     * <p>Used before seating a player. Besides validating the status, the update
     * takes a row lock that is held to the end of the transaction, which is what
     * serialises two people joining the same table in the same instant.
     *
     * @param sessionId the session to touch
     * @param required  the status the caller observed
     * @param now       the new modification timestamp
     * @return the number of rows changed: one on success, zero if the status moved
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.updatedAt = :now, s.version = s.version + 1 "
            + "WHERE s.id = :sessionId AND s.status = :required")
    int touchWhileInStatus(
            @Param("sessionId") UUID sessionId,
            @Param("required") SessionStatus required,
            @Param("now") OffsetDateTime now);

    /**
     * Moves the session from one status to another, but only from the expected one.
     *
     * @param sessionId the session to advance
     * @param required  the status the caller observed
     * @param target    the status to move to
     * @param now       the new modification timestamp
     * @return the number of rows changed: one on success, zero if the status moved
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.status = :target, s.updatedAt = :now, s.version = s.version + 1 "
            + "WHERE s.id = :sessionId AND s.status = :required")
    int advanceStatus(
            @Param("sessionId") UUID sessionId,
            @Param("required") SessionStatus required,
            @Param("target") SessionStatus target,
            @Param("now") OffsetDateTime now);

    /**
     * Claims the right to deal, by writing the opening leader's seat where none is set.
     *
     * <p>This is the deal-once gate for a session that has never been dealt. The first
     * caller to set the column wins and every later one changes no rows. Doing it here
     * rather than by counting hand rows is deliberate: the update takes the row lock
     * on the session before any hand row is written, which is both the lock order the
     * design requires and what serialises two simultaneous deals. Counting hands first
     * would only narrow the window.
     *
     * <p><strong>A null column no longer means only "not yet dealt".</strong> Since EOP-14
     * Slice E it also means "the hand is played out and no seat leads", written by
     * {@link #advanceLeaderSeat} when the last trick of a hand is resolved. So this
     * statement will change a row for a session whose cards have all been played, and the
     * predicate here is no longer the whole of the deal-once gate. What refuses the second
     * deal in that case is {@code uq_hand_session_seat}: the hand rows are still there, the
     * insert that follows this claim inside the same transaction violates the unique key,
     * and {@code TrickPlayRepositoryAdapter.dealFailure} turns that into
     * {@code HandAlreadyDealtException} — the same 409 the caller would have got before, from
     * a different check, with this claim rolled back with the rest of the transaction. The
     * concurrency argument above is untouched, because the column is genuinely null at the
     * start of a hand and the row lock is taken either way. Only a session that has already
     * finished a hand reaches the second gate, and there is no legitimate second deal: a game
     * of Elevation of Privilege is one hand, dealt whole, played out, then scored.
     *
     * @param sessionId  the session to deal into
     * @param leaderSeat the seat that leads the opening trick
     * @param required   the status the caller observed
     * @param now        the new modification timestamp
     * @return the number of rows changed: one on success, zero if the status moved or a
     *     hand is in progress
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.currentLeaderSeat = :leaderSeat, s.updatedAt = :now, "
            + "s.version = s.version + 1 "
            + "WHERE s.id = :sessionId AND s.status = :required AND s.currentLeaderSeat IS NULL")
    int claimDeal(
            @Param("sessionId") UUID sessionId,
            @Param("leaderSeat") int leaderSeat,
            @Param("required") SessionStatus required,
            @Param("now") OffsetDateTime now);

    /**
     * Touches the session, but only while the leader's seat is the one the caller saw.
     *
     * <p>Used before writing a play. The leader seat is not changed here, because it
     * advances once per trick rather than once per play: whose turn it is within a trick
     * is derived from the plays already in it. What this statement is for is the row lock
     * and the snapshot check. Zero rows means the trick moved on between the caller's read
     * and its write, and the caller's whole view of the table is stale.
     *
     * @param sessionId  the session to touch
     * @param expectedLeaderSeat the leader seat the caller observed
     * @param required   the status the caller observed
     * @param now        the new modification timestamp
     * @return the number of rows changed: one on success, zero if the status or the
     *     leader seat moved
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.updatedAt = :now, s.version = s.version + 1 "
            + "WHERE s.id = :sessionId AND s.status = :required AND s.currentLeaderSeat = :expectedLeaderSeat")
    int touchWhileLeaderSeatIs(
            @Param("sessionId") UUID sessionId,
            @Param("expectedLeaderSeat") int expectedLeaderSeat,
            @Param("required") SessionStatus required,
            @Param("now") OffsetDateTime now);

    /**
     * Moves the leader's seat on to the next trick's leader, but only from the expected one.
     *
     * <p>This is where the leader seat actually changes. It is <strong>not</strong> what stops a
     * trick being resolved twice, though an earlier version of this comment claimed it was. The
     * claim held only while the lead always moved. When the seat that led a trick also wins it
     * the caller passes a next leader equal to the expected one, this statement sets the column
     * to the value it just compared against, and the write is idempotent — so a replayed
     * resolution matches the row, changes one row, and sails straight through the guard that was
     * supposed to stop it. Trick resolution is therefore serialised by
     * {@code TrickJpaRepository.recordWinner}'s {@code winner_play_id IS NULL} predicate on the
     * trick row, not by this statement on the session row, and that predicate raises
     * {@code TrickAlreadyResolvedException} and so a 409. See ADR-020, "The deal-once gate" and
     * the replay note beside it. This statement's real job is the leader seat and the row lock it
     * takes while doing it.
     *
     * <p>{@code nextLeaderSeat} is a boxed {@link Integer} and null is a meaningful value: it
     * records that no seat leads, which is the state after the last trick of a hand, when every
     * seat is out of cards. Writing some seat's number there instead would have the row assert
     * that a seat may lead when it holds nothing to lead with. Note that this makes null the
     * column's second meaning, alongside "not yet dealt" — see {@link #claimDeal} for what still
     * refuses a second deal once it is written.
     *
     * @param sessionId          the session to advance
     * @param expectedLeaderSeat the leader seat the caller observed
     * @param nextLeaderSeat     the seat that leads the next trick, or null when the hand is
     *     played out and no seat leads
     * @param required           the status the caller observed
     * @param now                the new modification timestamp
     * @return the number of rows changed: one on success, zero if the status or the
     *     leader seat moved
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.currentLeaderSeat = :nextLeaderSeat, s.updatedAt = :now, "
            + "s.version = s.version + 1 "
            + "WHERE s.id = :sessionId AND s.status = :required AND s.currentLeaderSeat = :expectedLeaderSeat")
    int advanceLeaderSeat(
            @Param("sessionId") UUID sessionId,
            @Param("expectedLeaderSeat") int expectedLeaderSeat,
            @Param("nextLeaderSeat") Integer nextLeaderSeat,
            @Param("required") SessionStatus required,
            @Param("now") OffsetDateTime now);

    /**
     * Returns the identifiers of all sessions whose {@code expires_at} is before
     * the given instant and whose status is not yet {@code ABANDONED}.
     *
     * <p>Used by the sweep scheduler to discover which sessions to transition and
     * delete. Returning only identifiers keeps the query lightweight.
     *
     * @param before the cutoff — sessions expiring before this are returned
     * @return a list of session identifiers, possibly empty
     */
    @Query("SELECT s.id FROM GameSessionJpaEntity s WHERE s.expiresAt < :before AND s.status <> 'ABANDONED'")
    List<UUID> findExpiredSessionIds(@Param("before") OffsetDateTime before);

    /**
     * Unconditionally sets the session's status to {@code ABANDONED}.
     *
     * <p>The sweep calls this before deleting the row. A concurrent sweep call
     * that already set the status is a no-op here, and the delete that follows
     * still runs safely.
     *
     * @param sessionId the session to mark abandoned
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.status = 'ABANDONED', s.version = s.version + 1 "
            + "WHERE s.id = :sessionId")
    void markAbandoned(@Param("sessionId") UUID sessionId);

    /**
     * Moves a completed session back to {@code IN_PROGRESS} and clears the leader seat.
     *
     * <p>Called by {@link SessionRepositoryAdapter#resetToInProgress} as part of the
     * new-game reset. The compare-and-swap on {@code COMPLETED} means only one concurrent
     * new-game request succeeds; the others find zero rows changed.
     *
     * <p>The leader seat is set to {@code null} so that the next deal can write a fresh
     * opening lead via {@link #claimDeal}.
     *
     * @param sessionId the session to reset
     * @param now       the new modification timestamp
     * @return the number of rows changed: one on success, zero if the session was not completed
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.status = 'IN_PROGRESS', s.currentLeaderSeat = NULL, "
            + "s.updatedAt = :now, s.version = s.version + 1 "
            + "WHERE s.id = :sessionId AND s.status = 'COMPLETED'")
    int resetToInProgress(@Param("sessionId") UUID sessionId, @Param("now") OffsetDateTime now);
}
