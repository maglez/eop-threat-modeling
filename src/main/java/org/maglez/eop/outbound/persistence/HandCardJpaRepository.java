package org.maglez.eop.outbound.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the cards sitting in hands.
 *
 * <p>Package private, and not the application's port. Cards are read for every hand in
 * a session in one query rather than one query per hand, because the caller needs all of
 * them before it can reconstitute anything and a query per seat would make the cost of
 * reading a table grow with the number of players at it.
 *
 * <p>Removing a card is expressed as a conditional delete that reports how many rows it
 * changed, not as a derived {@code deleteByHandIdAndCardId}. A derived delete loads the
 * row and then deletes it, which is a read followed by a write with a window in between;
 * a single {@code DELETE} guarded by both key columns closes the window and answers the
 * only question the caller has. Zero rows changed is not a failure of the statement — it
 * is the answer that the hand did not hold the card, and the adapter turns it into a
 * domain exception. That is the same protocol the session repository uses for its status
 * transitions, and it is what makes the delete the storage backstop for playing a card
 * you do not hold.
 */
interface HandCardJpaRepository extends JpaRepository<HandCardJpaEntity, HandCardJpaEntity.Key> {

    /**
     * Reads the cards held by a set of hands.
     *
     * @param handIds the hands whose cards to read
     * @return the card rows for those hands, in no guaranteed order
     */
    List<HandCardJpaEntity> findByHandIdIn(Collection<UUID> handIds);

    /**
     * Removes one card from one hand, if that hand holds it.
     *
     * @param handId the hand to remove the card from
     * @param cardId the card to remove
     * @return {@code 1} if the hand held the card, {@code 0} if it did not
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM HandCardJpaEntity c WHERE c.handId = :handId AND c.cardId = :cardId")
    int removeCardFromHand(@Param("handId") UUID handId, @Param("cardId") UUID cardId);

    /**
     * Deletes all hand-card rows for a set of hands.
     *
     * <p>Called by {@link TrickPlayRepositoryAdapter#clearForNewGame} before deleting
     * hands (FK order: hand-cards → hands).
     *
     * @param handIds the hands whose cards to delete
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM HandCardJpaEntity c WHERE c.handId IN :handIds")
    void deleteByHandIdIn(@Param("handIds") Collection<UUID> handIds);
}
