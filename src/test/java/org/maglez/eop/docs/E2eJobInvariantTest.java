package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the two lines on which the {@code e2e} CI job's whole design rests, plus the artefact name that couples it to the
 * {@code image} job that feeds it.
 *
 * <p>EOP-220 added an end-to-end Playwright job to {@code .github/workflows/ci.yml}. Two of its lines carry the design and
 * neither was checked by anything before this test:
 *
 * <ul>
 *   <li>{@code if: github.event_name != 'pull_request'} is the <em>entire</em> mechanism by which the suite does not block a
 *       merge. It is not a policy and not a branch-protection setting -- the job simply never runs on a pull request, so there
 *       is no check for an administrator to mark required. Delete or widen that one line and the slowest job in the pipeline
 *       silently becomes a gate on every pull request.</li>
 *   <li>{@code needs: [ image ]} supplies both the sequencing and the skip semantics. GitHub skips a job whose dependency
 *       failed, and because the {@code if:} tests only the event name -- no {@code always()}, no {@code failure()} -- a broken
 *       build reports {@code e2e} as <em>skipped</em> rather than <em>failed</em>. Adding {@code always()} there would turn
 *       every red build into two red jobs and lose the distinction between "the suite failed" and "the suite never got an
 *       artefact to run against".</li>
 * </ul>
 *
 * <p><strong>This test reads the workflow as text. It does not execute the job, start a container, or run a single Playwright
 * spec.</strong> A green {@code ./mvnw verify} is therefore never evidence that the end-to-end suite ran or passed -- only that
 * the wiring which decides <em>when</em> it runs is still shaped the way EOP-220 designed it. The suite itself runs on a push
 * to {@code main}, on the nightly schedule and on manual dispatch, and its result is visible in the run summary rather than here.
 *
 * <p><strong>What this test does not cover.</strong> The roughly eighty lines of inline bash and {@code jq} in the {@code e2e}
 * job's run-summary step are unexecuted by any Java test. Their {@code jq} expressions were validated by hand against a real
 * {@code e2e/results.json} and against a synthetic failing mutation during EOP-220, and that remains the only evidence they
 * work. Closing that gap needs a shell-level test rather than a text rule, and is deliberately out of scope here. Neither does
 * this test check the {@code image} job's own {@code if: github.event_name != 'pull_request'} guards on its save and upload
 * steps: removing those merely wastes runner time uploading an artefact nobody downloads, whereas removing the {@code e2e}
 * job's {@code if:} is the dangerous direction, and the first rule below already catches that.
 *
 * <p>One deliberate conservatism: the {@code if:} and {@code needs:} rules read a single-line value. Rewriting either as a
 * folded or literal YAML block would fail this gate rather than be understood, which is the intended behaviour -- it forces the
 * author to confirm by hand that the invariant still holds instead of having a reshaped expression pass unread.
 *
 * @see E2eArtefactPublicationBoundaryTest for the companion rule on which artefacts the workflow may publish
 */
@DisplayName("The e2e CI job's load-bearing invariants")
class E2eJobInvariantTest {

    /** The workflow that carries every CI job, read as text rather than parsed as YAML. */
    private static final Path WORKFLOW = Path.of(".github", "workflows", "ci.yml");

    /** The job whose invariants this test holds. */
    private static final String E2E_JOB = "e2e";

    /** The job that builds and uploads the container images the {@code e2e} job loads. */
    private static final String IMAGE_JOB = "image";

    /** The artifact name by which the two jobs above agree to hand over the built images. */
    private static final String ARTEFACT_NAME = "ci-images";

    /**
     * Matches the artefact name as a whole YAML value rather than as a substring. The anchored {@code $} is load-bearing: a
     * plain {@code contains(ARTEFACT_NAME)} check passes for {@code ci-images-renamed}, because that string contains this one,
     * so a rename which merely <em>extends</em> the name would have slipped through the very rule written to catch it. Measured,
     * not theorised -- an unanchored first draft of this rule survived exactly that mutation.
     */
    private static final Pattern ARTEFACT_VALUE =
            Pattern.compile("(?m)^[ \\t]*name:[ \\t]*" + Pattern.quote(ARTEFACT_NAME) + "[ \\t]*$");

    /**
     * A vacuity floor, not a step-count coupling. The {@code e2e} job has fifteen steps today; this floor sits well below that
     * so ordinary step edits do not redden the build, while still failing loudly if the job is renamed, emptied or removed and
     * the rules above start matching nothing. {@code K6ThresholdCouplingTest} deliberately couples a count tightly because the
     * two numbers it compares must be equal; here there is no second number to agree with, so a tight count would buy nothing
     * and cost a build failure on every legitimate step addition.
     */
    private static final int MINIMUM_E2E_JOB_STEPS = 10;

    /**
     * Matches a key at two spaces of indent: a name, a colon, end of line. Indentation is the discriminator throughout this
     * test -- job keys sit at two spaces, job-level keys at four, step list items at six and step-level keys at eight -- which
     * is what keeps a job-level {@code if:} from being confused with the six step-level {@code if: always()} lines in this job.
     *
     * <p>Two spaces of indent is not by itself unique to a job, and the pattern is deliberately not claimed to be: the
     * {@code on:} block's trigger keys ({@code push:}, {@code schedule:}, {@code workflow_dispatch:}) sit at the same depth.
     * That costs nothing here because {@link #jobBlock(String)} is only ever asked for a job by name and neither {@code e2e}
     * nor {@code image} is a trigger name -- but a job named after a trigger would be shadowed by the earlier match, so look
     * up a job by name rather than treating a match on this pattern as proof that a job was found.
     */
    private static final Pattern JOB_KEY = Pattern.compile("(?m)^ {2}([A-Za-z][\\w-]*):[ \\t]*$");

    /** Matches a step list item: six spaces of indent then a dash, which is where a step begins inside a job's {@code steps:}. */
    private static final Pattern STEP_START = Pattern.compile("(?m)^ {6}- ");

    @Test
    @DisplayName("keeps the end-to-end suite off pull requests")
    void shouldKeepTheEndToEndSuiteOffPullRequests() {
        String condition = jobLevelValue(E2E_JOB, "if").orElse("");

        assertThat(condition)
                .as("""
                        The e2e job's job-level `if:` must exclude the pull_request event.

                        That single line is the entire mechanism by which the end-to-end suite does not block a merge. It is not \
                        a policy and not a branch-protection setting: the job never runs on a pull request, so there is no check \
                        for an administrator to mark required. Widen or delete it and the slowest job in the pipeline -- a full \
                        container stack plus fifteen Playwright specs across three browsers -- silently gates every pull \
                        request. If you meant to change when the suite runs, amend ADR-072 first and say so there. Found \
                        instead: %s""".formatted(condition))
                .contains("pull_request")
                .contains("!=");
    }

    @Test
    @DisplayName("keeps always() and failure() out of the end-to-end job's condition")
    void shouldKeepAlwaysAndFailureOutOfTheEndToEndJobsCondition() {
        String condition = jobLevelValue(E2E_JOB, "if").orElse("");

        assertThat(condition)
                .as("""
                        The e2e job's job-level `if:` must not contain always() or failure().

                        The job carries `needs: [ image ]`, and GitHub skips a job whose dependency failed. Because this `if:` \
                        tests only the event name, a broken build reports e2e as *skipped* rather than *failed* -- which is the \
                        honest signal, since the suite never got an artefact to run against. Adding always() turns every red \
                        build into two red jobs and destroys the distinction between "the suite failed" and "the suite never \
                        ran". Found instead: %s""".formatted(condition))
                .doesNotContain("always()")
                .doesNotContain("failure()");
    }

    @Test
    @DisplayName("keeps the end-to-end job dependent on the image job")
    void shouldKeepTheEndToEndJobDependentOnTheImageJob() {
        String needs = jobLevelValue(E2E_JOB, "needs").orElse("");

        assertThat(needs)
                .as("""
                        The e2e job's job-level `needs:` must contain the image job.

                        It supplies two things at once. Sequencing: the suite loads the container images the image job builds, \
                        so running the two concurrently would race. And skip semantics: GitHub skips a job whose dependency \
                        failed, which is what makes a broken build report e2e as skipped rather than failed. Drop this and the \
                        suite starts against images that do not exist yet, failing for a reason that has nothing to do with the \
                        product. Found instead: %s""".formatted(needs))
                .contains(IMAGE_JOB);
    }

    @Test
    @DisplayName("keeps the artefact name agreed between the job that uploads it and the job that downloads it")
    void shouldKeepTheArtefactNameAgreedBetweenProducerAndConsumer() {
        List<String> offences = new ArrayList<>();

        boolean uploaded = steps(IMAGE_JOB).stream()
                .anyMatch(step -> step.contains("actions/upload-artifact") && ARTEFACT_VALUE.matcher(step).find());
        boolean downloaded = steps(E2E_JOB).stream()
                .anyMatch(step -> step.contains("actions/download-artifact") && ARTEFACT_VALUE.matcher(step).find());

        if (!uploaded) {
            offences.add("no step in the '%s' job uploads an artifact named '%s'".formatted(IMAGE_JOB, ARTEFACT_NAME));
        }
        if (!downloaded) {
            offences.add("no step in the '%s' job downloads an artifact named '%s'".formatted(E2E_JOB, ARTEFACT_NAME));
        }

        assertThat(offences)
                .as("""
                        The image job uploads the built containers under one artifact name and the e2e job downloads them under \
                        the same name. Nothing but agreement on that string connects them.

                        Rename it on one side only and CI stays green until the e2e job runs, where it fails while reaching for \
                        an artefact nobody produced -- and it fails on main after the merge, not on the pull request that broke \
                        it, because the job does not run on pull requests at all. Change both sides together.""")
                .isEmpty();
    }

    @Test
    @DisplayName("cannot pass by matching nothing")
    void shouldNotPassVacuously() {
        assertThat(jobBlock(E2E_JOB))
                .as("""
                        The '%s' job was not found in %s, so every other rule in this class silently matched nothing and passed.

                        If the job was renamed, rename E2E_JOB here in the same commit. If it was removed, delete this test \
                        rather than leaving it green over an absence -- a guard that cannot fire is worse than no guard, because \
                        it reads as coverage.""".formatted(E2E_JOB, WORKFLOW))
                .isPresent();

        assertThat(jobBlock(IMAGE_JOB))
                .as("The '%s' job was not found in %s, so the artefact-handover rule matched nothing.".formatted(IMAGE_JOB, WORKFLOW))
                .isPresent();

        assertThat(steps(E2E_JOB))
                .as("""
                        The '%s' job was found but holds fewer than %d steps, so it is no longer the job these rules were \
                        written against. This is a vacuity floor rather than a step count: it sits well below the fifteen steps \
                        the job carries today, so ordinary edits do not redden the build.""".formatted(E2E_JOB, MINIMUM_E2E_JOB_STEPS))
                .hasSizeGreaterThanOrEqualTo(MINIMUM_E2E_JOB_STEPS);
    }

    /**
     * Extracts the text of one job, from its two-space-indented key to the next job key or the end of the file.
     *
     * @param jobName the job key to find, without indentation or colon
     * @return the job's block including its key line, or empty if no such job exists
     */
    private static Optional<String> jobBlock(String jobName) {
        String workflow = workflow();
        Matcher matcher = JOB_KEY.matcher(workflow);
        while (matcher.find()) {
            if (!matcher.group(1).equals(jobName)) {
                continue;
            }
            int start = matcher.start();
            int end = matcher.find() ? matcher.start() : workflow.length();
            return Optional.of(workflow.substring(start, end));
        }
        return Optional.empty();
    }

    /**
     * Reads a job-level key's single-line value, distinguished from a step-level key of the same name purely by indentation:
     * job-level keys sit at four spaces, step-level keys at eight.
     *
     * @param jobName the job to read from
     * @param key the job-level key, such as {@code if} or {@code needs}
     * @return the raw value text with surrounding whitespace stripped, or empty if the key is absent
     */
    private static Optional<String> jobLevelValue(String jobName, String key) {
        return jobBlock(jobName).flatMap(block -> {
            Matcher matcher = Pattern.compile("(?m)^ {4}" + Pattern.quote(key) + ":[ \\t]*(.+)$").matcher(block);
            return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
        });
    }

    /**
     * Splits a job's {@code steps:} list into one string per step, so a rule can require two facts to hold on the same step
     * rather than merely somewhere in the job.
     *
     * @param jobName the job whose steps to split
     * @return one entry per step, empty if the job is absent or has no steps
     */
    private static List<String> steps(String jobName) {
        return jobBlock(jobName)
                .map(block -> Arrays.stream(STEP_START.split(block)).skip(1).toList())
                .orElseGet(List::of);
    }

    /**
     * @return the workflow file as text
     */
    private static String workflow() {
        try {
            return Files.readString(WORKFLOW);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + WORKFLOW, e);
        }
    }
}
