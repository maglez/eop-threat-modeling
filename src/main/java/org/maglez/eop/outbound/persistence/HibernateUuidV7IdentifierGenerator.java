package org.maglez.eop.outbound.persistence;

import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.maglez.eop.application.port.out.IdentifierGenerator;
import org.springframework.stereotype.Component;

/**
 * Issues version 7 UUIDs for rows this application inserts at runtime.
 *
 * <p>Delegates to Hibernate's own implementation rather than assembling the bits
 * here. The interesting part of RFC 9562 §6.2 is not the timestamp, which is easy,
 * but the sub-millisecond counter that keeps two identifiers drawn in the same
 * millisecond in the order they were drawn. A hand-rolled version that omits it
 * still produces valid identifiers, which is precisely why the omission would go
 * unnoticed (ADR-018).
 *
 * <p>Generation is application-side because it cannot be database-side: PostgreSQL
 * grew a native {@code uuidv7()} in version 18 and the deployed image is 17, H2 has
 * no equivalent at all, and tests run on H2 while production runs on PostgreSQL. A
 * column default would therefore have to be written twice and verified once.
 */
@Component
public class HibernateUuidV7IdentifierGenerator implements IdentifierGenerator {

    /**
     * Creates the generator Spring registers as the sole {@link IdentifierGenerator} bean.
     *
     * <p>Written out only so that it can be documented. The generator holds no state — every
     * identifier is drawn from {@link UuidVersion7Strategy#INSTANCE}, which owns the
     * sub-millisecond counter, so a single instance is safe to share across request threads.
     */
    public HibernateUuidV7IdentifierGenerator() {
        // No state: monotonicity is maintained inside Hibernate's shared strategy.
    }

    @Override
    public UUID nextIdentifier() {
        // The version 7 strategy ignores the session argument. Passing null is
        // safe but undocumented, so UuidV7IdentifierGeneratorTest asserts both the
        // version nibble and monotonicity: a Hibernate upgrade that changed this
        // should fail the build rather than quietly return version 4 identifiers.
        return UuidVersion7Strategy.INSTANCE.generateUuid(null);
    }
}
