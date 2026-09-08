package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails the build if the PostgreSQL image the integration tests run against and the image the two Compose
 * stacks run disagree — on the tag, on the digest, or on either being absent.
 *
 * <p><strong>Why this coupling is load-bearing.</strong> Liquibase migrations are verified by
 * {@code PostgresChangelogIT}, {@code PostgresRollbackRoundTripIT} and {@code PostgresSchemaValidationIT}
 * against a Testcontainers-managed PostgreSQL, and the product runs against the PostgreSQL in
 * {@code compose.app.yml}. Those are two independently editable references to what is meant to be one
 * database build. EOP-164 exists because a schema verified on one engine and deployed on another is a defect
 * class this repository has already paid for, and EOP-229 reintroduced it in smaller form: it pinned both
 * Compose references by digest and left the test constant on the bare tag {@code postgres:17-alpine}, whose
 * Javadoc claimed parity with {@code compose.app.yml}. The claim was then false, and falsely reassuring —
 * {@code 17-alpine} is rebuilt upstream, so the suite would have verified the changelog against whatever the
 * registry served that week while the stack deployed a fixed image, with nothing anywhere reporting the
 * divergence.</p>
 *
 * <p><strong>Four references, one image.</strong> The tag and the digest are asserted across every place
 * they are written: the {@code IMAGE} constant in {@code PostgresTestContainer}, the
 * {@code ${POSTGRES_IMAGE:-...}} default in {@code compose.app.yml}, the same default in
 * {@code compose.e2e.yml}, and the {@code tag}/{@code digest} pair recorded for {@code postgres} in
 * {@code tools/supply-chain/expected-containers.json}. All four must name the same image.</p>
 *
 * <p><strong>Why a text gate rather than a runtime assertion.</strong> Comparing the running container's
 * image against the Compose file at test time would need the ITs to parse YAML and would only fire on a
 * machine that can start Docker — which, locally, is a machine with direnv loaded. The divergence is fully
 * decidable from four tracked files, so it is decided in {@code ./mvnw verify} on every commit instead, and
 * on a runner with no container runtime at all.</p>
 *
 * <p><strong>What this test does not do.</strong> It cannot prove the digest is the digest the tag currently
 * resolves to, because that needs the registry — {@code tools/supply-chain/audit-containers.sh} owns that
 * comparison and the {@code supply-chain} job runs it. It cannot prove the image contains PostgreSQL 17, only
 * that all four references name the same one. It deliberately says nothing about
 * {@code compose.e2e.yml}'s application or UI images, which are built from this repository and carry no
 * registry digest to pin (they are declared in that baseline's {@code unpinned_allowlist} instead). And it
 * reads {@code 17-alpine} as data rather than as a literal, so a deliberate major upgrade means editing four
 * files and no test — which is the correct amount of friction, because the audit script will still demand a
 * re-derived digest and a reviewed baseline entry.</p>
 */
@DisplayName("The PostgreSQL image the integration tests use and the one the stacks run must be identical")
class PostgresImagePinTest {

    /** The Testcontainers singleton whose {@code IMAGE} constant the integration tests start. */
    private static final Path TEST_CONTAINER =
            Path.of("src", "test", "java", "org", "maglez", "eop", "migration", "PostgresTestContainer.java");

    /** The deployed stack. Its PostgreSQL is the one the product runs against. */
    private static final Path COMPOSE_APP = Path.of("compose.app.yml");

    /** The end-to-end stack, which must prove the product against the same database build as the deployed one. */
    private static final Path COMPOSE_E2E = Path.of("compose.e2e.yml");

    /** The supply-chain baseline recording every digest-pinned container reference (ADR-064). */
    private static final Path EXPECTED_CONTAINERS = Path.of("tools", "supply-chain", "expected-containers.json");

    /** The image name, written without a tag so every pattern below can demand one. */
    private static final String IMAGE = "postgres";

    /**
     * A fully pinned reference: name, tag, digest. Anchored on the image name so it cannot match the
     * {@code postgres} substring of an unrelated word, and demanding all three parts so a half-pin fails to
     * match at all rather than matching with an empty group.
     */
    private static final Pattern PINNED_REFERENCE = Pattern.compile(
            "\\b" + Pattern.quote(IMAGE) + ":([A-Za-z0-9][A-Za-z0-9._-]*)@(sha256:[0-9a-f]{64})\\b");

    /**
     * Any mention of the image in a context that runs it, pinned or not, so an unpinned one is reported rather
     * than silently skipped. Matches the Compose {@code image:} key and the Java constant's assignment alike.
     */
    private static final Pattern ANY_RUNNABLE_MENTION = Pattern.compile(
            "(?:image:\\s*\\S*|\"\\s*)\\b" + Pattern.quote(IMAGE) + ":[A-Za-z0-9][^\"\\s}]*");

    /** The {@code tag} recorded for this image in the container baseline. */
    private static final Pattern BASELINE_TAG = Pattern.compile(
            "\"" + Pattern.quote(IMAGE) + "\"\\s*:\\s*\\{[^}]*?\"tag\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL);

    /** The {@code digest} recorded for this image in the container baseline. */
    private static final Pattern BASELINE_DIGEST = Pattern.compile(
            "\"" + Pattern.quote(IMAGE) + "\"\\s*:\\s*\\{[^}]*?\"digest\"\\s*:\\s*\"(sha256:[0-9a-f]{64})\"",
            Pattern.DOTALL);

    /**
     * Every file that must carry a fully pinned reference. Held as a list rather than derived, so that adding a
     * fourth place the image is named is a deliberate edit here — the failure mode this gate exists to stop is
     * precisely a new reference nobody remembered to keep in step.
     */
    private static final List<Path> MUST_BE_PINNED = List.of(TEST_CONTAINER, COMPOSE_APP, COMPOSE_E2E);

    @Test
    @DisplayName("every file that runs PostgreSQL pins it by tag and digest, and they all agree")
    void everyRunnableReferenceIsPinnedAndIdentical() throws IOException {
        Map<Path, String> pinned = new LinkedHashMap<>();

        for (Path file : MUST_BE_PINNED) {
            String body = read(file);

            List<String> mentions = allMatches(ANY_RUNNABLE_MENTION, body);
            assertThat(mentions)
                    .as("%s must name the %s image -- it is listed here as a file that runs PostgreSQL, so either it "
                            + "stopped doing so (remove it from MUST_BE_PINNED) or the reference was renamed", file, IMAGE)
                    .isNotEmpty();

            List<String> references = allMatches(PINNED_REFERENCE, body);
            assertThat(references)
                    .as("%s names %s without a digest: %s. A tag is a mutable pointer, so the integration tests and "
                            + "the deployed stack would drift onto different builds of the same tag with nothing "
                            + "reporting it. Write it as '%s:<tag>@sha256:<64 hex>' and derive the digest with "
                            + "'docker buildx imagetools inspect' -- never 'docker inspect', which reports the host "
                            + "platform's child digest on a developer machine (ADR-055)", file, IMAGE, mentions, IMAGE)
                    .hasSameSizeAs(mentions);

            for (String reference : references) {
                pinned.put(file, reference);
                assertThat(reference)
                        .as("%s carries more than one %s reference and they differ; every reference in one file must "
                                + "name the same image", file, IMAGE)
                        .isEqualTo(pinned.get(file));
            }
        }

        assertThat(pinned.values().stream().distinct().toList())
                .as("the %s references disagree: %s. The integration tests verify the Liquibase changelog against the "
                        + "image in %s, and the product runs the image in %s -- when those differ, a schema is verified "
                        + "on one engine and deployed on another, which is the defect class EOP-164 exists to close",
                        IMAGE, pinned, TEST_CONTAINER, COMPOSE_APP)
                .hasSize(1);
    }

    @Test
    @DisplayName("the container baseline records the same tag and digest those files pin")
    void baselineMatchesThePinnedReference() throws IOException {
        String reference = soleReference();
        String tag = reference.substring(reference.indexOf(':') + 1, reference.indexOf('@'));
        String digest = reference.substring(reference.indexOf('@') + 1);
        String baseline = read(EXPECTED_CONTAINERS);

        Matcher recordedTag = BASELINE_TAG.matcher(baseline);
        assertThat(recordedTag.find())
                .as("%s must declare %s with a 'tag' field, so audit-containers.sh can report tag drift against it",
                        EXPECTED_CONTAINERS, IMAGE)
                .isTrue();
        assertThat(recordedTag.group(1))
                .as("%s records the tag '%s' while the pinned references use '%s' -- the baseline is the record of what "
                        + "was reviewed, so it moves in the same commit as the pin", EXPECTED_CONTAINERS,
                        recordedTag.group(1), tag)
                .isEqualTo(tag);

        Matcher recordedDigest = BASELINE_DIGEST.matcher(baseline);
        assertThat(recordedDigest.find())
                .as("%s must declare %s with a 'digest' field", EXPECTED_CONTAINERS, IMAGE)
                .isTrue();
        assertThat(recordedDigest.group(1))
                .as("%s records a different digest for %s than the files that run it. Re-derive it with 'docker buildx "
                        + "imagetools inspect' and update the baseline and every reference together",
                        EXPECTED_CONTAINERS, IMAGE)
                .isEqualTo(digest);
    }

    @Test
    @DisplayName("the container baseline lists every file that pins PostgreSQL as an occurrence")
    void baselineListsEveryPinnedFile() throws IOException {
        String occurrences = occurrencesBlock();

        for (Path file : MUST_BE_PINNED) {
            assertThat(occurrences)
                    .as("%s pins %s but is not listed in that image's 'occurrences' in %s. audit-containers.sh compares "
                            + "occurrences in both directions, so an unlisted file fails that audit -- and every place a "
                            + "digest is written is a place it can go stale, which is why it must be declared",
                            file, IMAGE, EXPECTED_CONTAINERS)
                    .contains(file.toString().replace('\\', '/'));
        }
    }

    /**
     * The single pinned reference all of {@link #MUST_BE_PINNED} agree on.
     *
     * @return the reference, verbatim, in the form {@code postgres:<tag>@sha256:<64 hex>}
     * @throws IOException if any of the files cannot be read
     */
    private String soleReference() throws IOException {
        Matcher first = PINNED_REFERENCE.matcher(read(TEST_CONTAINER));
        assertThat(first.find())
                .as("%s must pin %s by tag and digest", TEST_CONTAINER, IMAGE)
                .isTrue();
        return first.group();
    }

    /**
     * The {@code occurrences} array recorded for {@link #IMAGE} in the container baseline.
     *
     * @return the raw text of the array, brackets included
     * @throws IOException if the baseline cannot be read
     */
    private String occurrencesBlock() throws IOException {
        Pattern block = Pattern.compile(
                "\"" + Pattern.quote(IMAGE) + "\"\\s*:\\s*\\{.*?\"occurrences\"\\s*:\\s*\\[([^\\]]*)\\]",
                Pattern.DOTALL);
        Matcher found = block.matcher(read(EXPECTED_CONTAINERS));
        assertThat(found.find())
                .as("%s must declare %s with an 'occurrences' array", EXPECTED_CONTAINERS, IMAGE)
                .isTrue();
        return found.group(1);
    }

    /**
     * Every match of a pattern in a body of text, in order.
     *
     * @param pattern the pattern to apply
     * @param body    the text to search
     * @return the matched substrings, possibly empty
     */
    private List<String> allMatches(Pattern pattern, String body) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }

    /**
     * Reads a repository file as UTF-8 text.
     *
     * @param path the repository-relative path
     * @return the file's contents
     * @throws IOException if the file cannot be read
     */
    private String read(Path path) throws IOException {
        assertThat(Files.exists(path)).as("%s must exist", path).isTrue();
        return Files.readString(path);
    }
}
