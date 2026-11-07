package org.maglez.eop.outbound.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the plays made into a trick.
 *
 * <p>Package private, and not the application's port. The read deliberately imposes no
 * ordering. Play order within a trick is rotation order starting from the trick's leading
 * seat, and that is a fact the trick row carries, not something the database can express
 * in an {@code ORDER BY} over this table alone.
 *
 * <p>Ordering by {@code played_at} would look correct and be wrong. Two plays a
 * millisecond apart can share a timestamp at the column's precision, and the tie would be
 * broken arbitrarily — which matters because the first play in the list is the one the
 * domain reads the led suit from. Rotation order is exact, deterministic and derived from
 * the same rule the domain used to accept the plays, so the adapter sorts on it and this
 * interface stays silent about order rather than offering a second, plausible answer.
 */
interface TrickPlayJpaRepository extends JpaRepository<TrickPlayJpaEntity, UUID> {

    /**
     * Reads every play made into a trick.
     *
     * @param trickId the trick whose plays to read
     * @return the plays, in no guaranteed order, empty if nobody has played yet
     */
    List<TrickPlayJpaEntity> findByTrickId(UUID trickId);

    /**
     * Reads the plays of several tricks at once, in no guaranteed order.
     *
     * <p>Silent about order for the same reason the single-trick read is: play order within a trick
     * is rotation from that trick's leader seat, which is a fact the trick row carries and no
     * {@code ORDER BY} over this table can express. The caller groups by trick and applies the
     * rotation itself.
     *
     * @param trickIds identifiers of the tricks whose plays are wanted
     * @return every play belonging to any of those tricks, in no guaranteed order
     */
    List<TrickPlayJpaEntity> findByTrickIdIn(List<UUID> trickIds);

    /**
     * Reads the identifiers of all plays in a set of tricks.
     *
     * <p>Used by {@link TrickPlayRepositoryAdapter#clearForNewGame} to delete components
     * before deleting plays (FK order).
     *
     * @param trickIds the tricks whose play IDs to read
     * @return the play identifiers, in no guaranteed order
     */
    @Query("SELECT p.id FROM TrickPlayJpaEntity p WHERE p.trickId IN :trickIds")
    List<UUID> findIdsByTrickIdIn(@Param("trickIds") List<UUID> trickIds);

    /**
     * Deletes all plays belonging to a set of tricks.
     *
     * <p>Called after all components for those plays have been deleted.
     *
     * @param trickIds the tricks whose plays to delete
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TrickPlayJpaEntity p WHERE p.trickId IN :trickIds")
    void deleteByTrickIdIn(@Param("trickIds") List<UUID> trickIds);
}
