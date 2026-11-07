package org.maglez.eop.outbound.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the trick table.
 *
 * <p>Package private, and not the application's port. There is one read, and it asks for
 * the highest-numbered trick in a session rather than for an unresolved one. Whether a
 * trick is finished is a question the trick answers about itself once it has been
 * reconstituted, and a query that filtered on {@code winner_play_id IS NULL} would be a
 * second authority on the same fact — one that would silently disagree the moment the
 * domain's idea of complete changed.
 *
 * <p>Recording a winner is a conditional update rather than a save, for the same reason
 * the session's status transitions are. Writing the winner only where none is recorded
 * yet makes a second resolution of the same trick change no rows instead of overwriting
 * the first, and the row count is the whole protocol.
 */
interface TrickJpaRepository extends JpaRepository<TrickJpaEntity, UUID> {

    /**
     * Reads the most recent trick in a session, resolved or not.
     *
     * @param gameSessionId the session whose current trick to read
     * @return the highest-numbered trick, or empty if no trick has been opened
     */
    Optional<TrickJpaEntity> findFirstByGameSessionIdOrderBySequenceDesc(UUID gameSessionId);

    /**
     * Reads every trick of a session, oldest first.
     *
     * <p>Ordered by sequence rather than by any timestamp, because sequence is the order of play and
     * is unique within a session. No predicate on {@code winner_play_id}: a trick still on the table
     * belongs in this answer, and filtering here would make the query a second authority on whether
     * a trick is finished.
     *
     * @param gameSessionId identifier of the session
     * @return the session's trick rows in ascending sequence order, empty when it has none
     */
    List<TrickJpaEntity> findByGameSessionIdOrderBySequenceAsc(UUID gameSessionId);

    /**
     * Records the winning play of a trick, if that trick has no winner yet.
     *
     * @param trickId the trick to resolve
     * @param winnerPlayId the play that took the trick
     * @return {@code 1} if the winner was recorded, {@code 0} if the trick was already
     *     resolved <em>or no longer exists</em> — the predicate cannot tell those apart, and
     *     the caller answers both with a 409. That conflation is only safe while nothing
     *     deletes a single trick row; see the class comment on
     *     {@code TrickPlayRepositoryAdapter}
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TrickJpaEntity t SET t.winnerPlayId = :winnerPlayId "
            + "WHERE t.id = :trickId AND t.winnerPlayId IS NULL")
    int recordWinner(@Param("trickId") UUID trickId, @Param("winnerPlayId") UUID winnerPlayId);

    /**
     * Reads the identifiers of all tricks in a session.
     *
     * <p>Used by {@link TrickPlayRepositoryAdapter#clearForNewGame} to delete plays
     * before deleting tricks (FK order).
     *
     * @param gameSessionId the session whose trick IDs to read
     * @return the trick identifiers, in no guaranteed order
     */
    @Query("SELECT t.id FROM TrickJpaEntity t WHERE t.gameSessionId = :gameSessionId")
    List<UUID> findIdsByGameSessionId(@Param("gameSessionId") UUID gameSessionId);

    /**
     * Deletes all trick rows for a session.
     *
     * <p>Called after all plays for those tricks have been deleted.
     *
     * @param gameSessionId the session whose tricks to delete
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TrickJpaEntity t WHERE t.gameSessionId = :gameSessionId")
    void deleteByGameSessionId(@Param("gameSessionId") UUID gameSessionId);
}
