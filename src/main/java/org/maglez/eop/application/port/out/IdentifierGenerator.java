package org.maglez.eop.application.port.out;

import java.util.UUID;

/**
 * Port that supplies primary key values for rows this application inserts at runtime.
 *
 * <p>Declared here rather than left to the persistence framework so that an
 * aggregate is fully formed before it is handed to a repository. A generator
 * attached to the JPA identifier would assign the value at flush time, which
 * would force a transient state in which the domain object exists without an
 * identity — and the domain types reject that, deliberately.
 *
 * <p>The implementation produces UUID version 7 values, which are ordered by
 * creation time. See ADR-018 for why version 7 rather than version 4, and for
 * the honest assessment that the ordering benefit is about readability of a
 * database dump rather than about performance at this scale.
 */
public interface IdentifierGenerator {

    /**
     * Produces the next identifier.
     *
     * <p>Successive calls must return increasing values when ordered as
     * unsigned bytes, including within the same millisecond.
     *
     * @return a fresh identifier, never {@code null} and never repeated
     */
    UUID nextIdentifier();
}
