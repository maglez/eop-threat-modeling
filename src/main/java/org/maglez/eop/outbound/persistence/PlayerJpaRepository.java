package org.maglez.eop.outbound.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the player table.
 *
 * <p>Package private, and not the application's port. Seats are always read for a
 * whole session at once and always in seat order, because the order is the order of
 * play: reading them unordered and sorting later would put the sort in more than
 * one place, and one of those places would eventually be wrong.
 *
 * <p>There is no method that finds a player by credential digest. Resolving a
 * credential is the aggregate's job — the session is read first, then asked — so
 * that identity is never established from a row without the session it belongs to.
 */
interface PlayerJpaRepository extends JpaRepository<PlayerJpaEntity, UUID> {

    /**
     * Reads every seat at a table, in the order play moves around it.
     *
     * @param gameSessionId the session whose seats to read
     * @return the seated players, ascending by seat order, empty if the session is unknown
     */
    List<PlayerJpaEntity> findByGameSessionIdOrderBySeatOrderAsc(UUID gameSessionId);
}
