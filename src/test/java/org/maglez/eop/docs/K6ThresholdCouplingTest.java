package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


/**
 * Holds the k6 threshold-count constant in {@code .github/workflows/ci.yml} in step with the
 * thresholds actually declared in {@code test/k6/config/options-ci.js}, and holds the workflow to
 * selecting the CI threshold profile at all.
 *
 * <p>The performance canary added by EOP-155 does not trust k6's exit code, because k6 exits 0
 * even when it could not write its metrics. Instead the workflow parses the exported summary and
 * counts the thresholds it reports, comparing that count against a hardcoded
 * {@code EXPECTED_THRESHOLD_COUNT}. That constant is what turns a silently truncated summary into
 * a loud failure -- and until 2026-09-04 it was coupled to {@code options-ci.js} by a comment and
 * nothing else, so adding or removing a threshold there would leave the workflow asserting the old
 * number and reporting a mismatch on a perfectly correct run, or worse, passing a run whose
 * thresholds had quietly shrunk.
 *
 * <p><strong>The second invariant is the non-obvious one.</strong> The workflow passes
 * {@code -e K6_ENV=ci} to select {@code THRESHOLDS_CI}; drop that flag and
 * {@code test/k6/health-check.js} falls back to the local {@code THRESHOLDS} in
 * {@code options.js}, which happens to declare the same number of thresholds. The count check
 * would therefore still pass while the canary silently ran against the stricter local SLO figures
 * on a shared two-vCPU runner. That failure direction is safe -- stricter, not looser -- but it is
 * invisible, and a control whose misconfiguration cannot be seen is the kind this repository keeps
 * turning into a build gate. Asserting the flag is present is what makes it visible.
 *
 * <p><strong>What this test does not do.</strong> It proves the two numbers agree and that the
 * profile flag is present. It does not prove the thresholds are the right thresholds, that
 * {@code health-check.js} really consumes {@code THRESHOLDS_CI} rather than merely being able to
 * (that is JavaScript control flow, not text), or that the workflow step is wired into a job that
 * runs. Those stay reviewer-enforced, the same bound {@code ConditionalOnPropertyHavingValueTest}
 * carries: a declared value is not a sensible one. See EOP-158 and ADR-055.
 */
@DisplayName("The k6 threshold-count constant and the CI options file agree")
class K6ThresholdCouplingTest {

    /** The workflow that carries the hardcoded threshold count and the profile-selecting flag. */
    private static final Path WORKFLOW = Path.of(".github", "workflows", "ci.yml");

    /** The k6 options module whose threshold entries the constant is counting. */
    private static final Path CI_OPTIONS = Path.of("test", "k6", "config", "options-ci.js");

    /**
     * Matches the shell assignment of the expected count. Anchored to the start of a line so a
     * mention of the name inside a comment or an {@code echo} cannot be read as the assignment.
     */
    private static final Pattern EXPECTED_COUNT =
            Pattern.compile("^\\s*EXPECTED_THRESHOLD_COUNT=(\\d+)\\s*$", Pattern.MULTILINE);

    /**
     * Matches one declared threshold. It deliberately requires the object-literal form
     * <code>{ threshold:</code> rather than the bare word, because {@code options-ci.js} discusses
     * thresholds in prose in its header comment and uses {@code threshold} as an object key; a bare
     * word match would count the rationale as configuration.
     */
    private static final Pattern THRESHOLD_ENTRY = Pattern.compile("\\{\\s*threshold\\s*:");

    /** The flag that selects the relaxed CI profile over the local SLO thresholds. */
    private static final String PROFILE_FLAG = "-e K6_ENV=ci";

    /**
     * Anti-vacuity floor, in the style of {@code DeckArithmeticClaimsTest}: if a refactor of
     * {@code options-ci.js} ever defeated the pattern, the count would fall to zero and comparing
     * zero against zero is the one way this test could pass while measuring nothing.
     */
    private static final int MINIMUM_THRESHOLDS = 1;

    @Test
    @DisplayName("the workflow's expected count equals the thresholds declared for CI")
    void shouldAgreeOnTheThresholdCount() throws IOException {
        int declared = countThresholds(Files.readString(CI_OPTIONS));
        int expected = declaredExpectedCount(Files.readString(WORKFLOW));

        assertThat(declared)
                .as(
                        "%s declares %d threshold(s) but %s asserts EXPECTED_THRESHOLD_COUNT=%d. "
                                + "The workflow counts the thresholds k6 reports and fails when the number "
                                + "differs, so these two must move together: update the constant in the same "
                                + "commit as the options file. EOP-158.",
                        CI_OPTIONS, declared, WORKFLOW, expected)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("there is at least one threshold to count")
    void shouldFindThresholdsToCount() throws IOException {
        int declared = countThresholds(Files.readString(CI_OPTIONS));

        assertThat(declared)
                .as(
                        "found %d threshold entries in %s, which is fewer than the floor of %d. Either the "
                                + "thresholds were removed -- in which case the canary asserts nothing -- or the "
                                + "declaration style changed and the matcher no longer recognises it. Both are "
                                + "failures; comparing zero against zero is how this test would pass while "
                                + "measuring nothing.",
                        declared, CI_OPTIONS, MINIMUM_THRESHOLDS)
                .isGreaterThanOrEqualTo(MINIMUM_THRESHOLDS);
    }

    @Test
    @DisplayName("the count is declared exactly once, so nothing can shadow it")
    void shouldDeclareTheCountOnce() throws IOException {
        Matcher matcher = EXPECTED_COUNT.matcher(Files.readString(WORKFLOW));
        int assignments = 0;
        while (matcher.find()) {
            assignments++;
        }

        assertThat(assignments)
                .as(
                        "expected exactly one EXPECTED_THRESHOLD_COUNT assignment in %s but found %d. A "
                                + "second assignment would shadow the first in shell, leaving this test "
                                + "checking a value the workflow does not use.",
                        WORKFLOW, assignments)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the workflow selects the CI threshold profile")
    void shouldSelectTheCiProfile() throws IOException {
        assertThat(Files.readString(WORKFLOW))
                .as(
                        "%s no longer passes '%s' to k6. Without it health-check.js falls back to the local "
                                + "SLO thresholds in options.js, which declare the same number of thresholds -- so "
                                + "the count assertion would still pass while the canary silently ran against "
                                + "figures chosen for a production-like machine on a shared two-vCPU runner. The "
                                + "failure direction is safe but invisible, which is why it is asserted here. "
                                + "EOP-158, ADR-055.",
                        WORKFLOW, PROFILE_FLAG)
                .contains(PROFILE_FLAG);
    }

    @Test
    @DisplayName("both matchers fire on synthetic text")
    void shouldDetectDriftInSyntheticText() {
        String options =
                """
                // Three thresholds are discussed in this comment; the word threshold appears here.
                export const THRESHOLDS_CI = {
                  http_req_duration: [
                    { threshold: "p(95) < 500", abortOnFail: true },
                  ],
                };
                """;
        assertThat(countThresholds(options))
                .as("prose mentioning thresholds must not be counted as configuration")
                .isEqualTo(1);

        assertThat(countThresholds("export const NONE = {};"))
                .as("a file declaring no thresholds must count zero, which the floor then rejects")
                .isZero();

        assertThat(declaredExpectedCount("        run: |\n          EXPECTED_THRESHOLD_COUNT=7\n"))
                .as("the assignment must be parsed out of a workflow body")
                .isEqualTo(7);

        assertThat(declaredExpectedCount("echo \"EXPECTED_THRESHOLD_COUNT=9 is the value\"\n"))
                .as("a mention inside an echo is not an assignment and must not be parsed")
                .isEqualTo(-1);

        assertThat("docker run --rm grafana/k6:2.2.0 run /scripts/health-check.js")
                .as("a docker invocation without the profile flag must not contain it")
                .doesNotContain(PROFILE_FLAG);
    }

    /**
     * Counts declared threshold entries in a k6 options module.
     *
     * @param options the text of the options module
     * @return the number of object literals opening with a {@code threshold} key
     */
    private static int countThresholds(String options) {
        Matcher matcher = THRESHOLD_ENTRY.matcher(options);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    /**
     * Reads the expected threshold count out of a workflow body.
     *
     * @param workflow the text of the workflow file
     * @return the assigned count, or {@code -1} when no assignment is present
     */
    private static int declaredExpectedCount(String workflow) {
        Matcher matcher = EXPECTED_COUNT.matcher(workflow);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }
}
