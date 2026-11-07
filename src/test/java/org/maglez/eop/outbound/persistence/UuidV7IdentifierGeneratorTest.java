package org.maglez.eop.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the two properties of a version 7 identifier that the schema depends on.
 *
 * <p>Named by a comment in {@link HibernateUuidV7IdentifierGenerator}: the
 * generator passes {@code null} where Hibernate's strategy expects a session,
 * which is safe but undocumented. A Hibernate upgrade that changed the strategy
 * would still hand back perfectly valid identifiers, so nothing would fail —
 * primary keys would simply stop being time-ordered and the index locality that
 * motivated the choice would quietly disappear (ADR-018). These assertions turn
 * that into a build failure.
 */
@DisplayName("HibernateUuidV7IdentifierGenerator")
class UuidV7IdentifierGeneratorTest {

    /** Enough draws to catch a counter that only increments per millisecond. */
    private static final int DRAWS = 2_000;

    /** The version nibble RFC 9562 reserves for time-ordered identifiers. */
    private static final int VERSION_7 = 7;

    /** The variant RFC 9562 requires of every UUID this application mints. */
    private static final int VARIANT_RFC_4122 = 2;

    private final HibernateUuidV7IdentifierGenerator generator = new HibernateUuidV7IdentifierGenerator();

    @Test
    @DisplayName("every identifier is version 7, not the version 4 a changed strategy would return")
    void shouldIssueVersion7Identifiers() {
        for (int draw = 0; draw < DRAWS; draw++) {
            final UUID identifier = generator.nextIdentifier();

            assertThat(identifier.version())
                    .as("version of %s", identifier)
                    .isEqualTo(VERSION_7);
            assertThat(identifier.variant())
                    .as("variant of %s", identifier)
                    .isEqualTo(VARIANT_RFC_4122);
        }
    }

    @Test
    @DisplayName("identifiers drawn in one tight loop still sort into the order they were drawn")
    void shouldIssueMonotonicIdentifiers() {
        final List<UUID> drawn = new ArrayList<>(DRAWS);
        for (int draw = 0; draw < DRAWS; draw++) {
            drawn.add(generator.nextIdentifier());
        }

        final List<UUID> sorted = new ArrayList<>(drawn);
        sorted.sort(UuidV7IdentifierGeneratorTest::compareUnsigned);

        assertThat(sorted)
                .as("a loop this tight lands many draws in one millisecond, so this fails "
                        + "unless the sub-millisecond counter is present")
                .isEqualTo(drawn);
    }

    @Test
    @DisplayName("no identifier repeats, so a primary key collision is not merely unlikely but absent here")
    void shouldIssueDistinctIdentifiers() {
        final Set<UUID> distinct = new HashSet<>();
        for (int draw = 0; draw < DRAWS; draw++) {
            distinct.add(generator.nextIdentifier());
        }

        assertThat(distinct).hasSize(DRAWS);
    }

    /**
     * Orders two identifiers the way a database index does.
     *
     * <p>{@link UUID#compareTo} signs both halves, so it places any identifier
     * whose leading bit is set before one whose is not. Time ordering is a claim
     * about the unsigned byte order, which is what an index on a {@code uuid}
     * column uses.
     */
    private static int compareUnsigned(final UUID left, final UUID right) {
        final int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
