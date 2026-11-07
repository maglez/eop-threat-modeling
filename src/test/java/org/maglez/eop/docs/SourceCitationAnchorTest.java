package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.application.port.in.PlayCardPort;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.inbound.web.SessionController;

/**
 * Holds the {@code File.java:123} line citations scattered through {@code docs/} against the files they name.
 *
 * <p>Documentation in this repository cites source by line — {@code SessionController.java:63} — and nothing used to check one.
 * EOP-49 existed solely because seven such anchors had drifted onto javadoc {@code @throws} tags roughly forty lines above the
 * code they claimed to name, and EOP-49's own ticket body then carried replacement numbers that were themselves stale by
 * twenty-four lines because they had been derived arithmetically instead of re-read. This gate is the eighth text-comparison
 * guard under {@code src/test/java/org/maglez/eop/docs/} and closes that gap for the citations that opt in.</p>
 *
 * <p><strong>Three checks, two of them universal.</strong> Every citation this gate recognises must name a path that resolves
 * to a tracked file (or sit in {@link #EXTERNAL_CITATIONS}), and must name lines that exist in that file. Those two catch
 * renames, typos and citations into files that have since shrunk. They are cheap and they apply to all 163 citations, but on
 * their own they are close to vacuous: a line number that has drifted onto a different line of a file that is still long
 * enough passes both. The third check is where the real strength is, and it is <em>opt-in</em>: a citation written as</p>
 *
 * <pre>{@code `Trick.java:441` (anchor: `winningPlay`)}</pre>
 *
 * <p>additionally asserts that the literal text {@code winningPlay} occurs within the cited line range. That form makes the
 * citation self-verifying — the anchor is the {@code grep} that re-derives the number — which is the instinct EOP-41 and
 * EOP-49 both converged on, and it is the ticket's own preferred option ("require a quoted token that must appear at that
 * line").</p>
 *
 * <p><strong>Why anchoring is opt-in rather than inferred.</strong> A prototype that inferred the anchor from nearby
 * backticked identifiers flagged 36 of the 67 citations it could infer for, and the noise was not tunable. Two causes are
 * fundamental. Generic tokens carry no signal — {@code true}, {@code throw} and {@code getId} occur all over a file. And the
 * ambiguity is irreducible: 58 citations in {@code docs/} deliberately point at javadoc or comments, which is the very same
 * position EOP-49's defects had drifted onto, so no automatic rule can separate "cites the comment on purpose" from "drifted
 * onto the comment". Requiring the author to state the anchor dissolves both, and makes the gate ratchet: adoption can only
 * ever add coverage, never a false failure.</p>
 *
 * <p><strong>Opt-in has a second, less obvious payoff:</strong> it lets documentation quote its own history truthfully. Some
 * citations here are deliberately stale, because the prose is narrating a past defect rather than pointing at today's code —
 * {@code ADR-013:198} preserves the line number EOP-70 falsified, and several blockquoted passages freeze text written months
 * ago. A gate that checked every citation semantically would have to either falsify those quotations or grow an opt-out
 * marker. Because an unanchored citation is checked only for resolvability and range, they pass untouched and no exclusion
 * rule for blockquotes or quoted spans is needed anywhere in this class.</p>
 *
 * <p><strong>Coverage limits, stated because they are not obvious.</strong></p>
 * <ul>
 *   <li><em>Named citations only.</em> The gate recognises {@code Name.ext:123} and {@code Name.ext:123-456}. It deliberately
 *       does not recognise the bare continuation form {@code `:124`} that several ADRs use after naming a file once, for two
 *       reasons: that syntax is indistinguishable from a port number — {@code `:8080`}, {@code `:5173`} and {@code `:5371`}
 *       all appear in {@code docs/} — and its antecedent is not mechanically recoverable, because a list such as
 *       {@code ADR-013:184-185} names ten files before the next bare reference, so "the last file named" binds to the wrong
 *       one. Roughly 122 bare references therefore go unchecked. A claim worth protecting must be written in the named form.</li>
 *   <li><em>Presence, not position.</em> An anchor must occur somewhere inside the cited range, not on an exact line. A
 *       single-line citation is therefore exact, while a wide range is correspondingly weaker.</li>
 *   <li><em>No proof of truth.</em> Like every guard in this package, a green result says the specific things checked here are
 *       consistent. It does not say the surrounding prose is correct.</li>
 * </ul>
 *
 * <p>Both floors below exist for the reason ADR-006 records in its branch-coverage lesson: a guard that has quietly stopped
 * matching anything is worse than no guard, because it reads as protection. If this gate ever becomes unfireable, delete it
 * rather than leave it green.</p>
 *
 * <p>Paths are resolved relative to the project base directory, which is Surefire's working directory.</p>
 */
@DisplayName("Line citations in documentation point at the code they claim")
class SourceCitationAnchorTest {

    /** Directory holding the documentation whose citations are checked. */
    private static final Path DOCUMENTATION_DIRECTORY = Path.of("docs");

    /** Project base directory, used to resolve a cited path against the files actually present. */
    private static final Path PROJECT_ROOT = Path.of(".");

    /**
     * Directory names never descended into when collecting candidate source files.
     *
     * <p>The working tree is roughly 1.9 GB, almost all of it build output and dependencies, against only a few hundred
     * tracked files. Walking it unfiltered would dominate the runtime of this test, so these are pruned with
     * {@link FileVisitResult#SKIP_SUBTREE}. Pruning {@code .tmp} matters for correctness as well as speed: scratch work
     * conventionally lives there, including {@code git worktree} checkouts that would otherwise contribute a second copy of
     * every tracked path and make every citation ambiguous.</p>
     */
    private static final Set<String> PRUNED_DIRECTORIES = Set.of(".git", ".idea", ".tmp", "dist", "node_modules", "target");

    /**
     * Cited paths that intentionally do not resolve to a file in this repository.
     *
     * <p>Both entries name {@code liquibase-core} internals. ADR-006 and ADR-043 cite them to explain how Liquibase itself
     * computes changeset identity, which is a legitimate thing for an ADR to do and not something this repository can hold to
     * a line number. Keep this list short: every entry is a citation nothing verifies, so an addition should be a deliberate
     * decision about a genuinely external file rather than a way to silence a failure about one of our own.</p>
     */
    private static final Set<String> EXTERNAL_CITATIONS = Set.of("ChangeSet.java", "liquibase/changelog/ChangeSet.java");

    /**
     * Lower bound on the number of citations this gate must find.
     *
     * <p>171 were present when the gate was written. The floor sits below that so ordinary editing does not trip it, while a
     * collapse to zero — a moved directory, a broken pattern — still fails loudly instead of passing silently.</p>
     */
    private static final int MINIMUM_CITATIONS = 150;

    /**
     * Lower bound on the number of citations carrying an explicit anchor.
     *
     * <p>This is the floor that actually matters, because the anchored subset carries all of the gate's strength. Twenty-one
     * were anchored when the gate landed, each one a claim whose line number had already drifted at least once.</p>
     *
     * <p>The slack over that count is deliberately narrow. {@link #shouldParseEveryAnchorMarkerItFinds()} catches an anchor
     * written in an unrecognised position, but it cannot see one that is simply deleted, so this floor is the only thing
     * standing between the gate and quiet erosion. Retiring an anchor legitimately — because the ADR that carried it was
     * removed — should mean lowering this number in the same commit, as a visible decision rather than a silent one.</p>
     */
    private static final int MINIMUM_ANCHORED_CITATIONS = 18;

    /**
     * Matches a named line citation such as {@code Trick.java:441} or {@code GameSessionJpaRepository.java:76-106}.
     *
     * <p>The lookbehind stops the pattern starting mid-path, and the trailing lookahead stops it swallowing part of a longer
     * token. Restricting the extension to a closed list is what keeps ports, Docker image tags such as {@code influxdb:1.8}
     * and clock times such as {@code 09:00} out of the results — all three appear in {@code docs/} and none is a citation.</p>
     *
     * <p>{@code Dockerfile} needs its own branch rather than a place in the extension list, because it carries no dot and the
     * dotted branch requires one. It sat in that list until 2026-08-23 and so could never match, which meant this gate read as
     * covering {@code ui/Dockerfile:13} while silently ignoring it — the same hollow-coverage failure
     * {@link #shouldParseEveryAnchorMarkerItFinds()} exists to prevent, found by {@code @code-reviewer} on this story's own
     * gate round. Any future dotless filename needs the same treatment; adding it to the list alone would do nothing.</p>
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile(
            "(?<![\\w/.-])((?:[A-Za-z0-9_][\\w/.-]*\\.(?:java|xml|yml|yaml|ts|tsx|json|sh|mjs|js|properties|md)"
                    + "|(?:[A-Za-z0-9_][\\w/.-]*/)?Dockerfile)):(\\d+)(?:-(\\d+))?(?![\\d\\w])");

    /**
     * Matches the optional anchor marker immediately following a citation.
     *
     * <p>Two placements are accepted, because a citation is often already inside a parenthetical and forcing one form would
     * mean either doubled brackets or contorted prose:</p>
     *
     * <ul>
     *   <li>{@code `Trick.java:441` (anchor: `winningPlay`)} — a parenthetical of its own</li>
     *   <li>{@code (`Trick.java:438`, anchor: `winningPlay()`)} — folded into the citation's own parenthetical</li>
     * </ul>
     *
     * <p>Applied to the remainder of the line after the citation match, so the leading {@code (} or {@code ,} must follow the
     * number directly. The optional leading backtick is required because a citation is normally written inside backticks, so
     * the text after the number begins with the closing one. {@link #shouldParseEveryAnchorMarkerItFinds()} fails the build if
     * a third placement is ever invented, so an unrecognised marker cannot sit in the prose looking enforced.</p>
     */
    private static final Pattern ANCHOR_PATTERN = Pattern.compile("^`?\\s*[(,]\\s*anchor:\\s*`([^`]+)`");

    /**
     * Matches a literal anchor marker anywhere in a line, used only to count markers the parser ought to have bound.
     *
     * <p>Deliberately looser than {@link #ANCHOR_PATTERN}: it does not care what precedes the word, so it finds markers that
     * are written in an unsupported position and would otherwise be silently ignored.</p>
     */
    private static final Pattern ANCHOR_MARKER_PATTERN = Pattern.compile("anchor:\\s*`[^`]+`");

    /**
     * One line citation found in a documentation file.
     *
     * @param document       documentation file the citation was written in
     * @param documentLine   one-based line number of the citation within {@code document}
     * @param citedPath      path as written in the prose, which may be a bare file name or a partial path
     * @param startLine      first cited line, one-based
     * @param endLine        last cited line, one-based; equal to {@code startLine} for a single-line citation
     * @param anchor         literal text that must occur within the cited range, or {@code null} when the citation is unanchored
     */
    private record Citation(Path document, int documentLine, String citedPath, int startLine, int endLine, String anchor) {

        /**
         * Renders the citation for a failure message, naming where it was written and what it points at.
         *
         * @return a description such as {@code docs/adr/ADR-023.md:1290 cites Trick.java:441 (anchor: winningPlay)}
         */
        private String describe() {
            final String range = startLine == endLine ? String.valueOf(startLine) : startLine + "-" + endLine;
            final String anchorText = anchor == null ? "" : " (anchor: " + anchor + ")";
            return "%s:%d cites %s:%s%s".formatted(document, documentLine, citedPath, range, anchorText);
        }
    }

    @Test
    @DisplayName("every cited path resolves to exactly one file in the repository")
    void shouldResolveEveryCitedPath() {
        final List<Path> sourceFiles = sourceFiles();
        final List<String> unresolved = new ArrayList<>();
        final List<String> ambiguous = new ArrayList<>();

        for (final Citation citation : citations()) {
            if (EXTERNAL_CITATIONS.contains(citation.citedPath())) {
                continue;
            }
            final List<Path> matches = resolve(citation.citedPath(), sourceFiles);
            if (matches.isEmpty()) {
                unresolved.add(citation.describe());
            } else if (matches.size() > 1) {
                ambiguous.add(citation.describe() + " — matches " + matches);
            }
        }

        assertThat(unresolved)
                .as("These citations name a file that does not exist. A citation is a promise the reader can follow, so a "
                        + "renamed or deleted file must be chased through the prose that names it. If the file is genuinely "
                        + "external to this repository, add it to EXTERNAL_CITATIONS with a note saying why nothing can "
                        + "verify it:%n%s", String.join(System.lineSeparator(), unresolved))
                .isEmpty();
        assertThat(ambiguous)
                .as("These citations name a file whose name is not unique, so a reader cannot tell which one is meant. "
                        + "Qualify the citation with enough leading path to disambiguate it, as in "
                        + "`adapter/web/SessionController.java:63`:%n%s", String.join(System.lineSeparator(), ambiguous))
                .isEmpty();
    }

    @Test
    @DisplayName("every cited line number exists in the file it points at")
    void shouldCiteLinesThatExist() {
        final List<Path> sourceFiles = sourceFiles();
        final List<String> offences = new ArrayList<>();

        for (final Citation citation : citations()) {
            if (EXTERNAL_CITATIONS.contains(citation.citedPath())) {
                continue;
            }
            final List<Path> matches = resolve(citation.citedPath(), sourceFiles);
            if (matches.size() != 1) {
                continue;
            }
            final int length = readLines(matches.get(0)).size();
            if (citation.startLine() < 1 || citation.endLine() > length) {
                offences.add("%s — %s has %d lines".formatted(citation.describe(), matches.get(0), length));
            } else if (citation.startLine() > citation.endLine()) {
                offences.add(citation.describe() + " — the range runs backwards");
            }
        }

        assertThat(offences)
                .as("These citations point past the end of the file they name, which means the file has shrunk since the "
                        + "citation was written and the surrounding claim has not been re-read. Re-derive the number by "
                        + "opening the file rather than by adjusting the old one arithmetically — EOP-49 shipped replacement "
                        + "numbers that were stale by twenty-four lines because they were computed instead of read:%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    @Test
    @DisplayName("every anchored citation finds its anchor inside the cited range")
    void shouldFindItsAnchorTokenWithinTheCitedRange() {
        final List<Path> sourceFiles = sourceFiles();
        final List<String> offences = new ArrayList<>();

        for (final Citation citation : citations()) {
            if (citation.anchor() == null || EXTERNAL_CITATIONS.contains(citation.citedPath())) {
                continue;
            }
            final List<Path> matches = resolve(citation.citedPath(), sourceFiles);
            if (matches.size() != 1) {
                continue;
            }
            final List<String> lines = readLines(matches.get(0));
            if (citation.endLine() > lines.size()) {
                continue;
            }
            if (!containsAnchor(lines, citation.startLine(), citation.endLine(), citation.anchor())) {
                offences.add("%s — not found in the cited range; it occurs at %s".formatted(
                        citation.describe(), describeOccurrences(lines, citation.anchor())));
            }
        }

        assertThat(offences)
                .as("These citations carry an explicit anchor that is no longer inside the range they name, so the line "
                        + "number has drifted away from the code the prose is describing. The occurrences listed after each "
                        + "one are where the anchor actually is — re-point the citation there, or, if the claim itself has "
                        + "changed rather than merely moved, rewrite the claim:%n%s", String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    @Test
    @DisplayName("the gate still finds the citations it guards")
    void shouldFindTheCitationsItGuards() {
        final List<Citation> citations = citations();

        assertThat(citations)
                .as("No line citations were found under %s at all. Either the documentation moved or CITATION_PATTERN "
                        + "stopped matching; a guard that can no longer fire must be fixed or deleted, never left green.",
                        DOCUMENTATION_DIRECTORY)
                .isNotEmpty();
        assertThat(citations.size())
                .as("Only %d citations were found, below the floor of %d. That is a large enough drop to suggest the "
                        + "extraction has broken rather than that the documentation genuinely shrank — confirm which, then "
                        + "either fix the pattern or lower the floor deliberately.", citations.size(), MINIMUM_CITATIONS)
                .isGreaterThanOrEqualTo(MINIMUM_CITATIONS);
    }

    @Test
    @DisplayName("the gate still finds the anchored citations that carry its strength")
    void shouldFindTheAnchoredCitationsItGuards() {
        final long anchored = citations().stream().filter(citation -> citation.anchor() != null).count();

        assertThat(anchored)
                .as("Only %d anchored citations were found, below the floor of %d. The anchored subset is the only part of "
                        + "this gate that verifies a citation points at the right code, so if anchors are being removed the "
                        + "gate is being hollowed out while still reporting success. Restore the anchors, or retire the gate "
                        + "outright per ADR-006's rule about guards that can no longer fire.", anchored, MINIMUM_ANCHORED_CITATIONS)
                .isGreaterThanOrEqualTo(MINIMUM_ANCHORED_CITATIONS);
    }

    /**
     * Requires every anchor marker written in the documentation to have been bound to a citation by the parser.
     *
     * <p>This is the guard against a hollowed-out gate, and it exists because the defect it catches happened during this
     * story's own implementation. Sixteen anchors were written and only fourteen were enforced: two had been placed as
     * {@code (`File.java:63`, anchor: `token`)}, a form the pattern of the day did not accept, so they read to a human as
     * checked while the gate never looked at them. An anchor that appears enforced but is not is worse than no anchor,
     * because it invites exactly the trust the story exists to make earnable.</p>
     *
     * <p>Counting markers with a deliberately looser pattern and comparing against what the parser bound turns any future
     * third placement into a build failure rather than a silent omission.</p>
     */
    @Test
    @DisplayName("every anchor marker in the documentation is one the parser actually enforces")
    void shouldParseEveryAnchorMarkerItFinds() {
        final List<String> orphans = new ArrayList<>();
        long markers = 0;
        for (final Path document : markdownFiles()) {
            final List<String> lines = readLines(document);
            final List<Citation> parsed = citations(document, lines);
            for (int index = 0; index < lines.size(); index++) {
                final int lineNumber = index + 1;
                final long markersOnLine = ANCHOR_MARKER_PATTERN.matcher(lines.get(index)).results().count();
                markers += markersOnLine;
                final long boundOnLine = parsed.stream()
                        .filter(citation -> citation.documentLine() == lineNumber && citation.anchor() != null)
                        .count();
                if (markersOnLine > boundOnLine) {
                    orphans.add("%s:%d — %d marker(s) written, %d bound"
                            .formatted(slashed(document), lineNumber, markersOnLine, boundOnLine));
                }
            }
        }

        assertThat(orphans)
                .as("These lines carry an anchor marker the gate does not enforce, so the prose claims a guarantee the build "
                        + "does not provide — the hollow-anchor defect this test exists to prevent. Write the marker "
                        + "immediately after the citation, in one of the two supported forms: `File.java:63` "
                        + "(anchor: `token`), or (`File.java:63`, anchor: `token`). Orphans:%n%s",
                        String.join(System.lineSeparator(), orphans))
                .isEmpty();
        assertThat(markers)
                .as("No anchor markers were found in the documentation at all, so nothing is being content-checked and this "
                        + "gate has become a no-op. Restore the anchors or retire the gate, per ADR-006's rule about guards "
                        + "that can no longer fire.")
                .isGreaterThanOrEqualTo(MINIMUM_ANCHORED_CITATIONS);
    }

    @Test
    @DisplayName("citations and anchors are parsed out of synthetic prose")
    void shouldParseCitationsAndAnchorsFromSyntheticProse() {
        final List<String> prose = List.of(
                "The publish happens at `PlayCardPort.java:253` (anchor: `CARD_PLAYED`), after the writes.",
                "The constructor rejects a bad seat (`Trick.java:51`).",
                "See ([`application.yml:122-169`](../../src/main/resources/application.yml)) for the flags.",
                "    Note over PC,EV: EOP-14 Slice E, PlayCardPort.java:253.<br/>After the writes");

        final List<Citation> found = citations(Path.of("synthetic.md"), prose);

        assertThat(found).hasSize(4);
        assertThat(found.get(0).citedPath()).isEqualTo("PlayCardPort.java");
        assertThat(found.get(0).startLine()).isEqualTo(253);
        assertThat(found.get(0).endLine()).isEqualTo(253);
        assertThat(found.get(0).anchor()).isEqualTo("CARD_PLAYED");
        assertThat(found.get(1).anchor()).isNull();
        assertThat(found.get(2).startLine()).isEqualTo(122);
        assertThat(found.get(2).endLine()).isEqualTo(169);
        assertThat(found.get(3).documentLine()).isEqualTo(4);
    }

    @Test
    @DisplayName("a drifted anchor is detected in synthetic source")
    void shouldDetectADriftedAnchorInSyntheticSource() {
        final List<String> source = List.of("class Example {", "", "    void moved() {", "        publish();", "    }", "}");

        assertThat(containsAnchor(source, 2, 2, "moved")).isFalse();
        assertThat(containsAnchor(source, 3, 3, "moved")).isTrue();
        assertThat(describeOccurrences(source, "moved")).isEqualTo("line 3");
    }

    @Test
    @DisplayName("colon-suffixed text that is not a citation is ignored")
    void shouldIgnoreTextThatIsNotACitation() {
        final List<String> prose = List.of(
                "Vite serves the bundle on `:5173` and proxies to Spring Boot on `:8080`.",
                "The stack pins `image: influxdb:1.8` and the cron runs at 09:00.",
                "Continuation references such as `:124` and `:167` are out of scope.",
                "A URL such as https://example.com:8443/path is not a citation either.");

        assertThat(citations(Path.of("synthetic.md"), prose)).isEmpty();
    }

    /**
     * Extracts every named line citation from every documentation file.
     *
     * @return the citations found, ordered by file and then by position within the file
     */
    private static List<Citation> citations() {
        final List<Citation> citations = new ArrayList<>();
        for (final Path document : markdownFiles()) {
            citations.addAll(citations(document, readLines(document)));
        }
        return citations;
    }

    /**
     * Extracts every named line citation from one file's content.
     *
     * <p>Split from {@link #citations()} so the parsing can be exercised against hand-built input, which is what makes it
     * possible to prove this gate can fire without first breaking the repository.</p>
     *
     * @param document file the lines came from, used only for reporting
     * @param lines    content of {@code document}, one entry per line
     * @return the citations found, in the order they appear
     */
    private static List<Citation> citations(final Path document, final List<String> lines) {
        final List<Citation> citations = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index);
            final Matcher matcher = CITATION_PATTERN.matcher(line);
            while (matcher.find()) {
                final int startLine = Integer.parseInt(matcher.group(2));
                final int endLine = matcher.group(3) == null ? startLine : Integer.parseInt(matcher.group(3));
                citations.add(new Citation(document, index + 1, matcher.group(1), startLine, endLine,
                        anchorAfter(line, matcher.end())));
            }
        }
        return citations;
    }

    /**
     * Reads the optional {@code (anchor: `token`)} suffix that follows a citation.
     *
     * @param line  full line of prose
     * @param after index immediately after the citation match
     * @return the anchor text, or {@code null} when the citation carries none
     */
    private static String anchorAfter(final String line, final int after) {
        final Matcher matcher = ANCHOR_PATTERN.matcher(line.substring(after));
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Reports whether an anchor occurs anywhere within a cited range.
     *
     * @param lines     content of the cited file, one entry per line
     * @param startLine first cited line, one-based
     * @param endLine   last cited line, one-based
     * @param anchor    literal text to look for
     * @return {@code true} when {@code anchor} occurs on at least one line of the range
     */
    private static boolean containsAnchor(final List<String> lines, final int startLine, final int endLine, final String anchor) {
        for (int lineNumber = startLine; lineNumber <= Math.min(endLine, lines.size()); lineNumber++) {
            if (lines.get(lineNumber - 1).contains(anchor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Describes where an anchor actually occurs, so a failure message can point at the fix.
     *
     * @param lines  content of the cited file, one entry per line
     * @param anchor literal text to look for
     * @return a description such as {@code line 441} or {@code lines 63, 187 and 4 more}, or a note that it is absent
     */
    private static String describeOccurrences(final List<String> lines, final String anchor) {
        final List<Integer> found = new ArrayList<>();
        for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
            if (lines.get(lineNumber - 1).contains(anchor)) {
                found.add(lineNumber);
            }
        }
        if (found.isEmpty()) {
            return "no line of the file — the anchor text is gone, so re-read the code before rewriting the claim";
        }
        if (found.size() == 1) {
            return "line " + found.get(0);
        }
        final List<Integer> shown = found.subList(0, Math.min(6, found.size()));
        final String remainder = found.size() > shown.size() ? " and %d more".formatted(found.size() - shown.size()) : "";
        return "lines " + shown.stream().map(String::valueOf).reduce((left, right) -> left + ", " + right).orElseThrow() + remainder;
    }

    /**
     * Resolves a path as written in prose against the files present in the repository.
     *
     * <p>Tried in order of decreasing precision: an exact path, then a path suffix, then a unique file name. Returning every
     * match rather than the first lets the caller distinguish "no such file" from "the name is ambiguous", which are
     * different defects with different fixes.</p>
     *
     * @param citedPath   path as written in the prose
     * @param sourceFiles candidate files, as paths relative to the project base directory
     * @return every candidate the cited path could mean
     */
    private static List<Path> resolve(final String citedPath, final List<Path> sourceFiles) {
        final List<Path> exact = sourceFiles.stream().filter(file -> slashed(file).equals(citedPath)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        final List<Path> suffix = sourceFiles.stream().filter(file -> slashed(file).endsWith("/" + citedPath)).toList();
        if (!suffix.isEmpty()) {
            return suffix;
        }
        return sourceFiles.stream().filter(file -> file.getFileName().toString().equals(citedPath)).toList();
    }

    /**
     * Renders a path with forward slashes, so comparison against prose does not depend on the platform separator.
     *
     * @param path path to render
     * @return the path as a string using {@code /} as separator
     */
    private static String slashed(final Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * Collects every file a citation could resolve to, pruning build output and dependencies.
     *
     * @return regular files under the project base directory, relative to it and sorted for reproducible messages
     */
    private static List<Path> sourceFiles() {
        final List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(PROJECT_ROOT, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
                    final Path name = directory.getFileName();
                    return name != null && PRUNED_DIRECTORIES.contains(name.toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        files.add(PROJECT_ROOT.relativize(file).normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException cause) {
            throw new UncheckedIOException("Unable to walk " + PROJECT_ROOT.toAbsolutePath(), cause);
        }
        return files.stream().sorted().toList();
    }

    /**
     * Lists the Markdown files whose citations are checked.
     *
     * @return every {@code .md} file under {@link #DOCUMENTATION_DIRECTORY}, sorted for reproducible messages
     */
    private static List<Path> markdownFiles() {
        try (Stream<Path> tree = Files.walk(DOCUMENTATION_DIRECTORY)) {
            return tree.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".md")).sorted().toList();
        } catch (final IOException cause) {
            throw new UncheckedIOException("Unable to walk " + DOCUMENTATION_DIRECTORY.toAbsolutePath(), cause);
        }
    }

    /**
     * Reads a file as lines.
     *
     * @param path file to read
     * @return the file's lines, without terminators
     */
    private static List<String> readLines(final Path path) {
        try {
            return Files.readAllLines(path);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Unable to read " + path.toAbsolutePath(), cause);
        }
    }
}
