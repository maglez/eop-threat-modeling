package org.maglez.eop.outbound.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the components named on a play.
 *
 * <p>Package private, and not the application's port. Components are read for every play
 * in a trick at once, because reading them one play at a time would make the cost of
 * reading a trick grow with the number of players in it for no benefit — the caller needs
 * all of them before it can reconstitute any play.
 *
 * <p>The ordering is explicit and load-bearing. A play's components are a list, not a set:
 * the same component may be named twice, deliberately, so the schema leaves component
 * names non-unique and makes {@code ordinal} part of the primary key. The ordinal is the
 * only thing that makes the list a list, so it is the only thing worth ordering by, and the
 * secondary ordering on the play identifier exists so that grouping the result is a single
 * pass rather than a sort the caller has to redo.
 */
interface TrickPlayComponentJpaRepository
        extends JpaRepository<TrickPlayComponentJpaEntity, TrickPlayComponentJpaEntity.Key> {

    /**
     * Reads the components named on a set of plays.
     *
     * @param trickPlayIds the plays whose components to read
     * @return the component rows, grouped by play and ascending by ordinal within each play
     */
    List<TrickPlayComponentJpaEntity> findByTrickPlayIdInOrderByTrickPlayIdAscOrdinalAsc(
            Collection<UUID> trickPlayIds);

    /**
     * Deletes all components belonging to a set of plays.
     *
     * <p>Called by {@link TrickPlayRepositoryAdapter#clearForNewGame} before deleting
     * plays (FK order: components → plays → tricks).
     *
     * @param trickPlayIds the plays whose components to delete
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TrickPlayComponentJpaEntity c WHERE c.trickPlayId IN :trickPlayIds")
    void deleteByTrickPlayIdIn(@Param("trickPlayIds") Collection<UUID> trickPlayIds);
}
