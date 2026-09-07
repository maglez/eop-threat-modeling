package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails the build if the Playwright suite reintroduces a secret into a publishable artefact, or if a
 * workflow publishes the HTML report as this repository's public page.
 *
 * <p>ADR-071 records the measurement behind this. The finding filed as EOP-228 was
 * failure-conditional: {@code trace}, {@code screenshot} and {@code video} fire on failure, and a
 * trace embeds the {@code eop_session} value, so a failing run can publish a live player token.
 * Verification against a deliberately failed run confirmed that -- two 43-character tokens were read
 * out of the trace's captured API response bodies verbatim -- and then went further. Playwright's
 * {@code html} reporter embeds its entire step tree as a base64 zip inside a
 * {@code <template id="playwrightReportBase64">} element, and decoding that element on the
 * <strong>fully passing</strong> 15/15 baseline recovered twelve distinct live join codes. So join
 * codes leak on every run and player tokens only on failure, which makes the ticket's premise that a
 * green run generates nothing worth inspecting false for the larger of the two channels.
 *
 * <p>Two channels carried those twelve codes. A custom {@code expect()} message becomes a step title
 * <em>whether or not the assertion fails</em>, so
 * {@code expect(joinCode, `join code ${joinCode} is not eight characters`)} published the code on
 * every green run. And {@code locator.fill()} records the filled value in its own step title, which
 * is inherent to typing a join code into {@code #join-code}. ADR-071 closes the first and
 * deliberately leaves the second, because suppressing it would mean not exercising real user input.
 * The control for the second is that the HTML report is never the published page. It is <em>not</em>
 * that the report is private: this repository is public, so an Actions artefact is world-readable
 * too, and ADR-071 accepts that residual explicitly rather than claiming a containment that does not
 * exist.
 *
 * <p><strong>Why a text gate rather than a scrubber.</strong> The secrets exist in at least six
 * representations -- API response bodies in the trace's {@code resources/*.json}, DOM snapshots,
 * network logs, an {@code error-context.md} aria snapshot, <em>visually rendered</em> in screencast
 * JPEGs and failure PNGs, and step titles in the embedded report JSON. No regular expression reaches
 * the visual class, and the trace is a version-dependent internal zip whose scrubber would rot
 * silently at the next Playwright upgrade. Subtraction is verifiable where transformation is not, so
 * this test pins the subtraction and the two source fixes rather than attempting to sanitise.
 *
 * <p><strong>What this test does not do.</strong> It proves that no interpolation in {@code e2e/}
 * reveals a secret value, that the {@code eop_session} read is reduced to a boolean in the browser,
 * that no workflow step publishes {@code playwright-report/} to Pages, and that
 * {@code e2e/README.md} records the outcome. It cannot prove a future publication step is safe: CI
 * has no E2E wiring at all yet -- EOP-220 adds it and EOP-221 publishes -- so the workflow rule here
 * is preventive, and a sufficiently novel step will evade a text matcher. It equally cannot prove the
 * HTML report is free of secrets, because ADR-071 decides it never will be. Those bounds stay
 * reviewer-enforced, and EOP-221's own Definition-of-Done round is where they land.
 */
@DisplayName("E2E artefacts do not leak secrets into a publishable channel")
class E2eArtefactPublicationBoundaryTest {

    /** The Playwright package. Its {@code node_modules} and run output are not sources. */
    private static final Path E2E_DIR = Path.of("e2e");

    /** The workflow directory. EOP-220 and EOP-221 add E2E steps here; none exists today. */
    private static final Path WORKFLOW_DIR = Path.of(".github", "workflows");

    /** The prose that must carry the outcome, per EOP-228's third requirement. */
    private static final Path E2E_README = Path.of("e2e", "README.md");

    /**
     * A floor, so no rule here can pass by scanning nothing. Four specs plus {@code game.ts},
     * {@code stack.ts}, {@code global-setup.ts}, {@code global-teardown.ts} and the config is nine.
     */
    private static final int MINIMUM_E2E_SOURCES = 9;

    /**
     * Identifiers whose value is a secret or reveals one, as whole-word patterns. Bare {@code code}
     * is the local {@code game.ts} binds from the lobby DOM, and the boundary is what keeps
     * {@code exitCode} and {@code statusCode} -- both legitimate in an error message from
     * {@code stack.ts} -- out of the match.
     *
     * <p>{@code stored} matches nothing in {@code e2e/} today, and that is deliberate rather than
     * stale. It was the name of the local the happy-path reload scenario bound to the raw
     * {@code eop_session} value, which is the defect ADR-071 fixes; the fix renamed it
     * {@code hasSession} because it now holds a boolean. The entry is retained so the defect cannot
     * return under its original name -- a future author reintroducing
     * {@code const stored = await page.evaluate(() => sessionStorage.getItem('eop_session'))} is
     * caught by rule three rather than merely reviewed. Do not read a zero match count as evidence
     * the entry is dead: every rule here carries its own floor over the file list, so an entry that
     * currently matches nothing cannot make a rule pass vacuously.
     */
    private static final List<Pattern> SECRET_IDENTIFIERS = Stream.of(
                    "joinCode", "playerToken", "eop_session", "stored", "code")
            .map(name -> Pattern.compile("(?<![A-Za-z0-9_])" + name + "(?![A-Za-z0-9_])"))
            .toList();

    /**
     * A template-literal interpolation. The class is restricted rather than the quantifier lazy, so a
     * nested brace ends the match -- {@code ${f({joinCode})}} is therefore not seen, and stays a
     * reviewer's problem.
     */
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^{}]*)}");

    /** The storage read. ADR-071 requires it be compared in the browser, never returned. */
    private static final Pattern SESSION_STORAGE_READ =
            Pattern.compile("getItem\\(\\s*'eop_session'\\s*\\)\\s*(.{0,4})", Pattern.DOTALL);

    /** An {@code expect()} subject -- the value a failing matcher prints back as "Received". */
    private static final Pattern ASSERTED_VALUE = Pattern.compile("expect\\(\\s*([A-Za-z0-9_.]+)\\s*[,)]");

    /**
     * The one matcher that may take a secret directly. {@code not.toBeNull()} can only fail when
     * the received value <em>is</em> null, so it can never print the secret back.
     */
    private static final String NULL_MATCHER = ".not.toBeNull()";

    /** Markers of a world-readable publication. {@code e2e-report} is EOP-221's orphan branch. */
    private static final List<String> PAGES_MARKERS =
            List.of("actions/deploy-pages", "peaceiris", "gh-pages", "e2e-report", "github-pages");

    @Test
    @DisplayName("no interpolation in e2e/ reveals a join code or a player token")
    void noInterpolationRevealsASecret() throws IOException {
        final List<Path> sources = e2eSources();
        assertThat(sources)
                .as("e2e/ TypeScript sources found -- a rule that scans nothing proves nothing")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_E2E_SOURCES);

        final List<String> offences = new ArrayList<>();
        for (final Path source : sources) {
            final String text = Files.readString(source);
            final Matcher matcher = INTERPOLATION.matcher(text);
            while (matcher.find()) {
                final String expression = matcher.group(1).trim();
                if (revealsASecret(expression)) {
                    offences.add("%s: ${%s}".formatted(source, expression));
                }
            }
        }

        assertThat(offences)
                .as(
                        "A custom expect() message becomes a step title in the HTML report's embedded step tree even "
                                + "when the assertion passes, so interpolating a secret publishes it on every green run "
                                + "(ADR-071). Report a length or an identifier, never a value.")
                .isEmpty();
    }

    @Test
    @DisplayName("the eop_session read is reduced to a boolean in the browser")
    void theSessionStorageReadIsReducedInTheBrowser() throws IOException {
        final List<String> offences = new ArrayList<>();
        int reads = 0;
        for (final Path source : e2eSources()) {
            final Matcher matcher = SESSION_STORAGE_READ.matcher(Files.readString(source));
            while (matcher.find()) {
                reads++;
                final String following = matcher.group(1);
                if (!following.contains("!==") && !following.contains("===")) {
                    offences.add("%s: getItem('eop_session') is not compared in the browser".formatted(source));
                }
            }
        }

        assertThat(reads)
                .as("the happy-path reload scenario reads eop_session -- if this is zero the matcher has rotted")
                .isPositive();
        assertThat(offences)
                .as(
                        "eop_session holds {playerToken, playerId, sessionId}. A value returned into the test process "
                                + "is recorded in the trace, and the assertion needs only non-nullness, so compare in "
                                + "the page and return a boolean (ADR-071).")
                .isEmpty();
    }

    @Test
    @DisplayName("no assertion in e2e/ prints a secret back as its received value")
    void noAssertionPrintsASecretAsItsReceivedValue() throws IOException {
        final List<String> offences = new ArrayList<>();
        final List<Path> sources = e2eSources();
        for (final Path source : sources) {
            final String[] lines = Files.readString(source).split("\n", -1);
            for (int line = 0; line < lines.length; line++) {
                final Matcher matcher = ASSERTED_VALUE.matcher(lines[line]);
                while (matcher.find()) {
                    final String subject = matcher.group(1);
                    if (namesASecret(subject) && !subject.endsWith(".length") && !lines[line].contains(NULL_MATCHER)) {
                        offences.add("%s:%d asserts on %s".formatted(source, line + 1, subject));
                    }
                }
            }
        }

        assertThat(sources)
                .as("the e2e sources must be found -- an empty list would pass this rule vacuously")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_E2E_SOURCES);
        assertThat(offences)
                .as(
                        "a failing matcher prints its received value, so asserting on a secret publishes it even when "
                                + "the custom message does not. Assert on a bound instead -- expect(code.length).toBe(8) "
                                + "prints a number (ADR-071).")
                .isEmpty();
    }

    @Test
    @DisplayName("no workflow step publishes playwright-report/ to a world-readable destination")
    void noWorkflowPublishesTheHtmlReportPublicly() throws IOException {
        if (!Files.isDirectory(WORKFLOW_DIR)) {
            return;
        }

        final List<String> offences = new ArrayList<>();
        try (Stream<Path> workflows = Files.list(WORKFLOW_DIR)) {
            for (final Path workflow : workflows.filter(E2eArtefactPublicationBoundaryTest::isYaml).toList()) {
                for (final String step : Files.readString(workflow).split("(?m)^\\s{2,}- ")) {
                    if (!step.contains("playwright-report")) {
                        continue;
                    }
                    PAGES_MARKERS.stream()
                            .filter(step::contains)
                            .map(marker -> "%s: a step names both playwright-report and %s".formatted(workflow, marker))
                            .forEach(offences::add);
                }
            }
        }

        assertThat(offences)
                .as(
                        "The html reporter embeds its whole step tree, which carries a live join code on every run "
                                + "including a passing one, so playwright-report/ must never become the published page. The "
                                + "world-readable page is built from results.json alone (ADR-071).")
                .isEmpty();
    }

    @Test
    @DisplayName("e2e/README.md records the artefact outcome and no longer misattributes it to ADR-069")
    void theReadmeRecordsTheOutcome() throws IOException {
        final String readme = Files.readString(E2E_README);

        assertThat(readme)
                .as(
                        "EOP-228's third requirement is that the outcome is stated where the next author will read it, so "
                                + "this file must cite ADR-071 and must not cite ADR-069. ADR-069 and ADR-070 exist and are "
                                + "about the SSE doorbell and an open-in-view connection leak, so a sentence promising "
                                + "ADR-069 will cover CI and report publication is a dangling reference to a decision that "
                                + "was numbered elsewhere.")
                .contains("ADR-071")
                .doesNotContain("ADR-069");
    }

    /** @return whether an interpolated expression yields a secret rather than a bound on one */
    private static boolean revealsASecret(final String expression) {
        // `.length` bounds the value without revealing it, which is the shape ADR-071 substitutes.
        return namesASecret(expression) && !expression.endsWith(".length");
    }

    /** @return whether the expression mentions one of the secret-bearing identifiers */
    private static boolean namesASecret(final String expression) {
        return SECRET_IDENTIFIERS.stream().anyMatch(pattern -> pattern.matcher(expression).find());
    }

    /** @return every hand-written TypeScript source under {@code e2e/}, excluding dependencies */
    private static List<Path> e2eSources() throws IOException {
        try (Stream<Path> tree = Files.walk(E2E_DIR)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".ts"))
                    .filter(path -> !path.toString().contains("node_modules"))
                    .sorted()
                    .toList();
        }
    }

    /** @return whether the path is a workflow definition */
    private static boolean isYaml(final Path path) {
        final String name = path.getFileName().toString();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
