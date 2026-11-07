package org.maglez.eop.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.TrickPlay;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the CHECK constraint in 004-trick-play-schema.xml to {@link TrickPlay#MAX_COMPONENTS}.
 *
 * <p>Changeset 006 hard-codes {@code CHECK (ordinal >= 0 AND ordinal <= 19)}.
 * Liquibase cannot read a Java constant, so if {@code MAX_COMPONENTS} is ever raised
 * the storage constraint becomes stricter than the domain: a legal play with ordinal 20
 * would be rejected by the database with a 500, not by the domain with a 400.
 *
 * <p>ADR-023 explicitly requires this test. The approach — reading the changeset XML
 * off the classpath and extracting the bounds — is the same pattern used by
 * {@code AdrIndexConsistencyTest}: a resource file is read from a test to hold docs
 * and code together without touching production sources.
 *
 * <p>Two properties of the match are load-bearing, and both were defects in the first
 * version of this test.
 *
 * <p>First, <strong>both</strong> bounds are pinned. Pinning only {@code <= 19} left
 * {@code >= 0} guarded by nothing: deleting that conjunct would widen the ordinal domain
 * to every negative int, which destroys the pigeonhole argument that caps a play at 20
 * component rows, while every test stayed green.
 *
 * <p>Second, the pattern is anchored to the whole {@code ADD CONSTRAINT ... CHECK (...)}
 * statement and the test asserts it appears <strong>exactly once</strong> in the file.
 * A bare {@code ordinal <= (\d+)} pattern taking the first match was demonstrably
 * defeatable: an explanatory comment mentioning the old bound above the {@code <sql>}
 * block became the pinned value, so the test reported success while the deployed
 * constraint said something else. Prose cannot satisfy this pattern without reproducing
 * the entire DDL statement verbatim, and if it does the match count catches it.
 *
 * <p>No Spring context. No I/O beyond a single classpath resource read.
 */
@DisplayName("004 CHECK constraint is pinned to TrickPlay.MAX_COMPONENTS")
class TrickPlayComponentOrdinalConstraintTest {

    /**
     * Path to the changeset file on the classpath.
     * The master changelog uses {@code relativeToChangelogFile="true"}, so the file
     * is at {@code db/changelog/changes/004-trick-play-schema.xml} on the classpath.
     */
    private static final String CHANGESET_CLASSPATH =
            "db/changelog/changes/004-trick-play-schema.xml";

    /** The lower bound the changeset must declare. Ordinals are 0-based list positions. */
    private static final int EXPECTED_LOWER_BOUND = 0;

    /**
     * Matches the entire CHECK constraint statement in the raw SQL block of changeset 006,
     * capturing both bounds.
     *
     * <p>Anchoring on {@code ALTER TABLE ... ADD CONSTRAINT <name> CHECK} rather than on
     * {@code ordinal <= N} alone is what makes this test undefeatable by a comment: the
     * whole statement, including the constraint name, has to be present.
     *
     * <p>{@code <} is written {@code &lt;} in the XML source because it would otherwise open
     * an element, so both forms are accepted. {@code >} needs no escaping and appears
     * literally. {@code DOTALL} lets the statement span the lines it is formatted across.
     */
    private static final Pattern CHECK_CONSTRAINT = Pattern.compile(
            "ALTER\\s+TABLE\\s+trick_play_component\\s+"
                    + "ADD\\s+CONSTRAINT\\s+chk_trick_play_component_ordinal\\s+"
                    + "CHECK\\s*\\(\\s*"
                    + "ordinal\\s*(?:&gt;|>)=\\s*(\\d+)"
                    + "\\s+AND\\s+"
                    + "ordinal\\s*(?:&lt;|<)=\\s*(\\d+)"
                    + "\\s*\\)",
            Pattern.DOTALL);

    @Test
    @DisplayName("CHECK (ordinal >= 0 AND ordinal <= N) is declared exactly once, and N is MAX_COMPONENTS - 1")
    void checkConstraintBoundsMatchDomainConstant() throws IOException {
        // Arrange — read the changeset XML as a raw string from the classpath
        final String changesetXml = readClasspathResource(CHANGESET_CLASSPATH);

        // Act — collect every occurrence of the full constraint statement
        final List<int[]> bounds = new ArrayList<>();
        final Matcher matcher = CHECK_CONSTRAINT.matcher(changesetXml);
        while (matcher.find()) {
            bounds.add(new int[] {
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
            });
        }

        // Assert — exactly one statement, so no comment or second changeset can shadow it
        assertThat(bounds)
                .as(
                        "004-trick-play-schema.xml must declare "
                                + "'ALTER TABLE trick_play_component ADD CONSTRAINT "
                                + "chk_trick_play_component_ordinal CHECK (ordinal >= L AND ordinal <= U)' "
                                + "exactly once. Zero matches means the constraint was removed or reworded; "
                                + "more than one means something else in the file now shadows the real one, "
                                + "and this test can no longer tell you which bound is deployed.")
                .hasSize(1);

        final int lowerBound = bounds.get(0)[0];
        final int upperBound = bounds.get(0)[1];

        // Assert — the lower bound keeps the ordinal domain finite, which is what caps the row count
        assertThat(lowerBound)
                .as(
                        "CHECK constraint lower bound (%d) must be %d. Without it the ordinal domain is "
                                + "every negative int, and the pigeonhole argument that caps a play at "
                                + "TrickPlay.MAX_COMPONENTS (%d) component rows no longer holds.",
                        lowerBound, EXPECTED_LOWER_BOUND, TrickPlay.MAX_COMPONENTS)
                .isEqualTo(EXPECTED_LOWER_BOUND);

        // Assert — the upper bound must equal MAX_COMPONENTS - 1
        // (ordinals are 0-based, so 20 components occupy ordinals 0..19)
        assertThat(upperBound)
                .as(
                        "CHECK constraint upper bound (%d) must equal TrickPlay.MAX_COMPONENTS - 1 (%d). "
                                + "If MAX_COMPONENTS was raised, update the CHECK in changeset 006 of "
                                + "004-trick-play-schema.xml to match, or storage will reject legal plays.",
                        upperBound, TrickPlay.MAX_COMPONENTS - 1)
                .isEqualTo(TrickPlay.MAX_COMPONENTS - 1);
    }

    /**
     * Reads a classpath resource as a UTF-8 string.
     *
     * @param path the classpath-relative path
     * @return the full content of the resource
     * @throws IOException if the resource cannot be read
     * @throws AssertionError if the resource is not found on the classpath
     */
    private static String readClasspathResource(final String path) throws IOException {
        final ClassLoader loader = TrickPlayComponentOrdinalConstraintTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            assertThat(stream)
                    .as("classpath resource '%s' must exist — is the test running from the project root?", path)
                    .isNotNull();
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
