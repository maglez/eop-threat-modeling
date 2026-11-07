package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;


/**
 * Fails the build when the application's HTTP surface drifts from the hand-authored contract in
 * {@code docs/api/openapi.yml} (EOP-95, ADR-054).
 *
 * <h2>The defect this replaces</h2>
 *
 * <p>This project is contract-first by rule: {@code .opencode/rules/api-design.md} requires
 * {@code docs/api/openapi.yml} to be hand-authored before an endpoint is implemented, and the file's own
 * header says "when an endpoint changes, this file changes in the same commit". Nothing checked that.
 * EOP-72 changed observable dealing behaviour and left the contract untouched; the divergence survived a
 * whole story with a green build and was found by a manual audit (EOP-83), not by the suite. Three
 * documents were stale at once. The Documentation Gate — a human reading a diff — was the only thing
 * between a stale corpus and {@code main} on two consecutive stories.
 *
 * <h2>What this guard actually compares</h2>
 *
 * <p>It boots the application, turns springdoc on for this context only, reads the document springdoc
 * generates at {@code /v3/api-docs}, and compares it against the authored file on two axes, in both
 * directions:
 *
 * <ol>
 *   <li><b>Operation bijection</b> — the set of {@code METHOD path} pairs. An endpoint Spring serves but
 *       the contract omits fails; so does a path the contract promises but Spring does not serve.</li>
 *   <li><b>Declared response status codes</b> — per operation, the set of status codes. This catches the
 *       common drift of adding a failure mode in code and not documenting it, or removing one and leaving
 *       the promise behind.</li>
 * </ol>
 *
 * <p>Reading the generated document rather than scanning annotations by hand is the point: springdoc
 * builds it from Spring's own {@code RequestMappingHandlerMapping}, so this guard cannot disagree with
 * what the application actually serves. A route registered by a mechanism a text scan would miss is
 * still in the generated document.
 *
 * <h2>What it does not catch — read this before trusting it</h2>
 *
 * <p><b>It would not have caught EOP-72.</b> That defect was prose: the contract described "all 68 cards
 * are dealt" while the code dealt {@code floor(D/n) × n}. Both the path and the status codes were
 * unchanged, so both axes above stay green. Descriptions, examples and narrative are compared by nothing
 * here. This guard closes an adjacent hole — signature and status drift — and the honest statement of its
 * limit is in ADR-054 rather than left implied.
 *
 * <p>It also does not compare request or response <b>schemas</b>. springdoc's generated schemas and the
 * authored ones differ routinely and legitimately on {@code $ref} naming, nullability spelling, examples
 * and description text, so a property-level diff would be noise, and a noisy gate gets deleted rather
 * than obeyed. Schema fidelity remains reviewer-enforced.
 *
 * <p>Nor does it check that a documented status code is <i>reachable</i>, only that code and contract
 * agree that it is declared. Both can be wrong together.
 *
 * <h2>Why there is no allow-list</h2>
 *
 * <p>The comparison covers the whole surface Spring serves, including {@code GET /health}, which is why
 * that endpoint is now documented in the authored file despite sitting outside {@code /api/v1}. An
 * exclusion list is what turns a gate into a formality, so there is none.
 *
 * <p>There is one declared exception, and it is <b>required rather than permitted</b>: {@code 413} on
 * {@code POST /api/v1/sessions} is produced by Caddy, not by Spring, so no truthful {@code @ApiResponse}
 * can exist for it and springdoc can never emit it. {@link #shouldNotCarryAnUnnecessaryException()} fails
 * if that entry ever stops being needed — either because the authored file dropped the status or because
 * the application started producing it — so the exception cannot quietly outlive its reason. This is the
 * same fail-in-both-directions discipline as {@code tools/supply-chain/accepted-advisories.json}, and it
 * is different in kind from an opt-out list: an entry here is a claim that must keep being true.
 *
 * <h2>Notes for whoever maintains this</h2>
 *
 * <p>Unlike its five siblings in this package, this guard boots a Spring context — it has to, because the
 * generated document only exists at runtime. The {@code properties} array below is byte-identical to
 * {@code SpringdocOptInIntegrationTest}'s, including order, so Spring's context cache key matches and the
 * context is shared rather than booted a second time. If the two ever diverge this still works; it just
 * costs a boot.
 *
 * <p>The authored file is parsed by indentation rather than with a YAML library, matching
 * {@link EnumMirrorParityTest}. That is deliberate: it means inconsistent indentation or an unquoted
 * status key fails here too, instead of being silently normalised by a parser and drifting from the
 * formatting every other operation in the file uses. The parse is bounded to the {@code paths:} block so
 * that three-digit keys elsewhere in the document cannot be mistaken for status codes.
 *
 * <p>No minimum-operation floor is asserted, because one would be redundant: a parse that silently
 * matched nothing would fail {@link #shouldDocumentEveryOperationSpringServes()} with every operation the
 * application serves reported as undocumented, which is a louder failure than any floor. Note it is that
 * test and not {@link #shouldServeEveryDocumentedOperation()} which fires, because an empty authored set
 * makes the authored-minus-generated difference empty and so leaves the latter green — the two directions
 * are not interchangeable, and only the generated side is independently guarded, by the status-code
 * assertion on the fetch in {@code readGenerated()}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@DisplayName("The hand-authored OpenAPI contract against the surface Spring serves")
class OpenApiContractDriftTest {

    /** The hand-authored contract. ADR-004 makes this the source of truth; the generated document is a mirror. */
    private static final Path AUTHORED = Path.of("docs", "api", "openapi.yml");

    /** Where springdoc serves the generated document once opted in (ADR-049). */
    private static final String API_DOCS = "/v3/api-docs";

    /** Opens the block this guard reads. Everything outside it is ignored. */
    private static final Pattern PATHS_BLOCK = Pattern.compile("^paths:\\s*$");

    /** Any line beginning in column zero closes the {@code paths:} block. */
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^\\S");

    /** A path key, at two spaces — {@code   /api/v1/cards:}. */
    private static final Pattern PATH_KEY = Pattern.compile("^ {2}(/\\S*):\\s*$");

    /** An HTTP method key, at four spaces. Listed explicitly so {@code parameters:} is not read as one. */
    private static final Pattern METHOD_KEY =
            Pattern.compile("^ {4}(get|put|post|delete|patch|head|options|trace):\\s*$");

    /** Any key of an operation object, at six spaces — {@code summary:}, {@code parameters:}, {@code responses:}. */
    private static final Pattern OPERATION_KEY = Pattern.compile("^ {6}(\\S+):.*$");

    /** Any key inside a {@code responses:} object, at eight spaces. Every one of these must be a status code. */
    private static final Pattern RESPONSE_KEY = Pattern.compile("^ {8}(\\S+):.*$");

    /** A response status code, single-quoted, as every operation in the authored file writes it. */
    private static final Pattern STATUS_KEY = Pattern.compile("^ {8}'(\\d{3})':\\s*$");

    /**
     * Statuses the authored contract declares that the application cannot produce, each with the reason.
     *
     * <p>This is not an allow-list. Every entry must keep being necessary — see
     * {@link #shouldNotCarryAnUnnecessaryException()} — so adding one to silence a failure only works if the
     * claim it makes is true, and it fails the build the moment it stops being true.
     */
    private static final List<ProxyProducedStatus> PROXY_PRODUCED = List.of(new ProxyProducedStatus(
            "POST",
            "/api/v1/sessions",
            "413",
            "Enforced by the reverse proxy: ui/Caddyfile sets request_body { max_size 16KB } on /api/*, so an "
                    + "oversized body is rejected before it reaches Spring. The response is plain text or empty, not a "
                    + "problem detail. No truthful @ApiResponse can exist for it, so springdoc can never emit it."));

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private final ObjectMapper json = new ObjectMapper();

    private Map<Operation, Set<String>> generated;

    private Map<Operation, Set<String>> authored;

    @BeforeEach
    void readBothDocuments() throws IOException, InterruptedException {
        generated = readGenerated();
        authored = readAuthored();
    }

    @AfterEach
    void hangUp() {
        client.shutdownNow();
    }

    @Test
    @DisplayName("documents every operation Spring serves")
    void shouldDocumentEveryOperationSpringServes() {
        Set<Operation> undocumented = new TreeSet<>(generated.keySet());
        undocumented.removeAll(authored.keySet());

        assertThat(undocumented)
                .as(
                        "The application serves operations that %s does not document. A surface nobody wrote down is "
                                + "a surface nobody reviewed: document each one in the authored file, in the same "
                                + "commit as the code that added it.",
                        AUTHORED)
                .isEmpty();
    }

    @Test
    @DisplayName("serves every operation it documents")
    void shouldServeEveryDocumentedOperation() {
        Set<Operation> unserved = new TreeSet<>(authored.keySet());
        unserved.removeAll(generated.keySet());

        assertThat(unserved)
                .as(
                        "%s promises operations the application does not serve. Either the code has been removed or "
                                + "renamed and the contract still advertises it, or the contract was written for an "
                                + "endpoint that never shipped. Both mislead a client author.",
                        AUTHORED)
                .isEmpty();
    }

    @Test
    @DisplayName("agrees with the code on which response statuses each operation declares")
    void shouldAgreeOnDeclaredResponseStatuses() {
        List<String> drift = new ArrayList<>();

        for (Operation operation : new TreeSet<>(authored.keySet())) {
            if (!generated.containsKey(operation)) {
                continue;
            }

            Set<String> expected = new TreeSet<>(authored.get(operation));
            expected.removeAll(proxyProducedStatusesFor(operation));
            Set<String> actual = generated.get(operation);

            if (!expected.equals(actual)) {
                Set<String> onlyAuthored = new TreeSet<>(expected);
                onlyAuthored.removeAll(actual);
                Set<String> onlyCode = new TreeSet<>(actual);
                onlyCode.removeAll(expected);
                drift.add("%s: documented but not declared in code %s, declared in code but not documented %s"
                        .formatted(operation, onlyAuthored, onlyCode));
            }
        }

        assertThat(drift)
                .as(
                        "The status codes %s declares disagree with the @ApiResponse annotations on the handlers. "
                                + "Fix whichever is wrong — do not add an annotation for a status the endpoint cannot "
                                + "return just to make this pass, because the contract is what a client author reads.",
                        AUTHORED)
                .isEmpty();
    }

    @Test
    @DisplayName("carries no declared exception that has stopped being necessary")
    void shouldNotCarryAnUnnecessaryException() {
        for (ProxyProducedStatus exception : PROXY_PRODUCED) {
            Operation operation = exception.operation();

            assertThat(authored)
                    .as("The declared exception for %s %s names an operation %s no longer documents.",
                            exception.status(), operation, AUTHORED)
                    .containsKey(operation);
            assertThat(authored.get(operation))
                    .as(
                            "The declared exception for %s on %s is stale: %s no longer documents that status, so the "
                                    + "entry in PROXY_PRODUCED should be deleted rather than left to weaken the "
                                    + "comparison for a status nobody promises.",
                            exception.status(), operation, AUTHORED)
                    .contains(exception.status());
            assertThat(generated.getOrDefault(operation, Set.of()))
                    .as(
                            "The declared exception for %s on %s is stale: the application now declares that status "
                                    + "itself, so the comparison can cover it. Delete the entry from PROXY_PRODUCED. "
                                    + "The reason it was added was: %s",
                            exception.status(), operation, exception.reason())
                    .doesNotContain(exception.status());
        }
    }

    private Set<String> proxyProducedStatusesFor(Operation operation) {
        Set<String> statuses = new TreeSet<>();
        for (ProxyProducedStatus candidate : PROXY_PRODUCED) {
            if (candidate.operation().equals(operation)) {
                statuses.add(candidate.status());
            }
        }
        return statuses;
    }

    /**
     * Reads the document springdoc generates, which is Spring's own view of what it serves.
     *
     * @return the declared status codes of every served operation
     * @throws IOException if the running application cannot be reached
     * @throws InterruptedException if the fetch is interrupted
     */
    private Map<Operation, Set<String>> readGenerated() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + API_DOCS))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("springdoc did not serve %s, so this guard has nothing to compare against and would otherwise "
                        + "pass vacuously. Check the properties on this class still enable it (ADR-049).", API_DOCS)
                .isEqualTo(200);

        Map<Operation, Set<String>> operations = new TreeMap<>();
        JsonNode paths = json.readTree(response.body()).path("paths");
        for (Iterator<String> pathNames = paths.fieldNames(); pathNames.hasNext(); ) {
            String path = pathNames.next();
            JsonNode methods = paths.path(path);
            for (Iterator<String> methodNames = methods.fieldNames(); methodNames.hasNext(); ) {
                String method = methodNames.next();
                Set<String> statuses = new TreeSet<>();
                methods.path(method).path("responses").fieldNames().forEachRemaining(statuses::add);
                operations.put(new Operation(method.toUpperCase(Locale.ROOT), path), statuses);
            }
        }
        return operations;
    }

    /**
     * Parses the {@code paths:} block of the authored contract by indentation.
     *
     * @return the documented status codes of every documented operation
     * @throws IOException if the authored file cannot be read
     */
    private Map<Operation, Set<String>> readAuthored() throws IOException {
        List<String> lines = Files.readAllLines(AUTHORED, StandardCharsets.UTF_8);
        Map<Operation, Set<String>> operations = new TreeMap<>();

        boolean insidePaths = false;
        String path = null;
        Operation operation = null;
        boolean insideResponses = false;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);

            if (!insidePaths) {
                insidePaths = PATHS_BLOCK.matcher(line).matches();
                continue;
            }
            if (TOP_LEVEL_KEY.matcher(line).find()) {
                break;
            }

            Matcher pathKey = PATH_KEY.matcher(line);
            if (pathKey.matches()) {
                path = pathKey.group(1);
                operation = null;
                insideResponses = false;
                continue;
            }

            Matcher methodKey = METHOD_KEY.matcher(line);
            if (methodKey.matches()) {
                operation = new Operation(methodKey.group(1).toUpperCase(Locale.ROOT), path);
                operations.put(operation, new TreeSet<>());
                insideResponses = false;
                continue;
            }

            Matcher operationKey = OPERATION_KEY.matcher(line);
            if (operationKey.matches()) {
                insideResponses = "responses".equals(operationKey.group(1));
                continue;
            }

            if (insideResponses && RESPONSE_KEY.matcher(line).matches()) {
                Matcher status = STATUS_KEY.matcher(line);
                if (!status.matches()) {
                    fail(
                            "%s line %d is a key inside a responses: block that this guard cannot read as a status "
                                    + "code: %s. Every status in this file is written as a single-quoted three-digit "
                                    + "code at eight spaces, and the parse is deliberately strict so that an "
                                    + "operation cannot drop out of the comparison through formatting alone. Write it "
                                    + "the way its siblings are written, or teach this parser and say why.",
                            AUTHORED, index + 1, line.strip());
                }
                operations.get(operation).add(status.group(1));
            }
        }

        return operations;
    }

    /** One HTTP operation: a method and a templated path, as both documents identify them. */
    private record Operation(String method, String path) implements Comparable<Operation> {

        @Override
        public String toString() {
            return method + " " + path;
        }

        @Override
        public int compareTo(Operation other) {
            return Comparator.comparing(Operation::path)
                    .thenComparing(Operation::method)
                    .compare(this, other);
        }
    }

    /** A status the contract declares and the application cannot produce, with the reason it cannot. */
    private record ProxyProducedStatus(String method, String path, String status, String reason) {

        private Operation operation() {
            return new Operation(method, path);
        }
    }
}
