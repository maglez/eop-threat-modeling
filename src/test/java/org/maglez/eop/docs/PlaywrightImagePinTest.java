package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails the build if the pinned {@code mcr.microsoft.com/playwright} container and the
 * {@code @playwright/test} npm package disagree about which Playwright release the end-to-end suite runs.
 *
 * <p><strong>Why this coupling is load-bearing.</strong> Since EOP-236 the {@code e2e} job no longer runs
 * {@code npx playwright install --with-deps}. The browsers arrive inside a digest-pinned container and the
 * test runner arrives from {@code e2e/node_modules}, so two independently editable files decide which
 * Playwright is in play. Playwright refuses to start when they diverge — it looks for a browser revision
 * recorded in the runner's own {@code browsers.json} and reports that the executable does not exist. That
 * failure surfaces only in a job which does not run on pull requests, so the divergence would be merged
 * first and discovered afterwards on {@code main}. This gate moves the discovery to {@code ./mvnw verify}.</p>
 *
 * <p><strong>Four files, one version.</strong> The version is asserted across every place it is written:
 * the declared range in {@code e2e/package.json}, the range recorded in the lockfile's root package entry,
 * the resolved version of {@code node_modules/@playwright/test} in that same lockfile, and the image tag as
 * it appears both in {@code .github/workflows/ci.yml} and in {@code tools/supply-chain/expected-containers.json}.
 * All of them must name the same {@code MAJOR.MINOR.PATCH}.</p>
 *
 * <p><strong>Why a text gate rather than a build step.</strong> Nothing in Maven's reach can start the
 * container or resolve the npm tree, and the alternative — discovering the mismatch when Playwright fails to
 * launch — costs a full post-merge {@code e2e} run to learn something four files already state in plain text.
 * The check is also the reason the declared range must be exact: a caret would let {@code npm install}
 * silently move the runner off the pinned image while every file here still read as consistent.</p>
 *
 * <p><strong>What this test does not do.</strong> It cannot prove the digest beside the tag is the digest
 * that tag resolves to, because that needs the network — {@code tools/supply-chain/audit-containers.sh}
 * owns that comparison and the {@code supply-chain} job runs it. It cannot prove the image actually contains
 * the browser revisions the runner wants, only that both name the same release. It says nothing about
 * {@code @types/node} or {@code typescript}, which are exact-pinned for reproducibility rather than because
 * anything couples them to an image. And it reads the tag's {@code -noble} suffix as a literal: moving to a
 * different Ubuntu base means editing this test, which is the intended prompt to think about it.</p>
 */
@DisplayName("The pinned Playwright container and the @playwright/test package must name the same release")
class PlaywrightImagePinTest {

    /** The workflow that runs the end-to-end suite inside the pinned container. */
    private static final Path WORKFLOW = Path.of(".github", "workflows", "ci.yml");

    /** The manifest declaring which {@code @playwright/test} the end-to-end project wants. */
    private static final Path E2E_PACKAGE_JSON = Path.of("e2e", "package.json");

    /** The lockfile recording which {@code @playwright/test} the end-to-end project actually installs. */
    private static final Path E2E_LOCKFILE = Path.of("e2e", "package-lock.json");

    /** The supply-chain baseline recording the digest-pinned container references (ADR-064). */
    private static final Path EXPECTED_CONTAINERS = Path.of("tools", "supply-chain", "expected-containers.json");

    /**
     * The image whose tag carries the Playwright version. Written without a tag so the patterns below can
     * demand one.
     */
    private static final String IMAGE = "mcr.microsoft.com/playwright";

    /**
     * Every {@code mcr.microsoft.com/playwright:vX.Y.Z-noble@sha256:...} reference, capturing the version and
     * the digest separately so both can be asserted.
     */
    private static final Pattern WORKFLOW_REFERENCE = Pattern.compile(
            Pattern.quote(IMAGE) + ":v(\\d+\\.\\d+\\.\\d+)-noble@(sha256:[0-9a-f]{64})");

    /** Any reference to the image at all, pinned or not, so an unpinned one can be reported rather than skipped. */
    private static final Pattern ANY_WORKFLOW_MENTION = Pattern.compile(Pattern.quote(IMAGE) + "\\S*");

    /** The {@code "@playwright/test": "1.63.0"} form, capturing whatever range or version is written. */
    private static final Pattern PLAYWRIGHT_SPEC = Pattern.compile("\"@playwright/test\"\\s*:\\s*\"([^\"]+)\"");

    /** The lockfile entry for the installed package, capturing its resolved {@code version}. */
    private static final Pattern LOCKFILE_RESOLVED_VERSION = Pattern.compile(
            "\"node_modules/@playwright/test\"\\s*:\\s*\\{[^}]*?\"version\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL);

    /** The {@code tag} recorded for this image in the container baseline. */
    private static final Pattern BASELINE_TAG = Pattern.compile(
            "\"" + Pattern.quote(IMAGE) + "\"\\s*:\\s*\\{[^}]*?\"tag\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL);

    /**
     * The workflow must reference the image at least once, so this test cannot pass by finding nothing. One
     * rather than a larger floor because a single {@code docker run} is the whole mechanism — the digest is
     * deliberately written once, and a second copy would be drift rather than coverage.
     */
    private static final int MINIMUM_WORKFLOW_REFERENCES = 1;

    /** Range operators that would let the installed version drift away from the pinned image. */
    private static final List<String> RANGE_OPERATORS = List.of("^", "~", ">", "<", "=", "*", "x", "|", " ");

    @Test
    @DisplayName("e2e/package.json pins @playwright/test to an exact version, not a range")
    void declaredVersionIsExact() throws IOException {
        String declared = declaredPlaywrightSpec();

        assertThat(declared)
                .as("e2e/package.json must pin @playwright/test exactly, because the container tag in %s is "
                        + "chosen to match it and a range lets npm move the runner off the pinned image", WORKFLOW)
                .matches("\\d+\\.\\d+\\.\\d+");
        assertThat(RANGE_OPERATORS.stream().noneMatch(declared::contains))
                .as("e2e/package.json declares @playwright/test as '%s', which carries a range operator", declared)
                .isTrue();
    }

    @Test
    @DisplayName("the lockfile agrees with e2e/package.json on the declared range and the resolved version")
    void lockfileAgreesWithManifest() throws IOException {
        String declared = declaredPlaywrightSpec();
        String lockfile = read(E2E_LOCKFILE);

        Matcher rootSpec = PLAYWRIGHT_SPEC.matcher(lockfile);
        assertThat(rootSpec.find())
                .as("%s records no @playwright/test range; regenerate it with 'npm install --package-lock-only' in e2e/",
                        E2E_LOCKFILE)
                .isTrue();
        assertThat(rootSpec.group(1))
                .as("%s records the range '%s' while %s declares '%s' -- a stale lockfile root entry is rewritten by "
                        + "the next 'npm install' and silently reintroduces the drift this gate exists to stop",
                        E2E_LOCKFILE, rootSpec.group(1), E2E_PACKAGE_JSON, declared)
                .isEqualTo(declared);

        Matcher resolved = LOCKFILE_RESOLVED_VERSION.matcher(lockfile);
        assertThat(resolved.find())
                .as("%s has no 'node_modules/@playwright/test' entry to read a resolved version from", E2E_LOCKFILE)
                .isTrue();
        assertThat(resolved.group(1))
                .as("%s resolves @playwright/test to %s while %s declares %s", E2E_LOCKFILE, resolved.group(1),
                        E2E_PACKAGE_JSON, declared)
                .isEqualTo(declared);
    }

    @Test
    @DisplayName("every Playwright image reference in the workflow carries the matching version and a digest")
    void workflowReferencesMatchTheDeclaredVersion() throws IOException {
        String declared = declaredPlaywrightSpec();
        String workflow = read(WORKFLOW);

        List<String> mentions = new ArrayList<>();
        Matcher anyMention = ANY_WORKFLOW_MENTION.matcher(workflow);
        while (anyMention.find()) {
            mentions.add(anyMention.group());
        }
        assertThat(mentions)
                .as("%s must reference %s -- the end-to-end suite runs inside it", WORKFLOW, IMAGE)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_WORKFLOW_REFERENCES);

        List<String> pinned = new ArrayList<>();
        Matcher reference = WORKFLOW_REFERENCE.matcher(workflow);
        while (reference.find()) {
            pinned.add(reference.group());
            assertThat(reference.group(1))
                    .as("%s runs %s:v%s-noble while %s declares @playwright/test %s -- Playwright will refuse to "
                            + "start, reporting that the browser executable does not exist", WORKFLOW, IMAGE,
                            reference.group(1), E2E_PACKAGE_JSON, declared)
                    .isEqualTo(declared);
        }
        assertThat(pinned)
                .as("every %s reference in %s must be written ':v<MAJOR.MINOR.PATCH>-noble@sha256:<64 hex>'; found %s. "
                        + "The digest is what makes this a pin -- a tag alone can be republished, and Playwright does "
                        + "not verify what the browser CDN returns", IMAGE, WORKFLOW, mentions)
                .hasSameSizeAs(mentions);
    }

    @Test
    @DisplayName("the container baseline records the same tag the workflow uses")
    void baselineTagMatchesTheDeclaredVersion() throws IOException {
        String declared = declaredPlaywrightSpec();

        Matcher tag = BASELINE_TAG.matcher(read(EXPECTED_CONTAINERS));
        assertThat(tag.find())
                .as("%s must declare %s with a 'tag' field, so audit-containers.sh can report tag drift", EXPECTED_CONTAINERS, IMAGE)
                .isTrue();
        assertThat(tag.group(1))
                .as("%s records the tag '%s' while %s declares @playwright/test %s -- the baseline is the record of "
                        + "what was reviewed, so it moves in the same commit as the pin", EXPECTED_CONTAINERS,
                        tag.group(1), E2E_PACKAGE_JSON, declared)
                .isEqualTo("v" + declared + "-noble");
    }

    /**
     * Reads the {@code @playwright/test} specifier declared in {@code e2e/package.json}.
     *
     * @return the declared specifier, verbatim
     * @throws IOException if the manifest cannot be read
     */
    private String declaredPlaywrightSpec() throws IOException {
        Matcher declared = PLAYWRIGHT_SPEC.matcher(read(E2E_PACKAGE_JSON));
        assertThat(declared.find())
                .as("%s must declare @playwright/test -- it is the end-to-end test runner", E2E_PACKAGE_JSON)
                .isTrue();
        return declared.group(1);
    }

    /**
     * Reads a repository file as UTF-8 text.
     *
     * @param path the path, relative to the repository root
     * @return the file contents
     * @throws IOException if the file cannot be read
     */
    private String read(Path path) throws IOException {
        assertThat(Files.exists(path)).as("%s must exist", path).isTrue();
        return Files.readString(path);
    }
}
