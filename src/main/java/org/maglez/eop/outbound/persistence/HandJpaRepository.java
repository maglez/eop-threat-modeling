package org.maglez.eop.outbound.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the hand table.
 *
 * <p>Package private, and not the application's port. Hands are always read for a
 * whole session at once, and that is a correctness requirement rather than a
 * convenience: {@code Hands.reconstitute} re-runs every cross-seat invariant it was
 * given, including the rule that one card cannot sit in two hands, and that rule has
 * no database constraint behind it. {@code pk_hand_card} stops a card appearing twice
 * in one hand and {@code uq_trick_play_trick_card} stops it being played twice into
 * one trick, but nothing scopes a card to a single hand within a session. Reading one
 * seat at a time would satisfy the invariant vacuously and thereby switch off the only
 * enforcement it has.
 *
 * <p>Reading in seat order matters for the same reason it matters on the player table:
 * the order seats are read in is the order play moves around the table, and sorting
 * later would put the sort in a second place.
 *
 * <p>There is no method that finds a hand by player. A player's hand is reached through
 * the session's hands, so that a hand is never read without the table it belongs to.
 */
interface HandJpaRepository extends JpaRepository<HandJpaEntity, UUID> {

    /**
     * Reads every hand dealt in a session, in the order play moves around the table.
     *
     * @param gameSessionId the session whose hands to read
     * @return the dealt hands, ascending by seat order, empty if nothing has been dealt
     */
    List<HandJpaEntity> findByGameSessionIdOrderBySeatOrderAsc(UUID gameSessionId);

    /**
     * Answers whether a session has ever been dealt.
     *
     * <p>This is not a shortcut for reading the hands, and nothing that needs the cards may
     * use it — the paragraph above about vacuous invariants still stands. It exists for one
     * refusal: {@code current_leader_seat} being null means either that no hand has been
     * dealt or that a hand has been dealt and played out, and only the presence of hand rows
     * tells those apart. A caller that has to choose between
     * {@code HandNotDealtException} and {@code HandCompleteException} is asking a question
     * about existence, not about content, and this answers exactly that.
     *
     * @param gameSessionId the session to check
     * @return true when at least one hand row exists for the session
     */
    boolean existsByGameSessionId(UUID gameSessionId);

    /**
     * Deletes all hand-card rows for a session's hands, then all hand rows.
     *
     * <p>Called by {@link TrickPlayRepositoryAdapter#clearForNewGame} as part of the
     * new-game reset. Hand-cards must be deleted before hands (FK constraint).
     * This method deletes only the hand rows; the caller is responsible for deleting
     * hand-cards first via {@link HandCardJpaRepository}.
     *
     * @param gameSessionId the session whose hands to delete
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM HandJpaEntity h WHERE h.gameSessionId = :gameSessionId")
    void deleteByGameSessionId(@Param("gameSessionId") UUID gameSessionId);
}
