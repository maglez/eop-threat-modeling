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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.maglez.eop.domain.ConnectionStatus;
import org.maglez.eop.domain.PlayerRole;

import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.StrideCategory;

/**
 * Holds the three declarations of each shared enum in agreement: the Java entity, the OpenAPI
 * schema, and the TypeScript mirror in {@code ui/src/api.ts} (EOP-105).
 *
 * <p>The same value is declared three times in this repository and nothing but habit kept the
 * three in step. EOP-105 is what that costs. {@code PlayerDto.role} had been typed
 * {@code 'FACILITATOR' | 'PLAYER'} while the server has only ever emitted {@code PARTICIPANT}
 * for a non-facilitator, and {@code SessionStateDto.status} carried an invented {@code 'ENDED'}
 * while omitting both {@code COMPLETED} and {@code ABANDONED}. Neither error could fail at
 * runtime: a TypeScript union is erased at compile time, and every fetch helper in that file
 * ends {@code (await response.json()) as SomeDto} — an assertion, not a parse — so the wrong
 * values arrived unchallenged and every comparison against a real one silently evaluated false.
 * The defect surfaced only because a reviewer read two files side by side.
 *
 * <p>That is the review this class replaces. It is deliberately a Java test rather than a
 * Vitest one: the front end has no {@code @types/node} on purpose, so that a browser project
 * does not pull in Node's type definitions ({@code ui/vite.config.ts} avoids {@code process.env}
 * for the same reason), and a Vitest case therefore cannot read {@code openapi.yml} or a Java
 * source file off disk. Reading repository files as text during {@code verify} is established
 * practice here in any case — see {@code AdrIndexConsistencyTest} (EOP-32),
 * {@code DeckArithmeticClaimsTest} (EOP-93) and {@code TrickPlayExceptionOriginTest} (EOP-14).
 * The Vitest suite keeps the complementary duty it can discharge in the browser: that each
 * declared member is accepted by its type guard and a non-member rejected.
 *
 * <p>Scope is membership, not order. Asserting that all three artefacts list their members in
 * the same sequence would be free to write and would fail the build for a harmless
 * re-alphabetising of a YAML block, which is the kind of strictness that gets a test deleted
 * rather than obeyed. Membership is the whole of the defect class: a value one artefact knows
 * and another does not.
 *
 * <p>This is a plain JUnit test with no Spring context. Surefire runs with the working directory
 * set to the project base directory, so the relative paths resolve.
 */
@DisplayName("Enum mirrors across Java, OpenAPI and the TypeScript client")
class EnumMirrorParityTest {

    /** The OpenAPI contract, which the Java entities and the TypeScript client both mirror. */
    private static final Path OPENAPI = Path.of("docs", "api", "openapi.yml");

    /** The hand-maintained TypeScript DTO layer. */
    private static final Path API_CLIENT = Path.of("ui", "src", "api.ts");

    /** Package directory holding the enums under test. */
    private static final Path DOMAIN_DIRECTORY = Path.of("src", "main", "java", "org", "maglez", "eop", "domain");

    /** Matches a Java block comment, so that a {@code @link} to a constant is not read as a declaration. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** Matches a Java line comment. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Matches an enum constant name: SHOUTING_SNAKE_CASE at the head of a declaration. */
    private static final Pattern CONSTANT = Pattern.compile("^([A-Z][A-Z0-9_]*)");

    /** Matches a schema key under {@code components.schemas}, which sits at four spaces of indent. */
    private static final Pattern SCHEMA_KEY = Pattern.compile("^ {4}(\\S+):\\s*$");

    /** Matches the {@code enum:} key inside a schema body, at six spaces of indent. */
    private static final Pattern ENUM_KEY = Pattern.compile("^ {6}enum:\\s*$");

    /** Matches one entry of a YAML block sequence, capturing its value. */
    private static final Pattern ENUM_VALUE = Pattern.compile("^ {8}-\\s*(\\S+)\\s*$");

    /**
     * Matches a single- or double-quoted string literal. Both are accepted because
     * {@code ui/src/api.ts} mixes the two by region — the older card-catalogue half uses double
     * quotes, the session half single — and a parser that only understood one would quietly force
     * a mirror to break its neighbours' style to be checkable.
     */
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]*)['\"]");

    /**
     * The enums declared in all three artefacts, and where each declaration lives.
     *
     * @param javaEnum simple name of the Java enum in {@code org.maglez.eop.domain}
     * @param openApiSchema key of the schema under {@code components.schemas} in the contract
     * @param typeScriptArray name of the {@code as const} array in {@code ui/src/api.ts}
     */
    private record Mirror(String javaEnum, String openApiSchema, String typeScriptArray) {

        @Override
        public String toString() {
            return javaEnum;
        }
    }

    /**
     * Supplies the enums that are mirrored across all three artefacts.
     *
     * <p>Adding a Java enum that the API exposes and the front end branches on means adding a row
     * here. Nothing detects an omission, which is the hole left in this guard: it keeps the mirrors
     * it is told about honest, and cannot know it has not been told about one. That hole is not
     * hypothetical — when this test was first written it covered three enums while
     * {@code ui/src/api.ts} mirrored a fourth, {@code StrideCategory}, as a bare union whose own
     * comment claimed parity with the server. EOP-105's review caught it and it is registered below.
     *
     * <p>Two enums the contract declares are deliberately absent from the table below:
     *
     * <ul>
     *   <li>{@code Rank} has no mirror in {@code ui/src/api.ts} — no {@code as const} array, no
     *       derived union, no {@code is*} guard — and EOP-109 decided it should stay that way rather
     *       than adding one by default. State it with that scope rather than as "no mirror at all":
     *       a hand-written twelve-member rank list does exist in
     *       {@code ui/src/utils/cardImagePath.test.ts}. It is invisible to this gate for two
     *       independent reasons, and the operative one is file scope:
     *       {@link #typeScriptArrayValues} reads only {@link #API_CLIENT} — that is
     *       {@code ui/src/api.ts} — and never opens another file, so its idiom regex never gets the
     *       chance to run. The second reason is that the list is neither exported nor
     *       {@code as const}, so it would not match the
     *       {@code export const NAME = [...] as const;} idiom even if it were read. Do not infer
     *       from the idiom bound alone that rewriting the list in that form would bring it under
     *       this gate — it would not, because the file is never read. The list is test-only, so
     *       drifting from the Java enum would under-test asset coverage rather than corrupt
     *       production behaviour, and it does not change the decision recorded here. Be precise
     *       about what that costs: the contract
     *       {@code $ref}s {@code Rank} for that field, so {@code Card.rank} and
     *       {@code CardDto.rank} being bare {@code string} in {@code ui/src/api.ts} is an accepted
     *       <em>drift</em>, not fidelity. Do not restate it as the contract declaring {@code rank}
     *       as {@code type: string} — it does not; {@code Card.rank} is
     *       {@code $ref: '#/components/schemas/Rank'}. The drift is accepted because the client
     *       never compares or orders a rank: the contract supplies {@code rankValue} expressly for
     *       comparison, the card face is rendered from {@code rankSymbol}, and {@code rank} reaches
     *       exactly one consumer — {@code cardImagePath(suit, rank)}, which returns {@code null}
     *       for anything it does not recognise and whose every call site null-checks. An
     *       out-of-contract rank therefore degrades to a missing image, never to a wrong
     *       comparison. Registering the mirror would also need {@link #openApiEnumValues} taught to
     *       read a YAML flow sequence, since {@code Rank} is written
     *       {@code enum: [TWO, THREE, ...]} on one line rather than as a block — a change to this
     *       gate's own parser, bought for a field with no client-side comparison to protect. If
     *       rank ever becomes something the front end orders or compares, add the mirror and teach
     *       the parser then.
     *   <li>{@code ProblemDetail} and the other response schemas carry no enums.
     * </ul>
     *
     * <p>A third exclusion used to sit here for a weaker reason, and it is now closed rather than
     * merely re-worded: {@code StrideCategory}-valued fields in {@code ui/src/api.ts} that were
     * declared bare {@code string} even though the mirror they should have used was registered
     * below. EOP-108 narrowed {@code CardDto.suit} and EOP-109 narrowed {@code TrickDto.ledSuit},
     * the last of them, so every field whose contract schema is a <em>mirrored</em> enum is now
     * both typed against that mirror and membership-checked by a parser in {@code ui/src/api.ts}.
     * Read that scope precisely: it is a claim about mirrored enums, and {@code rank} sits outside
     * it because {@code Rank} has no mirror, not because {@code rank} is narrow. Keep it that way:
     * a new bare-{@code string} field whose schema {@code $ref}s a <em>mirrored</em> enum belongs
     * in neither this list nor the code.
     *
     * @return one case per mirrored enum
     */
    private static Stream<Mirror> mirrors() {
        return Stream.of(
                new Mirror("PlayerRole", "PlayerRole", "PLAYER_ROLES"),
                new Mirror("SessionStatus", "SessionStatus", "SESSION_STATUSES"),
                new Mirror("ConnectionStatus", "ConnectionStatus", "CONNECTION_STATUSES"),
                new Mirror("StrideCategory", "StrideCategory", "STRIDE_CATEGORIES"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mirrors")
    @DisplayName("is declared with the same members in the OpenAPI contract as in the Java entity")
    void shouldAgreeBetweenJavaAndTheContract(final Mirror mirror) throws IOException {
        final List<String> java = javaConstants(mirror.javaEnum());
        final List<String> contract = openApiEnumValues(mirror.openApiSchema());
        assertThat(contract)
                .as(
                        "docs/api/openapi.yml schema %s must list exactly the constants of %s.java — "
                                + "a contract that disagrees with the server misleads every client generated or written from it",
                        mirror.openApiSchema(), mirror.javaEnum())
                .containsExactlyInAnyOrderElementsOf(java);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mirrors")
    @DisplayName("is mirrored in ui/src/api.ts with exactly the members the server can emit")
    void shouldAgreeBetweenJavaAndTheTypeScriptMirror(final Mirror mirror) throws IOException {
        final List<String> java = javaConstants(mirror.javaEnum());
        final List<String> mirrored = typeScriptArrayValues(mirror.typeScriptArray());
        assertThat(mirrored)
                .as(
                        "ui/src/api.ts %s must list exactly the constants of %s.java — a member the server never "
                                + "emits is a dead branch, and a member it does emit but the mirror omits has no typed path at all",
                        mirror.typeScriptArray(), mirror.javaEnum())
                .containsExactlyInAnyOrderElementsOf(java);
    }

    /**
     * Returns the constants of a Java enum in the entity package, in declaration order.
     *
     * <p>Comments are stripped first, so that the {@code @link} references these enums' javadoc
     * makes to their own constants are not mistaken for declarations. Only the constant block is
     * read — the text up to the first semicolon, which is where any methods begin.
     *
     * @param simpleName name of the enum, without package or extension
     * @return its constants, in the order declared
     * @throws IOException if the source file cannot be read
     * @throws IllegalArgumentException if the file declares no such enum, or no constants
     */
    private static List<String> javaConstants(final String simpleName) throws IOException {
        final Path source = DOMAIN_DIRECTORY.resolve(simpleName + ".java");
        final String stripped = LINE_COMMENT
                .matcher(BLOCK_COMMENT.matcher(Files.readString(source)).replaceAll(" "))
                .replaceAll(" ");
        final Matcher declaration = Pattern.compile("\\benum\\s+" + Pattern.quote(simpleName) + "\\s*\\{").matcher(stripped);
        if (!declaration.find()) {
            throw new IllegalArgumentException(source + " declares no enum named " + simpleName);
        }
        final String body = stripped.substring(declaration.end());
        final int terminator = body.indexOf(';');
        final int close = body.indexOf('}');
        final String block = body.substring(0, endOfConstantBlock(source, terminator, close));

        final List<String> constants = new ArrayList<>();
        for (final String fragment : block.split(",")) {
            final Matcher matcher = CONSTANT.matcher(fragment.strip());
            if (matcher.find()) {
                constants.add(matcher.group(1));
            }
        }
        if (constants.isEmpty()) {
            throw new IllegalArgumentException("no constants parsed from " + source);
        }
        return constants;
    }

    /**
     * Returns where an enum's constant block ends: at the semicolon that introduces its methods,
     * or at the closing brace when it has none.
     *
     * @param source the file being parsed, for the failure message
     * @param terminator index of the first semicolon in the body, or -1
     * @param close index of the first closing brace in the body, or -1
     * @return the exclusive end index of the constant block
     * @throws IllegalArgumentException if the body has neither
     */
    private static int endOfConstantBlock(final Path source, final int terminator, final int close) {
        if (terminator >= 0 && (close < 0 || terminator < close)) {
            return terminator;
        }
        if (close >= 0) {
            return close;
        }
        throw new IllegalArgumentException("unterminated enum body in " + source);
    }

    /**
     * Returns the values of a schema's {@code enum} block in the OpenAPI contract.
     *
     * <p>Parsed by indentation rather than with a YAML library, deliberately: the contract is
     * hand-authored and read by humans, and a parser that also fails when the block is indented
     * inconsistently is checking something worth checking. The search is bounded to the schema
     * named, so that the next schema's {@code enum} cannot be picked up when this one has none.
     *
     * @param schemaName key under {@code components.schemas}
     * @return its enumerated values, in the order listed
     * @throws IOException if the contract cannot be read
     * @throws IllegalArgumentException if the schema is absent, or lists no enum values
     */
    private static List<String> openApiEnumValues(final String schemaName) throws IOException {
        final List<String> lines = Files.readAllLines(OPENAPI);
        int start = -1;
        for (int index = 0; index < lines.size(); index++) {
            final Matcher key = SCHEMA_KEY.matcher(lines.get(index));
            if (key.matches() && key.group(1).equals(schemaName)) {
                start = index + 1;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalArgumentException(OPENAPI + " has no schema named " + schemaName);
        }

        final List<String> values = new ArrayList<>();
        boolean inEnum = false;
        for (int index = start; index < lines.size(); index++) {
            final String line = lines.get(index);
            if (SCHEMA_KEY.matcher(line).matches()) {
                break;
            }
            if (ENUM_KEY.matcher(line).matches()) {
                inEnum = true;
                continue;
            }
            if (inEnum) {
                final Matcher value = ENUM_VALUE.matcher(line);
                if (!value.matches()) {
                    break;
                }
                values.add(value.group(1));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("schema " + schemaName + " in " + OPENAPI + " lists no enum values");
        }
        return values;
    }

    /**
     * Returns the members of an {@code as const} array exported from {@code ui/src/api.ts}.
     *
     * @param arrayName name of the exported constant
     * @return its string members, in the order written
     * @throws IOException if the client cannot be read
     * @throws IllegalArgumentException if no such array is exported, or it is empty
     */
    private static List<String> typeScriptArrayValues(final String arrayName) throws IOException {
        final String source = Files.readString(API_CLIENT);
        final Matcher declaration = Pattern
                .compile("export\\s+const\\s+" + Pattern.quote(arrayName) + "\\s*=\\s*\\[([^\\]]*)\\]\\s*as\\s+const\\s*;")
                .matcher(source);
        if (!declaration.find()) {
            throw new IllegalArgumentException(API_CLIENT + " exports no `as const` array named " + arrayName);
        }

        final List<String> values = new ArrayList<>();
        final Matcher quoted = QUOTED.matcher(declaration.group(1));
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(arrayName + " in " + API_CLIENT + " is empty");
        }
        return values;
    }
}
