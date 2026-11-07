package org.maglez.eop.outbound.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the card table.
 *
 * <p>Package private, and not the application's port. {@link CardRepositoryAdapter}
 * wraps it and implements the port declared in the use case layer, so nothing
 * above this package can reach a Spring Data type or a persistence entity.
 *
 * <p>No write methods are exposed. {@code JpaRepository} inherits some, which is
 * the price of the base interface; the deck is only ever written by migration.
 */
interface CardJpaRepository extends JpaRepository<CardJpaEntity, UUID> {
}
