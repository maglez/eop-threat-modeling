package org.maglez.eop.outbound.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link GameResultPlayerJpaEntity}.
 */
interface GameResultPlayerJpaRepository extends JpaRepository<GameResultPlayerJpaEntity, UUID> {

    /**
     * Finds all player rows for a given result, ordered by seat for deterministic ranking.
     *
     * @param gameResultId the result identifier
     * @return the player rows in ascending seat order
     */
    List<GameResultPlayerJpaEntity> findByGameResultIdOrderBySeatOrderAsc(UUID gameResultId);
}
