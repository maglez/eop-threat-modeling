package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.maglez.eop.outbound.persistence.TrickPlayRepositoryAdapter;

/**
 * Derives the set of non-constraint exception origins from the adapter and holds ADR-023 to it.
 *
 * <p>It exists because prose lost to arithmetic. The paragraph beside ADR-023's constraint-name
 * translation table states how many exceptions arise with no constraint violated, and it said two,
 * then three, then six before nine. Each wrong count was written carefully and reviewed carefully,
 * which is the argument for deriving the number instead of restating it. EOP-14 Slice E took the
 * count from nine to ten by naming the third state of {@code current_leader_seat}, and this class
 * is why that arrived as a failing build rather than as a paragraph nobody re-read.
 *
 * <p>How it derives. Every construction of a type the adapter imports from {@code
 * org.maglez.eop.domain} is classified by <em>where</em> it sits. Inside {@code dealFailure}, {@code
 * playFailure}, or any of the four {@code catch (final DataIntegrityViolationException ...)} blocks,
 * a constraint really was violated and a row of the table covers it. Inside {@code assertSeated},
 * the refusal came from a read. Anywhere else it came from a rows-affected count of zero. Both
 * groups are located by cutting regions out of the source rather than by subtracting one group's
 * names from the other's, because subtraction cannot tell a type that belongs to both from one that
 * belongs only to the group being subtracted.
 *
 * <p>Each block of the paragraph must then name exactly its own derived group, and the two groups
 * must be disjoint. Per-group equality does not imply disjointness: a type constructed both inside
 * {@code assertSeated} and at a rows-affected position satisfies both equalities while the headings
 * sum to more than the derived total, so that is asserted separately.
 *
 * <p>The one limitation, measured rather than assumed. Membership is a name appearing in a group
 * block, so if a block mentions a type twice — once enumerating it, once in narrative — deleting
 * only the enumerating mention leaves it detected. A block would have to keep discussing a type it
 * had stopped listing for this to hide anything, and the mutations validating this class therefore
 * remove every mention. Closing it would mean parsing prose intent, which this approach cannot do.
 *
 * <p>It reads the adapter as text because the distinction being derived is positional and reflection
 * cannot see it. A construction moved into a helper called <em>from</em> a translation would be
 * classified wrongly.
 *
 * <p>No account here of which assertion a given mutation trips, and no tally of attack shapes or
 * assertion counts. Earlier versions of this comment gave both, and each was left behind by the
 * next change to the code it described. The assertion messages are the record: they cannot go
 * stale, because they are what fires.
 */
@DisplayName("ADR-023's non-constraint exception origins, derived from the adapter")
class TrickPlayExceptionOriginTest {

    private static final Path ADR =
            Path.of("docs", "adr", "ADR-023-deal-remainder-and-turn-order.md");

    private static final Path ADAPTER =
            Path.of(
                    "src", "main", "java", "org", "maglez", "eop", "outbound", "persistence",
                    "TrickPlayRepositoryAdapter.java");

    /**
     * The count as it stands. Asserted against the derived set rather than trusted, so this
     * constant cannot drift from the code the way the prose did — if the adapter grows an eleventh
     * origin, the derivation returns eleven and this fails.
     */
    private static final int EXPECTED_ORIGIN_COUNT = 10;

    private static String adapterSource() throws IOException {
        return Files.readString(ADAPTER, StandardCharsets.UTF_8);
    }

    private static String adrSource() throws IOException {
        return Files.readString(ADR, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("derives exactly ten of them from the adapter source")
    void shouldDeriveTenOriginsFromTheAdapter() throws IOException {
        final Set<String> derived = nonConstraintOrigins(adapterSource());

        assertThat(derived)
                .as(
                        "the adapter's non-constraint refusal vocabulary, derived by position. If "
                                + "this is not ten the ADR is stale and so is the constant above")
                .hasSize(EXPECTED_ORIGIN_COUNT);
    }

    @Test
    @DisplayName("states the derived number, in words, in the paragraph that scopes the table")
    void shouldStateTheDerivedNumber() throws IOException {
        final int derived = nonConstraintOrigins(adapterSource()).size();
        final String paragraph = paragraphBesideTheTable(adrSource());

        assertThat(paragraph)
                .as(
                        "the paragraph must state the count the adapter actually produces; it has "
                                + "previously said two, three and six")
                .contains("**" + numberWord(derived) + "** exceptions on the trick-play write paths");
    }

    /**
     * The assertion every previous version should have made: each block's names equal <em>its own</em>
     * derived group.
     *
     * <p>The disjointness of the two groups is asserted separately. Per-group equality alone does
     * not imply it: a type constructed both inside {@code assertSeated} and at a rows-affected
     * position belongs to both derived groups, and if the prose lists it in both blocks then both
     * equalities hold and the headings sum to more than the derived total with nothing firing. A
     * review gate proved that green after the aggregate assertions were deleted, so the disjointness
     * check is reinstated rather than the gap disclosed — it was doing real work.
     *
     * <p>A review gate diagnosed the shape of the problem exactly: the paragraph asserts
     * a <em>mapping</em> from name to mechanism, and every check built for it verified
     * <em>aggregates</em> of that mapping. Aggregates over a mapping leave every permutation green by
     * construction, however many you add, which is why strengthening the total, then the padding
     * direction, then the derivation's positional honesty each closed a real hole and left the next
     * one open in the same place.
     *
     * <p>The gate proved it by moving {@code CardNotInHandException} from the rows-affected block into
     * the read block, leaving both headings untouched: the build stayed green. That is not an abstract
     * hole — it is the exact mis-filing the block's own text records as its own past mistake, so the
     * guard built to stop it recurring could not see it recurring.
     *
     * <p>Per-group equality catches an origin the adapter gains and the prose omits, a name the
     * prose invents, a name the prose deletes, and a name that moves between groups. A duplicated
     * heading, and a rewording that leaves no block matching, are caught by {@link #namedInBlock}'s
     * requirement of exactly one match. No total is given here on purpose — a count of attack shapes is one more
     * number to get wrong.
     */
    @Test
    @DisplayName("maps each origin to the mechanism that raises it, group by group")
    void shouldMapEachOriginToItsMechanism() throws IOException {
        final String adapter = adapterSource();
        final String paragraph = paragraphBesideTheTable(adrSource());

        assertThat(namedInBlock(paragraph, ROWS_BLOCK))
                .as(
                        "the rows-affected block must name exactly the origins raised from a "
                                + "rows-affected count of zero — no omission, no padding, and nothing "
                                + "that actually comes from the read")
                .isEqualTo(rowCountOrigins(adapter));

        assertThat(namedInBlock(paragraph, READ_BLOCK))
                .as(
                        "the read block must name exactly the origins raised from assertSeated. "
                                + "Moving a name between the two blocks was green until this "
                                + "assertion existed")
                .isEqualTo(seatReadOrigins(adapter));

        assertThat(rowCountOrigins(adapter))
                .as(
                        "the two groups must be disjoint. A type constructed both inside "
                                + "assertSeated and at a rows-affected position belongs to both, and "
                                + "both per-group equalities then hold while the headings sum to more "
                                + "than the derived total. A review gate proved that green, which is "
                                + "why this assertion is back rather than the gap being disclosed")
                .doesNotContainAnyElementsOf(seatReadOrigins(adapter));

        assertThat(paragraph)
                .as("the rows-affected heading must state its own group's size")
                .contains(numberWord(rowCountOrigins(adapter).size()) + " are raised from a rows-affected count of zero");
        assertThat(paragraph)
                .as("the read heading must state its own group's size")
                .contains(numberWord(seatReadOrigins(adapter).size()) + " are raised from a read this adapter makes itself");
    }

    /**
     * Every domain exception the adapter constructs outside a constraint translation. These are the
     * ones no row of ADR-023's table can cover, because no constraint was violated.
     */
    private static Set<String> nonConstraintOrigins(final String adapter) {
        final Set<String> all =
                domainExceptionsConstructedIn(adapter, wholeFileExcludingTranslations(adapter));
        assertThat(all)
                .as("the derivation must find something, or every assertion using it is vacuous")
                .isNotEmpty();
        return all;
    }

    /**
     * The refusals raised from the {@code assertSeated} read rather than from a row count. Located
     * by that method's own body, not by subtraction.
     */
    private static Set<String> seatReadOrigins(final String adapter) {
        final int[] seated = bodyOf(adapter, adapter.indexOf("private void assertSeated"));
        final Set<String> found = domainExceptionsConstructedIn(adapter, Set.of(seated));
        assertThat(found)
                .as("assertSeated must still be the source of the read-based refusals")
                .isNotEmpty();
        return found;
    }

    /**
     * The refusals raised from a rows-affected count of zero: constructions in the source that is
     * left after cutting out both the constraint translations and {@code assertSeated}'s body.
     *
     * <p>This is a <em>region</em> complement, not a name-level subtraction. An earlier version
     * computed {@link #nonConstraintOrigins} and then {@code removeAll}-ed the names found in
     * {@code assertSeated}, which dropped a type from this group whenever it appeared in that
     * method even if it was <em>also</em> constructed at a rows-affected position. Cutting regions
     * rather than names is what makes the two groups independently derived.
     *
     * <p>Deliberately no account here of which assertion a given mutation trips. The
     * assertion messages are the record of what fails and where; they cannot go stale,
     * because they are the thing that fires.
     */
    private static Set<String> rowCountOrigins(final String adapter) {
        final Set<String> found =
                domainExceptionsConstructedIn(adapter, rowCountRegions(adapter));
        assertThat(found)
                .as("the rows-affected group must not be empty, or the derivation is inverted")
                .isNotEmpty();
        return found;
    }

    /** Everything that is neither a constraint translation nor the {@code assertSeated} read. */
    private static Set<int[]> rowCountRegions(final String adapter) {
        return excluding(
                wholeFileExcludingTranslations(adapter),
                bodyOf(adapter, adapter.indexOf("private void assertSeated")));
    }

    /**
     * The given ranges with {@code hole} cut out of each, splitting any range that straddles it.
     * Ranges are half-open, so a range merely <em>adjacent</em> to the hole is preserved intact — a
     * range whose start coincides with the hole's start is truncated, not preserved.
     */
    private static Set<int[]> excluding(final Set<int[]> ranges, final int[] hole) {
        final Set<int[]> kept = new LinkedHashSet<>();
        for (final int[] range : ranges) {
            if (hole[1] <= range[0] || hole[0] >= range[1]) {
                kept.add(range);
                continue;
            }
            if (range[0] < hole[0]) {
                kept.add(new int[] {range[0], hole[0]});
            }
            if (hole[1] < range[1]) {
                kept.add(new int[] {hole[1], range[1]});
            }
        }
        assertThat(kept)
                .as("cutting the read region must not consume the whole file")
                .isNotEmpty();
        return kept;
    }

    /** Invariant half of the lead-in of the block enumerating the rows-affected group. */
    private static final String ROWS_BLOCK = "are raised from a rows-affected count of zero";

    /** Invariant half of the lead-in of the block enumerating the read group. */
    private static final String READ_BLOCK = "are raised from a read this adapter makes itself";

    /**
     * The exception types named in the one block whose lead-in contains {@code leadIn}.
     *
     * <p>Scoped to a single block, which is what makes the mapping checkable. An earlier version
     * unioned the tokens of <em>both</em> group blocks and so could never tell which mechanism the
     * prose assigned a name to — the hole a review gate walked through by migrating a name between
     * them. Reading one block at a time is also what keeps the universe sentence out of the way: it
     * backticks {@code IllegalStateException} and {@code IllegalArgumentException} precisely in order
     * to exclude them, and a whole-paragraph sweep flagged those two as padding.
     */
    private static Set<String> namedInBlock(final String paragraph, final String leadIn) {
        final Set<String> found = new TreeSet<>();
        int blocks = 0;
        for (final String block : paragraph.split("\\n\\s*\\n")) {
            if (!headlineOf(block).contains(leadIn)) {
                continue;
            }
            blocks++;
            final Matcher matcher = Pattern.compile("`(\\w+Exception)`").matcher(block);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
        assertThat(blocks)
                .as(
                        "exactly one block's lead-in must contain '%s'. Zero means the heading was "
                                + "reworded and this check would go vacuous; more than one means it was "
                                + "duplicated",
                        leadIn)
                .isEqualTo(1);
        assertThat(found)
                .as("the block whose lead-in contains '%s' must name its members in backticks", leadIn)
                .isNotEmpty();
        return found;
    }

    /**
     * The byte ranges of every place a {@code DataIntegrityViolationException} is turned into a
     * domain exception, or handed to something that does: the two failure helpers, and all four
     * catch blocks — two in {@code recordDeal} and one in {@code appendPlay} that delegate, and
     * one inline in {@code openTrick} that translates in place.
     */
    private static Set<int[]> translationRegions(final String adapter) {
        final Set<int[]> regions = new LinkedHashSet<>();
        regions.add(bodyOf(adapter, adapter.indexOf("private RuntimeException dealFailure")));
        regions.add(bodyOf(adapter, adapter.indexOf("private RuntimeException playFailure")));

        final String catchAnchor = "catch (final DataIntegrityViolationException";
        int catchAt = adapter.indexOf(catchAnchor);
        assertThat(catchAt)
                .as("the adapter must still catch the violation somewhere, or there is no "
                        + "translation to exclude and this whole derivation is meaningless")
                .isNotNegative();
        int caught = 0;
        while (catchAt >= 0) {
            regions.add(bodyOf(adapter, catchAt));
            caught++;
            catchAt = adapter.indexOf(catchAnchor, catchAt + catchAnchor.length());
        }
        assertThat(caught)
                .as("every catch of the violation is a translation site: two in recordDeal "
                        + "delegating to dealFailure, one inline in openTrick, one in appendPlay "
                        + "delegating to playFailure. A new one appearing here must be classified "
                        + "deliberately rather than silently counted as a non-constraint origin")
                .isEqualTo(4);
        return regions;
    }

    /** The file with the translation regions cut out of it. */
    private static Set<int[]> wholeFileExcludingTranslations(final String adapter) {
        final Set<int[]> keep = new LinkedHashSet<>();
        int cursor = 0;
        for (final int[] region : sortedByStart(translationRegions(adapter))) {
            assertThat(cursor)
                    .as(
                            "translation regions must not overlap, or the complement below is "
                                    + "negative-length and silently drops source")
                    .isLessThanOrEqualTo(region[0]);
            keep.add(new int[] {cursor, region[0]});
            cursor = region[1];
        }
        keep.add(new int[] {cursor, adapter.length()});
        return keep;
    }

    private static List<int[]> sortedByStart(final Set<int[]> regions) {
        final List<int[]> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(region -> region[0]));
        return sorted;
    }

    /** The body of the declaration starting at {@code from}, as a half-open byte range. */
    private static int[] bodyOf(final String source, final int from) {
        assertThat(from).as("the declaration this test anchors on must still exist").isNotNegative();
        final int open = source.indexOf('{', from);
        assertThat(open).as("the declaration must have a body").isNotNegative();
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            final char character = source.charAt(at);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return new int[] {open, at + 1};
                }
            }
        }
        throw new AssertionError("unbalanced braces from offset " + from);
    }

    /**
     * Domain exception constructions within the given ranges. "Domain" means a type the adapter
     * imports from {@code org.maglez.eop.domain}, which excludes {@code IllegalStateException}
     * and {@code IllegalArgumentException} — server faults rather than refusals a caller earns.
     */
    private static Set<String> domainExceptionsConstructedIn(
            final String adapter, final Set<int[]> ranges) {
        final Set<String> domainTypes = importedDomainExceptions(adapter);
        final Pattern construction = Pattern.compile("new (\\w+Exception)\\s*\\(");
        final Set<String> found = new TreeSet<>();
        for (final int[] range : ranges) {
            final Matcher matcher = construction.matcher(adapter.substring(range[0], range[1]));
            while (matcher.find()) {
                if (domainTypes.contains(matcher.group(1))) {
                    found.add(matcher.group(1));
                }
            }
        }
        return found;
    }

    /** Exception types the adapter imports from the entity package. */
    private static Set<String> importedDomainExceptions(final String adapter) {
        final Pattern imported =
                Pattern.compile("import org\\.maglez\\.eop\\.domain\\.(\\w+Exception);");
        final Matcher matcher = imported.matcher(adapter);
        final Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        assertThat(found)
                .as("the adapter must still import its domain exceptions by name")
                .isNotEmpty();
        return found;
    }

    /**
     * The bolded lead-in of a block — the run between the opening {@code **} and its closing
     * {@code **} — or the block's first line when it has no bolded lead-in.
     *
     * <p>For a bolded block this stops a phrase matching inside its body. It does not for a
     * non-bolded one: the fallback returns the first line, and every block in this paragraph is a
     * single line, so a non-bolded block is still matched whole. That path is fail-safe rather than
     * safe — a wrong match there surfaces as an equality mismatch instead of as the vacuity guard —
     * and it is why the group blocks must stay bolded.
     *
     * <p>Matching on the invariant half of the lead-in, rather than on one containing the number
     * word, is what makes a wrong heading number fail as the size mismatch it is.
     */
    private static String headlineOf(final String block) {
        final String trimmed = block.trim();
        if (!trimmed.startsWith("**")) {
            return trimmed.split("\\n", 2)[0];
        }
        final int close = trimmed.indexOf("**", 2);
        return close < 0 ? trimmed.split("\\n", 2)[0] : trimmed.substring(2, close);
    }

    /** The paragraph the count lives in, anchored on text rather than a line number. */
    private static String paragraphBesideTheTable(final String adr) {
        final int start = adr.indexOf("**This table is not an inventory");
        assertThat(start)
                .as("the paragraph that scopes the translation table must still exist")
                .isNotNegative();
        final int end = adr.indexOf("**Superseded", start);
        assertThat(end)
                .as(
                        "the superseded note must still follow it, or this extraction silently "
                                + "widens to the rest of the document and the padding check goes "
                                + "vacuous")
                .isGreaterThan(start);
        return adr.substring(start, end);
    }

    private static String numberWord(final int value) {
        return switch (value) {
            case 2 -> "Two";
            case 3 -> "Three";
            case 4 -> "Four";
            case 5 -> "Five";
            case 6 -> "Six";
            case 7 -> "Seven";
            case 8 -> "Eight";
            case 9 -> "Nine";
            case 10 -> "Ten";
            default -> String.valueOf(value);
        };
    }
}
