package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.application.port.in.GetScorePort;
import org.maglez.eop.domain.Hands;
import org.maglez.eop.domain.NoTamperingCardDealtException;

import org.maglez.eop.domain.Trick;

/**
 * Guards prose about the deck against arithmetic that the deck size has outgrown (EOP-93).
 *
 * <p>The deck has been trimmed twice: EOP-69 took it from 78 cards to 74, and EOP-75 removed the six Aces to leave 68
 * (ADR-041). Each trim left prose behind. EOP-92's review found three documents simultaneously stale with a green
 * build, and EOP-93 then found fifteen more places — five of them in Java javadoc, four of them in claims no reviewer
 * had listed. Nothing in the build noticed any of it, because a comment cannot fail to compile.
 *
 * <p>The recurring defect was never the digits. It was the <em>universal quantifier</em>. Prose kept asserting that
 * the deal is uneven at <em>every</em> supported table size, which was true at 74 cards and false at 68: 68 divides
 * evenly by four, so at that one table size every seat holds seventeen and the final trick is full. Sentences of that
 * shape survived both trims because they name no number at all. Two concrete examples, both live on {@code main}
 * until EOP-93: "At 74 cards, all four supported table sizes (3, 4, 5 and 6 players) produce unequal hands", and
 * "EOP-14 deals the whole deck out, so at every supported player count (3, 4, 5 and 6) the hands are unequal".
 *
 * <p>So the first invariant here does not look for stale numbers. It looks for a universal claim over table sizes
 * sitting in the same sentence as a claim that the deal is uneven, and fails only when some supported table size
 * actually does divide the deck evenly. It is derived from {@link #CURRENT_DECK_SIZE}, so the next trim needs one
 * number changed rather than a re-reading of every document. The second invariant is the cheap one the first cannot
 * express: no superseded deck size stated in Java prose, where javadoc went stale five times in a row.
 *
 * <p>The third invariant was added by EOP-96 for a defect the second structurally cannot see: a number that is not a
 * deck size but is <em>derived</em> from one. Five places priced a per-trick score assembler at the figure a
 * 78-card deck produces, and because 79 is not 78 the figure survived two deck trims and EOP-93's sweep untouched.
 * Both the trick count and the read count are computed here from {@link #CURRENT_DECK_SIZE}, so a trim needs that one
 * number changed — and one further thing the first version of this invariant got wrong. Prose writes a total as a
 * word, so {@link #spell(int)} has to know the word for the cost the current deck implies <em>before</em> that cost
 * becomes superseded. Nothing announces a missing spelling: an unspellable number simply fails to match, and the
 * guard goes quiet while reporting success. So {@link #shouldNotStateASupersededRoundTripCount()} asserts the
 * spelling exists rather than trusting whoever trims the deck next to notice that it must be added.
 *
 * <p>That invariant is narrow on purpose, and the narrowness is the interesting part. It matches only a round-trip
 * count, and only in a sentence that is pricing a read. That anchor does all of the work. ADR-023 tabulates the
 * 78-card and 74-card deals under EOP-69's Amends header, which is correct history, and ADR-011 says "while the 26
 * descriptions existed ... neither graphify_get_node nor graphify_query_graph rendered them" — neither prices a read,
 * so neither can fire, though a matcher keyed on "query" or on the derived trick count would have flagged both. An
 * earlier draft of this invariant also skipped Markdown table rows out of the same fear. That skip was removed: it
 * protected against nothing the anchor does not already prevent, and it blinded the invariant to docs/adr/README.md,
 * whose ADR-031 row is itself one of the sites EOP-96 had to correct by hand. Widening the anchor would trade a
 * defect this repository has had for false positives it has not; narrowing the walk cost it a site it needed.
 *
 * <p>The standing caveat applies to all three: these are phrase lists, not semantic analysis. A green build here is
 * not proof that the documentation is right, only that the specific wordings that have gone stale before are absent.
 *
 * <p>Be precise about what "a universal claim" means here, because the phrase flatters the implementation. {@link
 * #EVERY_TABLE_SIZE} is a list of the phrasings that have actually gone stale in this repository, not a semantic
 * analysis of quantification. "At all four table sizes", "at each player count" and "the final trick is always
 * short" all escape it. That is a deliberate trade — a matcher general enough to catch every phrasing of a universal
 * claim would also catch the many true ones — but it has a consequence worth stating plainly: <em>a green build is
 * not proof that no stale universal claim exists.</em> It proves only that the specific shapes which have burned us
 * before are absent. Extend the pattern when a new shape appears; do not read the pass as a clean bill of health.
 *
 * <p>The scope is deliberately narrow, because an over-strict version of this test would be worked around instead of
 * obeyed. It matches per sentence rather than per paragraph, so an unrelated later sentence cannot supply the second
 * half of a contradiction; it says nothing about the many correct historical references in {@code docs/} — the
 * author's own "74, not 78 cards" quote, ADR-041, and the dated amendment blocks all state superseded sizes on
 * purpose; and it does not police ADR-023's original body, which still reads 78 cards under EOP-69's explicit
 * {@code Amends:} header and is correct as it stands.
 *
 * <p>This is a plain JUnit test with no Spring context. Surefire runs with the working directory set to the project
 * base directory, so the relative paths resolve.
 */
@DisplayName("Deck arithmetic claims in prose")
class DeckArithmeticClaimsTest {

    /** The deck as seeded today: 68 cards, after EOP-75 removed the six Aces. See ADR-041. */
    private static final int CURRENT_DECK_SIZE = 68;

    /**
     * Deck sizes that were once current and are now wrong to state as current: 78 as printed and originally seeded,
     * 74 after EOP-69 dropped the four cards the physical deck does not have.
     */
    private static final List<Integer> SUPERSEDED_DECK_SIZES = List.of(74, 78);

    /** Player counts the game supports, per ADR-023. The deal arithmetic differs at each. */
    private static final List<Integer> SUPPORTED_TABLE_SIZES = List.of(3, 4, 5, 6);

    /** Documentation root. Markdown here is prose a reader is expected to trust. */
    private static final Path DOCS_DIRECTORY = Path.of("docs");

    /** Source root. Javadoc here is prose a reader is expected to trust just as much. */
    private static final Path SOURCE_DIRECTORY = Path.of("src");

    /**
     * Files allowed to state a superseded deck size, and to write the sentences the first invariant forbids.
     *
     * <p>{@code DeckTrimMigrationRoundTripTest} rolls the ace-removal migration back and asserts the deck passes
     * through 74 cards and then 78, so those numbers are its assertions rather than a stale comment. This test is on
     * the list for an unavoidable reason: a guard has to quote what it forbids. Its javadoc names the claims that
     * were live on {@code main}, and {@link #shouldRecogniseTheHistoricalDefectItGuardsAgainst()} feeds them back in
     * to prove the matcher still fires. Without the exemption this test would fail on its own evidence, and the
     * tempting fix — deleting the examples — would leave a regex nobody could check.
     *
     * <p>The entries are repository-relative paths rather than bare filenames. A basename match would exempt any
     * future file anywhere in the tree that happened to share a name with one of these two, which is the weakest
     * discriminator available and costs nothing to improve.
     */
    private static final List<String> EXEMPT_FILES = List.of(
            "src/test/java/org/maglez/eop/migration/DeckTrimMigrationRoundTripTest.java",
            "src/test/java/org/maglez/eop/docs/DeckArithmeticClaimsTest.java");

    /**
     * A claim that ranges over every supported table size. The enumeration is included because prose reaches for
     * "3, 4, 5 and 6 players" as often as it reaches for a quantifier word, and both forms went stale.
     */
    private static final Pattern EVERY_TABLE_SIZE = Pattern.compile(
            "all (?:four|the four) supported table sizes"
                    + "|at any supported table size"
                    + "|at every supported table size"
                    + "|every supported table size"
                    + "|at any player count"
                    + "|at every player count"
                    + "|at every supported player count"
                    + "|every supported player count"
                    + "|\\b3,\\s*4,\\s*5,?\\s*(?:and|or)\\s*6\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A claim that no supported table size divides the deck. The negation lives in the quantifier here, so this pairs
     * with the opposite predicate to {@link #EVERY_TABLE_SIZE} — see {@link #DIVIDES_EVENLY}.
     */
    private static final Pattern NO_TABLE_SIZE = Pattern.compile(
            "no supported table size|none of the supported table sizes",
            Pattern.CASE_INSENSITIVE);

    /**
     * A claim that the deal comes out uneven. Note that "unequal" is matched but "near-equal" is not, so PRD-eop's
     * true sentence about 68 producing "equal or near-equal hands" is left alone.
     */
    private static final Pattern DEAL_IS_UNEVEN = Pattern.compile(
            "unequal"
                    + "|do(?:es)? not divide"
                    + "|short final trick"
                    + "|(?:final|last) trick is short",
            Pattern.CASE_INSENSITIVE);

    /** A claim that the deck does divide evenly — false only when paired with {@link #NO_TABLE_SIZE}. */
    private static final Pattern DIVIDES_EVENLY = Pattern.compile(
            "divides? (?:evenly|equally)",
            Pattern.CASE_INSENSITIVE);

    /** Jira keys are stripped before any number is read, so "EOP-74" cannot be mistaken for a deck size. */
    private static final Pattern JIRA_KEY = Pattern.compile("EOP-\\d+", Pattern.CASE_INSENSITIVE);

    /** Splits a paragraph into sentences. Sentence scope is what keeps the first invariant honest. */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

    /**
     * Matches prose that is costing out a read, and nothing wider. Keyed on these two phrases rather than on "read"
     * or "query" alone because ADR-011 reads "while the 26 descriptions existed, graph.json held them but neither
     * graphify_get_node nor graphify_query_graph rendered them" — a bare superseded figure in a line that mentions a
     * query, which a looser context pattern would have flagged. The second alternative is not redundant: the
     * runtime-view note stated its figure as "three reads per trick: seventy-nine", never using the words "round
     * trip", so a pattern matching only the first alternative would have missed one of the five sites EOP-96 fixed.
     */
    private static final Pattern READ_COST = Pattern.compile("round.?trips?|reads?\\s+per\\s+trick", Pattern.CASE_INSENSITIVE);

    /** The table size every figure in the third invariant is derived for. ADR-031 section six argues at three. */
    private static final int THREE_PLAYERS = 3;

    @Test
    @DisplayName("never claims the deal is uneven at every supported table size, when one of them divides evenly")
    void shouldNotClaimTheDealIsUnevenAtEveryTableSize() throws IOException {
        final List<Integer> evenTableSizes = tableSizesDividingTheDeckEvenly();
        assertThat(evenTableSizes)
                .as("this invariant only bites while some supported table size divides %d evenly; if a future trim "
                        + "leaves none, delete the test rather than leaving it passing vacuously", CURRENT_DECK_SIZE)
                .isNotEmpty();

        final List<Path> files = prosePaths();
        assertThat(files)
                .as("found no prose to check under %s or %s — is the working directory the project root?",
                        DOCS_DIRECTORY, SOURCE_DIRECTORY)
                .isNotEmpty();

        final List<String> offences = new ArrayList<>();
        for (final Path file : files) {
            if (isExempt(file)) {
                continue;
            }
            for (final String sentence : sentencesOf(Files.readAllLines(file))) {
                if (contradictsTheDeal(sentence)) {
                    offences.add("%s: %s".formatted(file, sentence));
                }
            }
        }

        assertThat(offences)
                .as("a sentence claims the deal is uneven across every supported table size, but %d divides evenly "
                        + "by %s — at that table size every seat holds the same number of cards and the final trick "
                        + "is full. This is the wording that survived both deck trims untouched, because it names no "
                        + "number. Qualify it to the table sizes whose deal is actually uneven.",
                        CURRENT_DECK_SIZE, evenTableSizes)
                .isEmpty();
    }

    @Test
    @DisplayName("states no superseded deck size in Java prose")
    void shouldNotStateASupersededDeckSizeInJavaProse() throws IOException {
        final List<Path> files = javaPaths();
        assertThat(files)
                .as("found no Java under %s — is the working directory the project root?", SOURCE_DIRECTORY)
                .isNotEmpty();

        final List<String> offences = new ArrayList<>();
        for (final Path file : files) {
            if (isExempt(file)) {
                continue;
            }
            final List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                final String line = JIRA_KEY.matcher(lines.get(index)).replaceAll("");
                for (final int superseded : SUPERSEDED_DECK_SIZES) {
                    if (statesTheNumber(line, superseded)) {
                        offences.add("%s:%d states %d: %s".formatted(file, index + 1, superseded, line.trim()));
                    }
                }
            }
        }

        assertThat(offences)
                .as("Java prose states a superseded deck size. The deck holds %d cards (ADR-041); %s are historical. "
                        + "Javadoc in Trick, Hands, SeatOrder, GetScorePort and NoTamperingCardDealtException all "
                        + "went stale this way and the build stayed green, as did a GlobalExceptionHandlerTest fixture "
                        + "that constructed the exception with a deck of 78. Only %s may state a superseded size.",
                        CURRENT_DECK_SIZE, SUPERSEDED_DECK_SIZES, EXEMPT_FILES)
                .isEmpty();
    }

    /**
     * The third invariant: no round trip count derived from a superseded deck size, in prose that is costing out a
     * read. EOP-96's defect class — a figure that is not a deck size but is computed from one, which therefore
     * survives a deck trim untouched and is invisible to the second invariant.
     *
     * <p>Sentence-scoped, using the same splitter as the first invariant. An earlier draft scoped it to single lines,
     * reasoning that all five pre-fix sites carried the number on the same line as the phrase pricing the read. That
     * was true when it was written and false by the time the fix landed: rewrapping ADR-031 section six left
     * "= seventy" ending one line and "round trips" beginning the next, so the one site this invariant exists for had
     * moved out of its reach — and a green build said nothing, because a guard that cannot see a claim reports no
     * offence. Line scope rests on where an author's paragraph happens to wrap, which is a property nobody maintains;
     * {@code sentencesOf} already argues exactly this a few methods below. Nothing is lost on the Mermaid note in
     * runtime-view.md, which is one long line with no sentence boundary: a splitter that finds none yields it whole.
     *
     * <p>Markdown table rows are scanned like any other prose. An earlier draft skipped them, fearing ADR-023's
     * historical deal tables, but the read-pricing anchor already acquits those — a row reading "| 3 | 26 | 0 |"
     * prices nothing — while the skip blinded this invariant to the ADR-031 row of docs/adr/README.md, which is one of
     * the nine sites EOP-96 corrected and had carried a stale derived figure across two deck trims.
     *
     * @throws IOException when a file cannot be read
     */
    @Test
    @DisplayName("no round-trip count derived from a superseded deck size, in prose costing out a read")
    void shouldNotStateASupersededRoundTripCount() throws IOException {
        final List<Integer> unspellable = Stream.concat(Stream.of(CURRENT_DECK_SIZE), SUPERSEDED_DECK_SIZES.stream())
                .map(DeckArithmeticClaimsTest::perTrickReadCount)
                .filter(cost -> spell(cost) == null)
                .toList();
        assertThat(unspellable)
                .as("prose states a total as a word, so a cost this guard cannot spell fails to match in silence "
                        + "rather than failing the build. Every cost a supported deck size implies needs a spelling in "
                        + "spell() before the trim that makes it superseded, not after; %s have none. EOP-96 shipped "
                        + "without a spelling for the cost the current deck implies, which would have left this "
                        + "invariant reaching one of the four corrected sites while its own javadoc promised all four.",
                        unspellable)
                .isEmpty();

        final List<String> offences = new ArrayList<>();
        for (final Path file : prosePaths()) {
            if (isExempt(file)) {
                continue;
            }
            for (final String sentence : sentencesOf(Files.readAllLines(file))) {
                if (statesASupersededRoundTripCount(sentence)) {
                    offences.add("%s: %s".formatted(file, sentence));
                }
            }
        }

        assertThat(offences)
                .as("prose prices a read using a round-trip count the current deck no longer produces. At %d cards a "
                        + "three-player hand runs to %d tricks, so a per-trick assembler costs 1 + 3 x %d = %d round "
                        + "trips; the superseded decks %s give %s. Five sites stated the 78-card figure and carried it "
                        + "through two deck trims, because a number derived from the deck size is not the deck size "
                        + "and so the second invariant never looked at it. State the derivation beside any total.",
                        CURRENT_DECK_SIZE, tricksAtThreePlayers(CURRENT_DECK_SIZE),
                        tricksAtThreePlayers(CURRENT_DECK_SIZE), perTrickReadCount(CURRENT_DECK_SIZE),
                        SUPERSEDED_DECK_SIZES,
                        SUPERSEDED_DECK_SIZES.stream().map(DeckArithmeticClaimsTest::perTrickReadCount).toList())
                .isEmpty();
    }

    /**
     * Reports whether a file is one of the two that may state what this test otherwise forbids.
     *
     * <p>Compares a repository-relative path, not a bare filename, so the exemption cannot be inherited by an
     * unrelated future file that happens to share a name.
     *
     * @param file the file being scanned
     * @return true when the file is exempt
     */
    private static boolean isExempt(final Path file) {
        final String relative = file.normalize().toString().replace('\\', '/');
        return EXEMPT_FILES.stream().anyMatch(relative::endsWith);
    }

    @Test
    @DisplayName("still recognises the three claims that were live on main before EOP-93")
    void shouldRecogniseTheHistoricalDefectItGuardsAgainst() {
        final String fromAdr023 =
                "At 74 cards, all four supported table sizes (3, 4, 5 and 6 players) produce unequal hands.";
        final String fromAdr019 =
                "EOP-14 deals the whole deck out, so at every supported player count the hands are unequal.";
        final String fromThePrd =
                "At 74 cards no supported table size divides evenly, so a player can win the trick they end on.";
        final String trueOfSixtyEight =
                "At 68 cards, all four supported table sizes (3, 4, 5 and 6 players) produce equal or near-equal hands.";
        final String properlyQualified =
                "At 68 cards three of the four supported table sizes produce unequal hands; four players is the "
                        + "exception, because 68 divides evenly by four.";

        assertThat(contradictsTheDeal(fromAdr023))
                .as("the ADR-023 wording EOP-93 fixed must still be caught, or this guard has rotted into a no-op")
                .isTrue();
        assertThat(contradictsTheDeal(fromAdr019))
                .as("the ADR-019 wording EOP-93 fixed must still be caught — it names no number at all, which is "
                        + "precisely why two deck trims sailed past it")
                .isTrue();
        assertThat(contradictsTheDeal(fromThePrd))
                .as("the PRD wording EOP-93 fixed must still be caught — it puts the negation in the quantifier")
                .isTrue();
        assertThat(contradictsTheDeal(trueOfSixtyEight))
                .as("a universal claim that is true at 68 cards must not be flagged; 'near-equal' is not 'unequal'")
                .isFalse();
        assertThat(contradictsTheDeal(properlyQualified))
                .as("the corrected wording must not be flagged, or the guard would forbid its own fix")
                .isFalse();
    }

    /**
     * Proves the third invariant fires on the wordings EOP-96 actually found on main, and stays silent on the wording
     * that replaced them. Without this the invariant could be quietly defeated by a change to {@link #READ_COST} or
     * {@link #statesTheNumberStrictly} and would still pass, having stopped looking.
     *
     * <p>Four of the seven flagged literals are the real pre-fix sentences from ADR-031 section six, the ADR index row,
     * the runtime-view note and the {@code findTricks} javadoc. The index row keeps its leading pipe, because the walk
     * reads table rows and an assertion that dropped the pipe would exercise the predicate on a line the walk was
     * never able to deliver. The other three cover what no real site happened to exercise: the figure in digits, the
     * 74-card figure, and a sentence whose figure and anchor sat on different physical lines, which is the shape that
     * defeated line scope. Each literal is asserted on its own rather than through {@code allMatch}, so a failure
     * names the wording that stopped being recognised instead of the whole list.
     *
     * <p>These are the only copies of the stale figures left in the repository. Quoting them is safe here and
     * nowhere else, because this file is listed in {@link #EXEMPT_FILES} and the invariant honours it — the note
     * added to ADR-031 deliberately describes the stale figures instead of reprinting them.
     */
    @Test
    @DisplayName("still recognises the stale round-trip counts EOP-96 removed")
    void shouldRecogniseTheStaleRoundTripCountsItGuardsAgainst() {
        final String adrSectionSix = "A three-player game reaches twenty-six tricks, so mapping it over the history "
                + "would cost seventy-nine round trips - one for the trick rows and three for each trick.";
        final String adrIndexRow = "| [ADR-031](ADR-031-the-score-is-read-through-its-own-route.md) | Accepted | Its "
                + "adapter reads four queries for the whole session rather than three per trick, which at three "
                + "players is four round trips instead of seventy-nine. |";
        final String mermaidNote = "Mapping the single-trick assembler over every row would have cost three reads per "
                + "trick: seventy-nine in a twenty-six-trick hand.";
        final String adapterJavadoc = "Assembling each trick on its own would cost seventy-nine round trips at three "
                + "players, for an answer whose size the deck already bounds.";
        final String inDigits = "Mapping the assembler over the history would cost 79 round trips.";
        final String previousDeck = "Assembling each trick on its own would cost seventy-six round trips.";
        final String wrappedAcrossLines = "A three-player game reaches twenty-six tricks, so mapping it over the "
                + "history would cost seventy-nine round trips for one score read.";

        assertThat(statesASupersededRoundTripCount(adrSectionSix))
                .as("ADR-031 section six's wording must still be caught, or this guard has rotted into a no-op")
                .isTrue();
        assertThat(statesASupersededRoundTripCount(adrIndexRow))
                .as("the ADR index row must still be caught — the figure trails the sentence as a bare comparison")
                .isTrue();
        assertThat(statesASupersededRoundTripCount(mermaidNote))
                .as("the runtime-view note must still be caught, and it is the only site that never said 'round "
                        + "trip' — it is why READ_COST carries a second alternative")
                .isTrue();
        assertThat(statesASupersededRoundTripCount(adapterJavadoc))
                .as("the production javadoc must still be caught — a stale cost for the design that was not chosen, "
                        + "sitting in the code implementing the one that was")
                .isTrue();
        assertThat(statesASupersededRoundTripCount(inDigits))
                .as("the figure must be caught in digits as well as spelled out")
                .isTrue();
        assertThat(statesASupersededRoundTripCount(previousDeck))
                .as("the 74-card figure must be caught too, or the guard only ever knew about one trim")
                .isTrue();
        assertThat(statesASupersededRoundTripCount(wrappedAcrossLines))
                .as("a figure and its anchor that sat on different physical lines must be caught once the sentence is "
                        + "rejoined — this is the shape line scope missed, and the shape ADR-031 section six took the "
                        + "moment EOP-96 rewrapped it")
                .isTrue();

        assertThat(statesTheNumberStrictly(adapterJavadoc, perTrickReadCount(CURRENT_DECK_SIZE)))
                .as("'seventy' must not be found inside 'seventy-nine'. Every word spell() knows shares a prefix, so "
                        + "once the current deck's cost is spellable a substring test would report it in the very "
                        + "sentence that states a superseded one — an offence raised against the wrong number")
                .isFalse();

        final String correctedAdr = "A three-player hand runs to twenty-three tricks, so mapping it over the history "
                + "would cost 1 + 3 x 23 = seventy round trips for one score read.";
        final String correctedNote = "Mapping the single-trick assembler over every row would have cost three reads "
                + "per trick: 1 + 3 x 23 = seventy in a twenty-three-trick hand.";
        final String unrelatedQuery = "While the 79 descriptions existed, graph.json held them but neither "
                + "graphify_get_node nor graphify_query_graph rendered them.";
        final String jiraKeyOnly = "EOP-79 and EOP-76 both touched the round trip count.";
        final String cidrSuffix = "Route 10.0.0.79/25 costs one round trip, and ADR-079 records why.";

        assertThat(statesASupersededRoundTripCount(correctedAdr))
                .as("the corrected ADR wording must not be flagged, or the guard would forbid its own fix")
                .isFalse();
        assertThat(statesASupersededRoundTripCount(correctedNote))
                .as("the corrected note must not be flagged either, and it still says 'reads per trick'")
                .isFalse();
        assertThat(statesASupersededRoundTripCount(unrelatedQuery))
                .as("a count beside the word 'query' must not be flagged: ADR-011 line 44 is a real line of this "
                        + "shape, and this literal carries 79 rather than its true 26 so that READ_COST is the only "
                        + "thing that can acquit it — with 26 it would pass whether READ_COST were right or not")
                .isFalse();
        assertThat(statesASupersededRoundTripCount(jiraKeyOnly))
                .as("a Jira key is not a read count, and both keys here would otherwise match")
                .isFalse();
        assertThat(statesASupersededRoundTripCount(cidrSuffix))
                .as("a figure inside a CIDR suffix or an ADR identifier is not a read count")
                .isFalse();
    }

    /**
     * Reports whether one sentence claims the deal is uneven across table sizes that include one dividing evenly.
     *
     * <p>Two shapes are wrong. A positive quantifier ("all four supported table sizes") paired with an uneven-deal
     * predicate, and a negative quantifier ("no supported table size") paired with the opposite predicate. Both say
     * the same false thing.
     *
     * @param sentence a single normalised sentence
     * @return true when the sentence contradicts the deal arithmetic of {@link #CURRENT_DECK_SIZE}
     */
    private static boolean contradictsTheDeal(final String sentence) {
        final String text = JIRA_KEY.matcher(sentence).replaceAll("");
        final boolean overstatesUnevenness =
                EVERY_TABLE_SIZE.matcher(text).find() && DEAL_IS_UNEVEN.matcher(text).find();
        final boolean deniesEvenDivision =
                NO_TABLE_SIZE.matcher(text).find() && DIVIDES_EVENLY.matcher(text).find();
        return overstatesUnevenness || deniesEvenDivision;
    }

    /**
     * Returns the supported table sizes that divide the current deck without remainder.
     *
     * @return the evenly dividing table sizes, in ascending order
     */
    private static List<Integer> tableSizesDividingTheDeckEvenly() {
        return SUPPORTED_TABLE_SIZES.stream().filter(size -> CURRENT_DECK_SIZE % size == 0).toList();
    }

    /**
     * Reports whether a line states a number as a standalone token, in digits or in words.
     *
     * <p>The word-boundary match is what keeps UUID fixtures out of the results: the {@code 74} inside
     * {@code b9f389069b74} has a word character before it and so is not a standalone token. The lookarounds extend
     * that to decimals, which a word boundary does not exclude on its own — {@code 0.74} and {@code 74.5} are one
     * number each, not a mention of the old deck size.
     *
     * @param line a line of source, with Jira keys already removed
     * @param number the number to look for
     * @return true when the line states that number
     */
    private static boolean statesTheNumber(final String line, final int number) {
        final String digits = "(?<![\\d.])\\b" + number + "\\b(?!\\.\\d)";
        if (Pattern.compile(digits).matcher(line).find()) {
            return true;
        }
        final String spelled = spell(number);
        return spelled != null && line.toLowerCase(Locale.ROOT).contains(spelled);
    }

    /**
     * Spells the superseded deck sizes, which prose is as likely to write in words as in digits.
     *
     * @param number the number to spell
     * @return the spelled form, or null when there is no spelling worth checking
     */
    private static String spell(final int number) {
        return switch (number) {
            case 70 -> "seventy";
            case 74 -> "seventy-four";
            case 76 -> "seventy-six";
            case 78 -> "seventy-eight";
            case 79 -> "seventy-nine";
            default -> null;
        };
    }

    /**
     * Returns how many tricks a three-player hand runs to for a given deck size. The whole deck is dealt (ADR-023),
     * so the longest hand sets the trick count and the last trick is short when the deal does not divide evenly.
     *
     * @param deckSize the number of cards in the deck
     * @return the number of tricks a three-player hand runs to
     */
    private static int tricksAtThreePlayers(final int deckSize) {
        return Math.ceilDiv(deckSize, THREE_PLAYERS);
    }

    /**
     * Returns what a per-trick score assembler would cost in round trips at three players: one read for the trick
     * rows, then three per trick for its plays, its cards and its components (ADR-031 section six).
     *
     * <p>Derived rather than listed, which is the point of the third invariant. The figure that went stale five
     * times was a total someone had computed by hand, so the deck could shrink twice without it looking wrong.
     *
     * @param deckSize the number of cards in the deck
     * @return the number of round trips a per-trick assembler would cost
     */
    private static int perTrickReadCount(final int deckSize) {
        return 1 + THREE_PLAYERS * tricksAtThreePlayers(deckSize);
    }

    /**
     * Reports whether a line states a round-trip count belonging to a superseded deck size, while costing out a read.
     *
     * <p>Stricter than {@link #statesTheNumber} on purpose: it is looking for small numbers that occur all over a
     * repository inside version strings, timestamps, CIDR suffixes and identifiers, so adjacency to any of
     * {@code / : - .} or a word character disqualifies a match. Jira keys go first, so EOP-79 is never a read count.
     *
     * @param line one line of prose, already normalised
     * @return true when the line prices a read using a figure the current deck no longer produces
     */
    private static boolean statesASupersededRoundTripCount(final String line) {
        final String stripped = JIRA_KEY.matcher(line).replaceAll("");
        if (!READ_COST.matcher(stripped).find()) {
            return false;
        }
        return SUPERSEDED_DECK_SIZES.stream()
                .anyMatch(deckSize -> statesTheNumberStrictly(stripped, perTrickReadCount(deckSize)));
    }

    /**
     * Reports whether a line states a number as a figure in its own right, rather than as part of something else.
     *
     * <p>The spelled form is matched with hyphen-aware boundaries rather than by substring, because every word this
     * class spells shares a prefix: "seventy" occurs inside "seventy-nine". A substring test would therefore report
     * the cost of the current deck wherever a superseded one is written out, and once both are in {@link #spell(int)}
     * that misfires in the direction that matters — announcing an offence in the very sentence that corrected one.
     *
     * @param line one line of prose
     * @param number the number to look for, in digits or spelled out
     * @return true when the line states that number
     */
    private static boolean statesTheNumberStrictly(final String line, final int number) {
        final String digits = "(?<![\\w./:-])" + number + "(?![\\w/:-])(?!\\.\\d)";
        if (Pattern.compile(digits).matcher(line).find()) {
            return true;
        }
        final String spelled = spell(number);
        if (spelled == null) {
            return false;
        }
        final String words = "(?<![\\w-])" + Pattern.quote(spelled) + "(?![\\w-])";
        return Pattern.compile(words, Pattern.CASE_INSENSITIVE).matcher(line).find();
    }

    /**
     * Splits a file into sentences, joining wrapped prose first.
     *
     * <p>Markdown and javadoc both wrap freely, so a claim routinely straddles two lines: the PRD's true sentence
     * about 68 cards breaks after "produce equal or". A line-scoped matcher would miss exactly the paragraph-length
     * claims that go stale, so lines are joined into paragraphs on blank lines and then split into sentences.
     *
     * @param lines the raw lines of the file
     * @return the sentences of the file, normalised
     */
    private static List<String> sentencesOf(final List<String> lines) {
        final List<String> sentences = new ArrayList<>();
        final StringBuilder paragraph = new StringBuilder();
        for (final String rawLine : lines) {
            final String line = normalise(rawLine);
            if (line.isEmpty()) {
                addSentences(paragraph.toString(), sentences);
                paragraph.setLength(0);
            } else {
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
            }
        }
        addSentences(paragraph.toString(), sentences);
        return sentences;
    }

    /**
     * Splits one paragraph into sentences and adds the non-blank ones to the accumulator.
     *
     * @param paragraph the joined paragraph
     * @param sentences the accumulator to add to
     */
    private static void addSentences(final String paragraph, final List<String> sentences) {
        if (paragraph.isBlank()) {
            return;
        }
        for (final String sentence : SENTENCE_BOUNDARY.split(paragraph)) {
            if (!sentence.isBlank()) {
                sentences.add(sentence.trim());
            }
        }
    }

    /**
     * Strips the markers that markdown and javadoc put in front of prose, so a claim reads the same either way.
     *
     * <p>Blockquote and heading markers go, then one leading comment marker, then emphasis and code ticks — bold is
     * how prose stresses the very quantifiers this test looks for, and "all four supported table sizes" must match
     * whether or not someone wrapped it in asterisks.
     *
     * @param rawLine a line as read from the file
     * @return the line's prose, with markers removed and whitespace collapsed
     */
    private static String normalise(final String rawLine) {
        String text = rawLine.trim();
        while (text.startsWith(">") || text.startsWith("#")) {
            text = text.substring(1).trim();
        }
        if (text.startsWith("/**") || text.startsWith("<p>")) {
            text = text.substring(3);
        } else if (text.startsWith("/*") || text.startsWith("//")) {
            text = text.substring(2);
        } else if (text.startsWith("*")) {
            text = text.substring(1);
        }
        return text.replace("*/", " ")
                .replace("*", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Collects the prose the first invariant reads: markdown under {@code docs/} and Java under {@code src/}.
     *
     * @return every markdown and Java file, sorted for a stable failure message
     * @throws IOException if a directory cannot be walked
     */
    private static List<Path> prosePaths() throws IOException {
        final List<Path> paths = new ArrayList<>(markdownPaths());
        paths.addAll(javaPaths());
        return paths;
    }

    /**
     * Collects the markdown files under {@code docs/}.
     *
     * @return every markdown file, sorted
     * @throws IOException if the directory cannot be walked
     */
    private static List<Path> markdownPaths() throws IOException {
        try (Stream<Path> walk = Files.walk(DOCS_DIRECTORY)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Collects the Java files under {@code src/}.
     *
     * @return every Java file, sorted
     * @throws IOException if the directory cannot be walked
     */
    private static List<Path> javaPaths() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCE_DIRECTORY)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
