package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.inbound.web.GlobalExceptionHandler;
import org.maglez.eop.outbound.security.SecureRandomJoinCodeGenerator;

/**
 * Holds the prose that reasons about the <em>width of the join-code space</em> against the two
 * constants that actually determine it: {@link JoinCode#ALPHABET} and {@link JoinCode#LENGTH}.
 *
 * <p>This test exists because of a fragility named in EOP-56. Two places justify a decision by
 * appeal to how wide the code space is — {@code GlobalExceptionHandler} logs an exhausted
 * join-code budget at {@code WARN} <em>with a stack trace</em> on the grounds that a caller
 * cannot provoke it, and refuses to say why a lookup failed on the grounds that confirming a
 * real code would be an oracle worth querying. Both arguments are sound at forty bits and both
 * quietly stop being sound if the alphabet shrinks or the code gets shorter. Nothing connected
 * the prose to the constants, so such a change would have withdrawn the justification for
 * logging a trace on a caller-reachable path while every test stayed green.
 *
 * <p>EOP-56's remedy was to make the reasoning rest on a <em>control</em> — the per-address
 * creation rate limit from ADR-033 — rather than on arithmetic alone, because a control survives
 * a change to the constants. That is a change to the argument, not to the arithmetic, and the
 * arithmetic is still quoted in both places as the reason an occurrence is worth reading. This
 * test is the other half: it makes the quoted width derived rather than asserted, so shrinking
 * the alphabet or the code turns the stale sentences red instead of leaving them to be believed.
 *
 * <p>The derivation is deliberately narrow. It computes bits per character as the base-2
 * logarithm of the alphabet size, which is only a whole number when that size is a power of two,
 * so the power-of-two check is a precondition of the derivation rather than a style preference.
 * It also requires the alphabet's characters to be distinct, because a repeated character would
 * leave {@code length()} unchanged while lowering the entropy each character actually carries —
 * the one way these constants could drift without either asserted number moving.
 *
 * <p>Coverage comes in two layers, because the two failure modes are different. Three
 * <em>justifications</em> are pinned individually by anchor phrase, so that a failure names the
 * argument that has gone stale and says what it was for. Anchoring is precise but fragile —
 * rewording an anchor would make its check vacuous rather than red — so each anchor is asserted to
 * occur <em>exactly once</em>, which turns a reword into a failure and a duplication into one too.
 *
 * <p>Anchoring alone would still have missed the larger problem. The width is quoted in more than
 * a dozen places: the entity's own Javadoc, both join limiters, the generator and its port, the
 * join use case, an exception, and the C4 and runtime-view documents. EOP-24 widened the code from
 * six characters to eight and every one of those sentences had to be found and corrected by hand.
 * So the second layer is a sweep of {@code src/main/java}, {@code docs/architecture},
 * {@code docs/adr} and {@code docs/api} that requires <em>every</em> spelled-out bit width to be
 * the derived one, which
 * means a site added later is covered without anyone remembering to register it.
 *
 * <p>The sweep needs an escape hatch, because some passages quote a width deliberately in order to
 * argue against it: ADR-019 rejects six characters at thirty bits and ten at fifty, the generator
 * and both architecture documents explain what was wrong with thirty, and ADR-021 quotes the
 * superseded wording verbatim. Those are listed in {@link #SUPERSEDED}, each with its reason, and
 * the list fails in <em>both</em> directions — an unexplained width fails, and so does an entry
 * that no longer matches anything, so a deleted historical passage means deleting its entry rather
 * than leaving it to rot. What the sweep deliberately does not do is check digits or powers of
 * two written out longhand; it reads the English words the prose actually uses.
 */
@DisplayName("Join-code space claims in prose match the constants that determine them")
class JoinCodeSpaceClaimsTest {

    private static final Path ADR = Path.of("docs", "adr", "ADR-019-session-lifecycle-and-join-codes.md");

    private static final Path HANDLER = Path.of(
            "src", "main", "java", "org", "maglez", "eop", "inbound", "web", "GlobalExceptionHandler.java");

    /**
     * The width the prose currently states, in bits. Asserted against the derived value rather
     * than trusted, so it cannot drift from the constants the way the prose it guards did.
     */
    private static final int EXPECTED_WIDTH_BITS = 40;

    /**
     * Trees swept for width claims. Java sources, Markdown and YAML are all read as plain text.
     *
     * <p>{@code docs/api} is here because a reviewer found it absent: the hand-authored contract
     * states the width and was the one place a stale figure could outlive this gate.
     */
    private static final List<Path> SWEEP_ROOTS = List.of(
            Path.of("src", "main", "java"), Path.of("docs", "architecture"), Path.of("docs", "adr"),
            Path.of("docs", "api"));

    /**
     * Spelled-out bit widths. Only English words are matched, because that is how this prose
     * writes them.
     *
     * <p>The compounds are listed before their prefixes for readability, but do not rely on that
     * ordering: it is not what stops {@code thirty-five bits} being read as {@code thirty}. The
     * {@code [ -]bits?} suffix is. Were the order reversed, {@code thirty} would match, the
     * separator would consume the hyphen, {@code bits?} would then fail against {@code five}, and
     * the engine would backtrack into the alternation and take {@code thirty-five}. An earlier
     * version of this comment claimed the ordering was load-bearing; it is not, and a reviewer
     * caught the claim.
     */
    private static final Pattern WIDTH_MENTION = Pattern.compile(
            "(twenty-five|twenty|thirty-five|thirty|forty-five|forty|fifty-five|fifty|sixty)[ -]bits?\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * The floor on how many live claims the sweep must find. A floor rather than an exact count:
     * quoting the width in one more place is not a regression, whereas the sweep silently matching
     * nothing would be. Well below the number present so ordinary editing does not churn it.
     */
    private static final int MINIMUM_LIVE_CLAIMS = 12;

    /**
     * How much prose after an anchor is searched for the width phrase. Wide enough that the phrase
     * follows the anchor within it — the longest is twenty-one characters — and narrow enough that a
     * match cannot be borrowed from the next sentence, which would let a reworded claim pass.
     */
    private static final int ANCHOR_WINDOW_CHARS = 60;

    /**
     * Widths quoted deliberately in order to be argued against, which the sweep must not demand be
     * rewritten. Each entry names the file, the word, and why that mention is legitimate.
     */
    private static final List<SupersededMention> SUPERSEDED = List.of(
            new SupersededMention("ADR-019-session-lifecycle-and-join-codes.md", "thirty",
                    "ADR-019 argues against the superseded six-character code, which was thirty bits"),
            new SupersededMention("ADR-019-session-lifecycle-and-join-codes.md", "fifty",
                    "ADR-019 argues against a rejected ten-character alternative at fifty bits"),
            new SupersededMention("ADR-021-trusted-proxy-forwarded-for.md", "thirty",
                    "ADR-021 quotes ADR-019's superseded \"at thirty bits the rate limiter is a primary security "
                            + "control\" wording verbatim, and its own EOP-24 amendment marks the quotation historical"),
            new SupersededMention("README.md", "thirty",
                    "the ADR index's ADR-019 summary contrasts the current width against the thirty bits that "
                            + "was enumerable by a distributed attacker"),
            new SupersededMention("SecureRandomJoinCodeGenerator.java", "thirty",
                    "the generator's Javadoc explains what was inadequate about thirty bits"),
            new SupersededMention("C4-Diagrams.md", "thirty",
                    "the container view contrasts the current width against the superseded thirty bits"),
            new SupersededMention("runtime-view.md", "thirty",
                    "the runtime view records that thirty bits left the join limiter carrying too much"));

    /**
     * A width quoted deliberately for historical or rejected-alternative reasons.
     *
     * @param pathSuffix  file name the mention is permitted in
     * @param word        the spelled-out width permitted there
     * @param reason      why this mention is legitimate rather than stale prose
     */
    private record SupersededMention(String pathSuffix, String word, String reason) { }

    @Test
    @DisplayName("the code space is forty bits wide, derived from a distinct power-of-two alphabet")
    void shouldDeriveTheWidthFromTheConstants() {
        final int alphabetSize = JoinCode.ALPHABET.length();

        assertThat(alphabetSize)
                .as("the alphabet must not be empty, or every derivation below is vacuous")
                .isPositive();

        assertThat(JoinCode.ALPHABET.chars().distinct().count())
                .as("JoinCode.ALPHABET must contain no repeated character. A duplicate would leave "
                        + "length() unchanged while lowering the entropy each character carries, which is "
                        + "the one way the code space could narrow without either number in this test moving.")
                .isEqualTo(alphabetSize);

        assertThat(Integer.bitCount(alphabetSize))
                .as("JoinCode.ALPHABET must have a power-of-two size (it is %d). This test derives bits "
                        + "per character as log2 of that size, which is only a whole number of bits when it "
                        + "is a power of two, so this is a precondition of the derivation and not a "
                        + "preference. If the alphabet must change to a non-power-of-two size, the prose "
                        + "this test guards has to start quoting a non-integer width and this derivation "
                        + "needs rewriting rather than relaxing.", alphabetSize)
                .isEqualTo(1);

        assertThat(JoinCode.LENGTH)
                .as("JoinCode.LENGTH must be positive, or the derived width is zero and every prose "
                        + "assertion below would be comparing against 'zero'")
                .isPositive();

        assertThat(derivedWidthBits())
                .as("EXPECTED_WIDTH_BITS is the width this test asserts the prose states. It has drifted "
                        + "from the constants: %d characters drawn from a %d-character alphabet is %d bits, "
                        + "not %d. Fix the constant here and then fix every sentence this test pins, "
                        + "because each of them quotes the old width.",
                        JoinCode.LENGTH, alphabetSize, derivedWidthBits(), EXPECTED_WIDTH_BITS)
                .isEqualTo(EXPECTED_WIDTH_BITS);
    }

    @Test
    @DisplayName("ADR-019's 503 capacity statement quotes the derived width")
    void shouldStateTheDerivedWidthInTheCapacityJustification() throws IOException {
        assertClaimQuotesWidth(
                flatten(read(ADR)),
                "ADR-019's amended 503 bullet",
                "the code space is finite and",
                numberWord(derivedWidthBits()) + " bits wide",
                "This bullet explains why an exhausted join-code budget is answered 503 rather than 500: "
                        + "the space is finite, so exhaustion is a capacity statement rather than a fault. "
                        + "The width is the whole force of that argument.");
    }

    @Test
    @DisplayName("the WARN-with-trace justification quotes the derived width")
    void shouldStateTheDerivedWidthInTheLogLevelJustification() throws IOException {
        assertClaimQuotesWidth(
                flatten(read(HANDLER)),
                "GlobalExceptionHandler.handleJoinCodeUnavailable's Javadoc",
                "the occupied fraction of the",
                numberWord(derivedWidthBits()) + "-bit code space",
                "This sentence is the arithmetic half of why an exhausted join-code budget is logged at "
                        + "WARN with a stack trace. EOP-56 made the binding half a control — the per-address "
                        + "creation limit from ADR-033 — precisely so this half could go stale without "
                        + "withdrawing the justification. It should still be accurate: a narrower space makes "
                        + "collisions likelier, and the sentence would then be understating how often this "
                        + "fires.");
    }

    @Test
    @DisplayName("the join-code oracle justification quotes the derived width")
    void shouldStateTheDerivedWidthInTheOracleJustification() throws IOException {
        assertClaimQuotesWidth(
                flatten(read(HANDLER)),
                "GlobalExceptionHandler's session-lookup Javadoc",
                "must be indistinguishable: at",
                numberWord(derivedWidthBits()) + " bits of entropy",
                "This sentence justifies refusing to reveal why a join-code lookup failed. Unlike the log "
                        + "level, no control was added to back this one up: it rests on the width alone, so a "
                        + "narrower space weakens it directly and there is nothing else holding it.");
    }

    @Test
    @DisplayName("every spelled-out width across source, architecture docs, ADRs and the API contract is the derived one")
    void shouldQuoteTheDerivedWidthWhereverItIsClaimed() throws IOException {
        final String derived = numberWord(derivedWidthBits());
        final List<String> unexplained = new ArrayList<>();
        final Map<SupersededMention, Integer> allowlistHits = new LinkedHashMap<>();
        SUPERSEDED.forEach(entry -> allowlistHits.put(entry, 0));
        int liveClaims = 0;

        final List<Path> swept = sweepFiles();
        assertThat(swept)
                .as("the sweep must find files to read under %s, or every assertion below is vacuous "
                        + "because an empty sweep trivially reports no stale prose", SWEEP_ROOTS)
                .isNotEmpty();

        for (final Path file : swept) {
            final Matcher matcher = WIDTH_MENTION.matcher(flatten(read(file)));
            while (matcher.find()) {
                final String word = matcher.group(1).toLowerCase(Locale.ROOT);
                if (word.equals(derived)) {
                    liveClaims++;
                    continue;
                }
                final SupersededMention entry = SUPERSEDED.stream()
                        .filter(candidate -> file.getFileName().toString().equals(candidate.pathSuffix())
                                && candidate.word().equals(word))
                        .findFirst()
                        .orElse(null);
                if (entry == null) {
                    unexplained.add(file + " quotes \"" + matcher.group() + "\"");
                } else {
                    allowlistHits.merge(entry, 1, Integer::sum);
                }
            }
        }

        assertThat(unexplained)
                .as("every spelled-out bit width in these trees must be the derived width (%s bits), or be "
                        + "listed in SUPERSEDED as a width quoted deliberately in order to be argued against. "
                        + "The mentions below are neither. If the constants in JoinCode moved, this is the "
                        + "list of sentences EOP-24 had to find by hand and the prose is what needs fixing. If "
                        + "one of them is a deliberate historical reference, add it to SUPERSEDED with its "
                        + "reason rather than deleting this assertion.", derived)
                .isEmpty();

        allowlistHits.forEach((entry, hits) -> assertThat(hits)
                .as("SUPERSEDED lists \"%s bits\" in %s (%s) but no such mention was found. The passage was "
                        + "presumably reworded or removed, so delete the entry: a stale exemption silently "
                        + "widens what this sweep will tolerate.", entry.word(), entry.pathSuffix(), entry.reason())
                .isPositive());

        assertThat(liveClaims)
                .as("the sweep found only %d live mentions of the derived width, below the floor of %d. Either "
                        + "the prose stopped quoting the width — in which case this test is no longer holding "
                        + "anything and the floor is the only thing that says so — or the pattern stopped "
                        + "matching how it is written.", liveClaims, MINIMUM_LIVE_CLAIMS)
                .isGreaterThanOrEqualTo(MINIMUM_LIVE_CLAIMS);
    }

    /**
     * Collects every Java source, Markdown and YAML file under {@link #SWEEP_ROOTS}.
     *
     * <p>YAML is swept because the hand-authored API contract states the width three times, and a
     * reviewer found those claims sitting outside an earlier version of this sweep. A contract is
     * the last place a stale figure should survive, since it is what a client integrates against.
     *
     * <p>Only {@code .yml} is matched, not {@code .yaml}. Every YAML file in this repository uses
     * the short spelling, so the long one would match nothing today — but a contract added as
     * {@code openapi.yaml} would slip the sweep silently, so add the extension here if that
     * spelling ever appears rather than assuming this filter found it.
     *
     * @return the files to sweep, in a stable order
     * @throws IOException if a root cannot be walked
     */
    private static List<Path> sweepFiles() throws IOException {
        final List<Path> files = new ArrayList<>();
        for (final Path root : SWEEP_ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> {
                            final String name = path.getFileName().toString();
                            return name.endsWith(".java") || name.endsWith(".md") || name.endsWith(".yml");
                        })
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    /**
     * Asserts that the prose immediately following {@code anchor} quotes {@code expectedPhrase}.
     *
     * <p>The anchor is required to occur exactly once. Zero occurrences would mean the sentence was
     * reworded and this check had silently gone vacuous; more than one would mean the extraction
     * below could be reading a different sentence than the one intended.
     *
     * @param document      the whole file, already flattened onto one line
     * @param label         how to name the location in a failure message
     * @param anchor        invariant text immediately preceding the quoted width
     * @param expectedPhrase the derived phrase the prose must quote
     * @param why           what the sentence is for, so a failure explains what breaks
     */
    private static void assertClaimQuotesWidth(
            final String document,
            final String label,
            final String anchor,
            final String expectedPhrase,
            final String why) {

        assertThat(occurrencesOf(document, anchor))
                .as("%s must contain the anchor \"%s\" exactly once. Zero means the sentence was reworded "
                        + "and this check would have gone vacuous rather than failing — re-anchor it on the "
                        + "new wording instead of deleting it. More than one means the anchor no longer "
                        + "identifies a single sentence. %s", label, anchor, why)
                .isEqualTo(1);

        final int start = document.indexOf(anchor) + anchor.length();
        final String following = document.substring(start, Math.min(start + ANCHOR_WINDOW_CHARS, document.length()));

        assertThat(following)
                .as("%s must quote the derived code-space width. Just after \"%s\" this test expected "
                        + "\"%s\" and found \"%s\". The constants in JoinCode are the source of truth, so "
                        + "the prose is what is wrong here. %s", label, anchor, expectedPhrase, following.strip(), why)
                .contains(expectedPhrase);
    }

    /**
     * Derives the width of the join-code space in bits from the two constants that determine it.
     *
     * <p>Only meaningful once the alphabet size is known to be a power of two, which
     * {@link #shouldDeriveTheWidthFromTheConstants()} asserts.
     *
     * @return the number of bits of entropy a full-length join code carries
     */
    private static int derivedWidthBits() {
        final int bitsPerCharacter = Integer.numberOfTrailingZeros(JoinCode.ALPHABET.length());
        return bitsPerCharacter * JoinCode.LENGTH;
    }

    /**
     * Collapses a Javadoc comment or a Markdown blockquote onto a single line.
     *
     * <p>Both of the files read here wrap the sentences this test pins across several lines, each
     * continuation carrying a leading {@code *} or {@code >}. Without flattening, a phrase such as
     * "at forty bits of entropy" is unmatchable purely because of where the line happened to break.
     *
     * @param text the raw file contents
     * @return the same text with line-continuation markers and runs of whitespace reduced to single spaces
     */
    private static String flatten(final String text) {
        return text.replaceAll("\\n\\s*[*>]\\s?", " ").replaceAll("\\s+", " ");
    }

    /**
     * Counts non-overlapping occurrences of {@code needle} in {@code haystack}.
     *
     * @param haystack the text to search
     * @param needle   the text to count
     * @return how many times {@code needle} appears
     */
    private static int occurrencesOf(final String haystack, final String needle) {
        int count = 0;
        int from = haystack.indexOf(needle);
        while (from >= 0) {
            count++;
            from = haystack.indexOf(needle, from + needle.length());
        }
        return count;
    }

    /**
     * Renders a bit count the way the prose spells it — as an English word rather than digits.
     *
     * <p>Unsupported values fail loudly rather than returning digits that would never match the
     * prose, so a change to the constants produces a message naming the missing word instead of an
     * opaque mismatch.
     *
     * @param value the number to spell
     * @return the lower-case English word for {@code value}
     */
    private static String numberWord(final int value) {
        return switch (value) {
            case 20 -> "twenty";
            case 25 -> "twenty-five";
            case 30 -> "thirty";
            case 35 -> "thirty-five";
            case 40 -> "forty";
            case 45 -> "forty-five";
            case 50 -> "fifty";
            case 55 -> "fifty-five";
            case 60 -> "sixty";
            default -> throw new IllegalArgumentException(
                    "No English word is known for a width of " + value + " bits. The join-code constants "
                            + "have changed to a width this test cannot spell; add the word to numberWord "
                            + "and update the prose it guards.");
        };
    }

    /**
     * Reads a repository-relative file as UTF-8.
     *
     * @param path the file to read, relative to the repository root
     * @return the file contents
     * @throws IOException if the file cannot be read
     */
    private static String read(final Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
