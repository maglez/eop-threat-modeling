package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the coupling between the row shape the {@code e2e-report} CI job writes and the field
 * names the report page reads, and enforces the directory and publication-guard invariants that
 * keep the E2E series isolated from the k6 performance series (EOP-221, ADR-074).
 *
 * <p>The defect this class retro-catches: the first version of {@code tools/e2e/report-page.html}
 * declared {@code var SERIES = ["passed", "failed", "flaky"]} while the workflow wrote
 * {@code expected}/{@code unexpected} (Playwright's own {@code .stats} vocabulary). The page's
 * {@code parseRow} rejects any row missing a numeric value for every {@code SERIES} key, so
 * <em>every row the job would ever write was rejected</em> and the published page would have
 * permanently displayed "Found N line(s) … but none carried valid test result values" with the
 * chart and table hidden. Both files were internally consistent; neither looked wrong in isolation;
 * {@code ./mvnw verify} was green. That is the exact coupling shape this repository already gates
 * elsewhere — see {@code K6ThresholdCouplingTest} (EOP-158) and {@code E2eJobInvariantTest}
 * (EOP-220).
 *
 * <p><strong>What this test does not do.</strong> It proves the field names agree and that the
 * publication guards are present. It does not execute the job, parse JSON, or verify that the
 * page renders correctly in a browser. A green {@code ./mvnw verify} is therefore never evidence
 * that the report page works end-to-end — only that the coupling between the writer and the reader
 * is still shaped the way EOP-221 designed it.
 *
 * <p>This is a plain JUnit test with no Spring context. Surefire runs with the working directory
 * set to the project base directory, so the relative paths resolve.
 */
@DisplayName("The e2e-report job's row shape and the report page agree")
class E2eReportRowShapeTest {

    /** The workflow that contains the {@code e2e-report} job. */
    private static final Path WORKFLOW = Path.of(".github", "workflows", "ci.yml");

    /** The report page that reads the JSONL series the {@code e2e-report} job writes. */
    private static final Path REPORT_PAGE = Path.of("tools", "e2e", "report-page.html");

    /** The name of the job whose row shape and publication guards this test holds. */
    private static final String E2E_REPORT_JOB = "e2e-report";

    /**
     * Matches a job key at two spaces of indent, following the same indentation-based
     * discrimination used by {@code E2eJobInvariantTest}: job keys sit at two spaces, job-level
     * keys at four, step-level keys at eight.
     */
    private static final Pattern JOB_KEY = Pattern.compile("(?m)^ {2}([A-Za-z][\\w-]*):[ \\t]*$");

    /** Matches a step list item: six spaces of indent then a dash. */
    private static final Pattern STEP_START = Pattern.compile("(?m)^ {6}- ");

    /**
     * Matches the {@code SERIES} array declaration in the report page. Captures the content
     * between the square brackets so individual member strings can be extracted.
     */
    private static final Pattern SERIES_ARRAY =
            Pattern.compile("var\\s+SERIES\\s*=\\s*\\[([^\\]]+)\\]");

    /**
     * Matches a quoted string inside the {@code SERIES} array. Used to extract individual
     * member names after the array content has been captured.
     */
    private static final Pattern QUOTED_STRING = Pattern.compile("\"([^\"]+)\"");

    /**
     * Matches a jq object field assignment of the form {@code fieldName: expr} inside the
     * {@code jq -c} row construction in the workflow. The field name must be a bare identifier
     * (no quotes), which is the form the {@code e2e-report} job uses throughout. The jq object
     * literal is indented at sixteen spaces inside the single-quoted shell string.
     *
     * <p>This anchors on the <em>literal indentation of the YAML source text</em>, not on the jq
     * program the YAML encodes, so it is deliberately brittle in one specific direction: a
     * reindent of the workflow that is entirely correct YAML and entirely correct jq will still
     * stop this pattern matching. That failure is loud rather than silent — {@code
     * shouldNotPassVacuously} enforces a floor on the number of fields extracted, so a pattern
     * that matches nothing reddens the build instead of letting the coupling tests pass over an
     * empty set. If the workflow's indentation is ever changed, change the {@code 16} here and
     * the count in that floor together.
     */
    private static final Pattern JQ_FIELD = Pattern.compile("(?m)^ {16}(\\w+):\\s");

    /**
     * Matches a field read of the form {@code r.fieldName} in the report page JavaScript.
     * Anchored to a word boundary on the right so {@code r.sha} does not also match
     * {@code r.sha.slice}.
     */
    private static final Pattern PAGE_FIELD_READ = Pattern.compile("\\br\\.(\\w+)\\b");

    /**
     * Anti-vacuity floor for the number of distinct {@code r.<field>} reads found in the page.
     * The page reads eight distinct fields today ({@code date}, {@code sha}, {@code run_id},
     * {@code expected}, {@code unexpected}, {@code flaky}, {@code skipped}, {@code duration});
     * this floor sits below that so ordinary additions do not redden the build, while still
     * failing loudly if the page is emptied or the pattern no longer matches.
     */
    private static final int MINIMUM_PAGE_FIELD_READS = 5;

    /**
     * Anti-vacuity floor for the number of fields found in the workflow's jq row construction.
     * The job writes eight fields today; this floor sits below that.
     */
    private static final int MINIMUM_JQ_FIELDS = 5;

    @Test
    @DisplayName("every SERIES member is a field the e2e-report job writes")
    void shouldHaveEverySeriesMemberInTheWrittenRow() throws IOException {
        Set<String> writtenFields = jqRowFields(workflowJobBlock(E2E_REPORT_JOB).orElse(""));
        List<String> seriesMembers = pageSeriesMembers(Files.readString(REPORT_PAGE));

        List<String> missing = seriesMembers.stream()
                .filter(member -> !writtenFields.contains(member))
                .toList();

        assertThat(missing)
                .as("""
                        The page's SERIES array contains member(s) that the e2e-report job does not write \
                        into its row: %s.

                        SERIES drives parseRow: it rejects any row missing a numeric value for every SERIES \
                        key. A mismatch means every row the job writes is rejected and the page permanently \
                        displays "Found N line(s) but none carried valid test result values" with the chart \
                        and table hidden. This is the exact defect EOP-221 caught during delivery: the page \
                        declared ["passed","failed","flaky"] while the job wrote expected/unexpected \
                        (Playwright's own .stats vocabulary). Change SERIES to match the job's jq output, \
                        or change the jq output to match SERIES -- never both independently. \
                        Written fields: %s. SERIES: %s."""
                        .formatted(missing, writtenFields, seriesMembers))
                .isEmpty();
    }

    @Test
    @DisplayName("every field the page reads is a field the e2e-report job writes")
    void shouldHaveEveryPageReadFieldInTheWrittenRow() throws IOException {
        Set<String> writtenFields = jqRowFields(workflowJobBlock(E2E_REPORT_JOB).orElse(""));
        Set<String> pageReads = pageFieldReads(Files.readString(REPORT_PAGE));

        List<String> missing = pageReads.stream()
                .filter(field -> !writtenFields.contains(field))
                .toList();

        assertThat(missing)
                .as("""
                        The page reads field(s) via r.<field> that the e2e-report job does not write: %s.

                        This is strictly stronger than the SERIES check: it catches a typo in any cell \
                        rendered by the table or chart, not just the chart series keys. A field the page \
                        reads but the job never writes arrives as `undefined`, which the page renders as \
                        an em-dash or silently treats as zero -- a silent data loss rather than a loud \
                        failure. Written fields: %s. Page reads: %s."""
                        .formatted(missing, writtenFields, pageReads))
                .isEmpty();
    }

    @Test
    @DisplayName("the page fetches from its own directory, not the parent")
    void shouldFetchFromOwnDirectory() throws IOException {
        String page = Files.readString(REPORT_PAGE);

        assertThat(page)
                .as("""
                        %s must fetch "./e2e-history.jsonl" (relative to its own directory), not \
                        "../e2e-history.jsonl".

                        The page is served from the e2e/ subdirectory of the perf-history branch. \
                        Resolving "../" from there lands on the branch ROOT, which is the k6 perf-trend \
                        page's directory -- a different measurement population that this series must never \
                        read from or write to (EOP-221, ADR-074). The two series are deliberately \
                        isolated: mixing them would show step changes caused by infrastructure differences \
                        rather than by code.""", REPORT_PAGE)
                .contains("./e2e-history.jsonl");

        assertThat(page)
                .as("""
                        %s must not fetch "../e2e-history.jsonl".

                        Resolving "../" from e2e/ on the perf-history branch lands on the branch root, \
                        which is the k6 perf-trend page's directory. Reading from there would silently \
                        mix two unrelated measurement populations (EOP-221, ADR-074).""", REPORT_PAGE)
                .doesNotContain("../e2e-history.jsonl");
    }

    @Test
    @DisplayName("the e2e-report job writes inside e2e/, not the branch root")
    void shouldWriteInsideE2eDirectory() throws IOException {
        String jobBlock = workflowJobBlock(E2E_REPORT_JOB).orElse("");

        assertThat(jobBlock)
                .as("""
                        The e2e-report job must reference "e2e/e2e-history.jsonl" (inside the e2e/ \
                        subdirectory of the perf-history branch), not a bare "e2e-history.jsonl" at \
                        the branch root.

                        The branch root is owned by the perf-trend job (ci-history.jsonl and index.html \
                        live there). Writing the E2E series to the root would put two unrelated \
                        measurement populations in one directory, which is what ADR-074 exists to \
                        prevent. The two jobs write disjoint paths so they can run concurrently without \
                        a merge conflict.""")
                .contains("e2e/e2e-history.jsonl");

        assertThat(jobBlock)
                .as("""
                        The e2e-report job must not git-add a bare "e2e-history.jsonl" at the branch root.

                        The job must stage only paths under e2e/ (e2e/e2e-history.jsonl, e2e/index.html). \
                        A bare path would write into the branch root, which the perf-trend job owns, \
                        and would silently overwrite or conflict with the k6 series (ADR-074).""")
                .doesNotContain("git add e2e-history.jsonl")
                .doesNotContain("add -- e2e-history.jsonl");
    }

    @Test
    @DisplayName("the e2e-report job never publishes a failing, PR or nightly run")
    void shouldNeverPublishAFailingOrNonPushRun() throws IOException {
        String condition = workflowJobLevelValue(E2E_REPORT_JOB, "if").orElse("");

        assertThat(condition)
                .as("""
                        The e2e-report job's job-level `if:` must contain \
                        "github.event_name == 'push'" to exclude workflow_dispatch and schedule \
                        triggers. Both report refs/heads/main, so the ref test alone is not \
                        sufficient: a scheduled re-run of unchanged code would otherwise append a \
                        duplicate point for a commit already in the series. Found instead: %s"""
                        .formatted(condition))
                .contains("github.event_name == 'push'");

        assertThat(condition)
                .as("""
                        The e2e-report job's job-level `if:` must contain \
                        "github.ref == 'refs/heads/main'" to restrict publication to the main branch. \
                        Found instead: %s""".formatted(condition))
                .contains("github.ref == 'refs/heads/main'");

        assertThat(condition)
                .as("""
                        The e2e-report job's job-level `if:` must not contain always().

                        always() disables the implicit `needs` success requirement, which means a \
                        failing e2e run would still be published to the report series. The series \
                        must only ever record passing runs: a failure point would permanently skew \
                        the chart and mislead anyone reading the trend. Found instead: %s"""
                        .formatted(condition))
                .doesNotContain("always()");

        assertThat(condition)
                .as("""
                        The e2e-report job's job-level `if:` must not contain failure().

                        failure() would cause the job to run specifically when the e2e suite failed, \
                        which is the opposite of the intended behaviour. Found instead: %s"""
                        .formatted(condition))
                .doesNotContain("failure()");
    }

    @Test
    @DisplayName("cannot pass vacuously -- the job block and parsed arrays must be non-empty")
    void shouldNotPassVacuously() throws IOException {
        Optional<String> jobBlock = workflowJobBlock(E2E_REPORT_JOB);

        assertThat(jobBlock)
                .as("""
                        The '%s' job was not found in %s, so every other rule in this class silently \
                        matched nothing and passed.

                        If the job was renamed, rename E2E_REPORT_JOB here in the same commit. If it \
                        was removed, delete this test rather than leaving it green over an absence -- \
                        a guard that cannot fire is worse than no guard, because it reads as coverage."""
                        .formatted(E2E_REPORT_JOB, WORKFLOW))
                .isPresent();

        List<String> seriesMembers = pageSeriesMembers(Files.readString(REPORT_PAGE));

        assertThat(seriesMembers)
                .as("""
                        The SERIES array in %s was parsed as empty.

                        Either the array was removed, or the declaration style changed and the pattern \
                        no longer recognises it. An empty SERIES means parseRow accepts every row \
                        regardless of its shape, which is the opposite of the validation it is supposed \
                        to provide -- and the rule-1 check above would pass by comparing two empty \
                        sets.""", REPORT_PAGE)
                .isNotEmpty();

        Set<String> writtenFields = jqRowFields(jobBlock.orElse(""));

        assertThat(writtenFields.size())
                .as("""
                        The jq row construction in the '%s' job yielded fewer than %d fields (found %d).

                        Either the jq block was removed, or the indentation changed and the pattern no \
                        longer recognises it. A floor of %d sits well below the eight fields the job \
                        writes today, so ordinary edits do not redden the build -- but if the count \
                        falls this low the rules above are comparing against an empty or near-empty set \
                        and passing vacuously."""
                        .formatted(E2E_REPORT_JOB, MINIMUM_JQ_FIELDS, writtenFields.size(), MINIMUM_JQ_FIELDS))
                .isGreaterThanOrEqualTo(MINIMUM_JQ_FIELDS);

        Set<String> pageReads = pageFieldReads(Files.readString(REPORT_PAGE));

        assertThat(pageReads.size())
                .as("""
                        The page field reads (r.<field>) in %s yielded fewer than %d distinct fields \
                        (found %d).

                        Either the reads were removed, or the pattern no longer matches. A floor of %d \
                        sits below the eight fields the page reads today. If the count falls this low \
                        the rule-2 check above is comparing against a near-empty set and passing \
                        vacuously."""
                        .formatted(REPORT_PAGE, MINIMUM_PAGE_FIELD_READS, pageReads.size(), MINIMUM_PAGE_FIELD_READS))
                .isGreaterThanOrEqualTo(MINIMUM_PAGE_FIELD_READS);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the text of one job from the workflow, from its two-space-indented key to the next
     * job key or the end of the file. Follows the same indentation-based approach as
     * {@code E2eJobInvariantTest#jobBlock}.
     *
     * @param jobName the job key to find, without indentation or colon
     * @return the job's block including its key line, or empty if no such job exists
     */
    private static Optional<String> workflowJobBlock(String jobName) {
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
     * Reads a job-level key's single-line value, distinguished from a step-level key of the same
     * name purely by indentation: job-level keys sit at four spaces, step-level keys at eight.
     *
     * @param jobName the job to read from
     * @param key the job-level key, such as {@code if} or {@code needs}
     * @return the raw value text with surrounding whitespace stripped, or empty if the key is absent
     */
    private static Optional<String> workflowJobLevelValue(String jobName, String key) {
        return workflowJobBlock(jobName).flatMap(block -> {
            Matcher matcher = Pattern.compile("(?m)^ {4}" + Pattern.quote(key) + ":[ \\t]*(.+)$")
                    .matcher(block);
            return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
        });
    }

    /**
     * Extracts the field names from the jq row construction inside a job block. Matches bare
     * identifier keys at sixteen spaces of indent (the indentation level used by the
     * {@code e2e-report} job's inline jq object literal).
     *
     * @param jobBlock the text of the job block to search
     * @return the set of field names found, in encounter order
     */
    private static Set<String> jqRowFields(String jobBlock) {
        Set<String> fields = new LinkedHashSet<>();
        Matcher matcher = JQ_FIELD.matcher(jobBlock);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    /**
     * Parses the {@code SERIES} array from the report page JavaScript.
     *
     * @param pageContent the full text of the report page
     * @return the list of member strings, in declaration order
     */
    private static List<String> pageSeriesMembers(String pageContent) {
        Matcher arrayMatcher = SERIES_ARRAY.matcher(pageContent);
        if (!arrayMatcher.find()) {
            return List.of();
        }
        String arrayContent = arrayMatcher.group(1);
        List<String> members = new ArrayList<>();
        Matcher stringMatcher = QUOTED_STRING.matcher(arrayContent);
        while (stringMatcher.find()) {
            members.add(stringMatcher.group(1));
        }
        return members;
    }

    /**
     * Collects all distinct field names read via {@code r.<field>} in the report page JavaScript.
     * Excludes method calls (e.g. {@code r.sha.slice}) by matching only the first identifier
     * after {@code r.}.
     *
     * @param pageContent the full text of the report page
     * @return the set of field names, in encounter order
     */
    private static Set<String> pageFieldReads(String pageContent) {
        Set<String> fields = new LinkedHashSet<>();
        Matcher matcher = PAGE_FIELD_READ.matcher(pageContent);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
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
