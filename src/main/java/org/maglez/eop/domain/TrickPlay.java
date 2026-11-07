package org.maglez.eop.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One player's card in one trick, together with what they said about it.
 *
 * <p>Immutable. A play is a historical fact: once made it is never edited, which
 * is what lets the trick's winner be recomputed from stored rows rather than
 * trusted from a cached result.
 *
 * <p>Carries the whole {@link Card} rather than a card identifier so that
 * deciding who took the trick needs no lookup. Cards are seeded reference data
 * that gameplay never mutates (PRD §5), so an embedded copy cannot go stale.
 *
 * <p>Carries {@code seatOrder} as well as {@code playerId}. That is a copy of a
 * fact {@link Player} also holds, accepted deliberately: a seat is assigned once
 * at join and never re-derived (ADR-019), so the two cannot drift, and a trick
 * needs the seat to work out who leads next without loading the session's
 * players.
 *
 * <p>Carries no {@link Player}. A player holds the digest of its identity token,
 * and that value has no business travelling inside gameplay data.
 *
 * @param trickPlayId  the play's identifier
 * @param playerId     the player who made the play
 * @param seatOrder    the seat that player occupies
 * @param card         the card played
 * @param threatLinked whether the player connected the threat to the system
 * @param components   the components the player named, possibly empty
 * @param notes        what the player said about the threat, or null
 * @param playedAt     when the play was made
 */
public record TrickPlay(
        UUID trickPlayId,
        UUID playerId,
        int seatOrder,
        Card card,
        boolean threatLinked,
        List<String> components,
        String notes,
        Instant playedAt) {

    /** Most components one play may name. A bound, not a game rule. */
    public static final int MAX_COMPONENTS = 20;

    /** Longest component name accepted. */
    public static final int MAX_COMPONENT_NAME_LENGTH = 200;

    /** Longest note accepted against a single play. */
    public static final int MAX_NOTES_LENGTH = 2000;

    /**
     * Rejects a malformed play at construction, and normalises the free text.
     *
     * <p>{@code threatLinked} being false is a legal, ordinary outcome, not an
     * error: a player who cannot connect their card to the system still plays it,
     * the card still competes for the trick, and the play simply scores nothing
     * (PRD §3.3). Modelling that as a failure or as a skipped turn would change
     * the rules of the game.
     *
     * <p>Components are not required even when the threat was linked. No rule
     * says they must be, and a facilitation tool that refuses a play in a live
     * session because a text box is empty gets in the way of the conversation it
     * exists to support. Scoring settled what to do with a linked threat that
     * names nothing: it still scores its threat point, because the rule keys on
     * the threat being connected rather than on the naming, and this application
     * holds no model of the system against which a component name could be
     * checked in any case (ADR-030).
     *
     * <p>Both free-text fields are bounded in length and count because they cross
     * a system boundary from a client and are written to the database.
     *
     * <p>Both are also rejected outright if they contain a control character or a
     * bidirectional formatting character. Three separate reasons, none of which is
     * cosmetic. A NUL byte cannot be stored in a PostgreSQL {@code text} column at
     * all, so accepting one here turns into an aborted transaction mid-trick that
     * only shows up outside the H2 test database. A carriage return or newline in
     * text that reaches a log lets a player forge log lines at a severity of their
     * choosing (CWE-117). And a bidirectional override lets a player display a
     * component name to the rest of the table that reads differently from the one
     * that was stored, in the one field the exercise asks people to trust each
     * other's words in. None of the three has a legitimate use in a component name
     * or a one-line note, so the boundary refuses them rather than escaping them.
     *
     * <p>The bounds count {@code char} values, that is UTF-16 code units, so they
     * are a code-unit budget and neither a character budget nor a byte budget: an
     * astral character costs two {@code char} values and four UTF-8 bytes, so a
     * 200-unit budget spent entirely on astral text holds 100 characters and 400
     * bytes. A column sized from one of these constants still maps to it directly,
     * because both PostgreSQL's {@code character varying(n)} and H2's count
     * characters rather than bytes: a string of 200 code units is at most 200
     * characters, so {@code varchar(200)} can never refuse a name this constructor
     * accepted. The domain is the stricter of the two, by up to a factor of two on
     * astral text, which is the safe direction; widening the columns for multi-byte
     * text would only make storage looser than the domain (ADR-023).
     *
     * <p>The one genuinely byte-denominated limit is elsewhere: PostgreSQL caps a
     * B-tree index entry at roughly 2704 bytes, which {@link #MAX_NOTES_LENGTH}
     * characters can exceed once they are multi-byte. That is why the free-text
     * columns are bounded but deliberately left unindexed, not a reason to resize
     * them.
     *
     * @throws NullPointerException     if a required component is null
     * @throws IllegalArgumentException if the seat is out of range
     * @throws InvalidInputException    if the text is over-long or contains a
     *                                  rejected character
     */
    public TrickPlay {
        Objects.requireNonNull(trickPlayId, "trickPlayId is required");
        Objects.requireNonNull(playerId, "playerId is required");
        Objects.requireNonNull(card, "card is required");
        Objects.requireNonNull(playedAt, "playedAt is required");
        Objects.requireNonNull(components, "components is required, though it may be empty");
        if (seatOrder < 0 || seatOrder >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException(
                    "seatOrder must be between 0 and " + (GameSession.MAXIMUM_PLAYERS - 1) + ", was " + seatOrder);
        }
        if (components.size() > MAX_COMPONENTS) {
            throw new InvalidInputException(
                    "A play may name at most " + MAX_COMPONENTS + " components, was " + components.size());
        }
        components = components.stream()
                .map(component -> Objects.requireNonNull(component, "a component name must not be null"))
                .map(String::strip)
                .map(component -> {
                    if (component.isEmpty()) {
                        throw new InvalidInputException("A component name must not be blank");
                    }
                    if (component.length() > MAX_COMPONENT_NAME_LENGTH) {
                        throw new InvalidInputException("A component name must be at most "
                                + MAX_COMPONENT_NAME_LENGTH + " characters, was " + component.length());
                    }
                    rejectUnsafeText(component, "A component name");
                    return component;
                })
                .toList();
        if (notes != null) {
            notes = notes.strip();
            if (notes.length() > MAX_NOTES_LENGTH) {
                throw new InvalidInputException(
                        "notes must be at most " + MAX_NOTES_LENGTH + " characters, was " + notes.length());
            }
            rejectUnsafeText(notes, "A note");
            if (notes.isEmpty()) {
                notes = null;
            }
        }
    }

    /**
     * The components this play named.
     *
     * <p>The stored list is already immutable, so this override changes no
     * behaviour. It exists because a record's generated accessor hands back the
     * field itself, and a reader of that generated code has to go and check the
     * constructor to find out whether the list is safe to hand out. Copying here
     * states the guarantee at the point a caller reads it, and costs nothing:
     * {@link List#copyOf} returns an already-immutable list unchanged.
     *
     * @return the component names, possibly empty, never null and never modifiable
     */
    @Override
    public List<String> components() {
        return List.copyOf(components);
    }

    /**
     * What the player said about the threat, if they said anything.
     *
     * <p>Offered alongside the record accessor so that callers are not tempted to
     * treat a blank note and an absent note as different things. Blank notes are
     * normalised to absent at construction.
     *
     * @return the note, or empty if none was given
     */
    public Optional<String> notesIfGiven() {
        return Optional.ofNullable(notes);
    }

    /**
     * Whether this play can take the trick, given the suit that was led.
     *
     * <p>Only a card of the led suit or a trump can take a trick. A card played
     * off-suit by a player who could not follow is a legal play that cannot win.
     *
     * <p>This is eligibility, not the winner rule, and the two are deliberately
     * different questions. {@code Trick.resolved()} narrows to the <em>decisive</em>
     * suit — trump if any trump was played, otherwise the led suit — so a card of
     * the led suit is in contention by this method and yet cannot win once someone
     * has trumped. Do not reach for this method to pick a winner. It exists for the
     * question a screen asks: which of the cards in front of me are still worth
     * playing for this trick.
     *
     * @param ledSuit the suit that was led
     * @return true if this play is in contention
     */
    public boolean canTakeTrick(final StrideCategory ledSuit) {
        return card.isTrump() || card.suit() == ledSuit;
    }

    /**
     * The same play, carrying the card as it was actually dealt.
     *
     * <p>Used by {@code Trick.acceptPlay} to replace the card a request claimed with the card resolved
     * out of the player's hand, so that the suit and rank a client sent can never reach the trick, the
     * winner rule, or the database. Everything else about the play is the player's to state.
     *
     * @param dealt the card as the deck holds it
     * @return a new play carrying that card
     */
    TrickPlay withCard(final Card dealt) {
        return new TrickPlay(trickPlayId, playerId, seatOrder, dealt, threatLinked, components, notes, playedAt);
    }

    /**
     * A redacted rendering, because the default one is a disclosure.
     *
     * <p>A record's generated {@code toString} prints every component, which here
     * means the whole {@link Card} including its threat prompt, plus the player's
     * raw note and every component name they typed. That is the rendering that
     * appears the moment anything logs a play or interpolates one into an exception
     * message, and {@code Trick.plays()} cascades straight into it, so a single
     * debug statement over a trick would emit every note at the table.
     *
     * <p>What is printed is what is already public at the table: the card is face
     * up the instant it is played, so its suit and rank are not confidential. The
     * threat prompt is dropped as bulk rather than as a secret. The note and the
     * component names are reduced to a count and a presence flag because they are
     * attacker-controlled text, and text that reaches a log is text that can forge
     * one.
     *
     * @return a rendering that names no free text
     */
    @Override
    public String toString() {
        return "TrickPlay[trickPlayId=" + trickPlayId
                + ", playerId=" + playerId
                + ", seatOrder=" + seatOrder
                + ", card=" + card.suit() + " " + card.rank().symbol()
                + ", threatLinked=" + threatLinked
                + ", components=" + components.size()
                + ", notes=" + (notes == null ? "none" : "given")
                + ", playedAt=" + playedAt + "]";
    }

    /**
     * Refuses text that is unsafe to store, to log, or to show to another player.
     *
     * @param value the already-stripped text to check
     * @param field how to name the field in the rejection message
     */
    private static void rejectUnsafeText(final String value, final String field) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw new InvalidInputException(
                        field + " must not contain control characters, found one at position " + index);
            }
            if (isBidirectionalFormatting(character)) {
                throw new InvalidInputException(
                        field + " must not contain bidirectional formatting characters, found one at position "
                                + index);
            }
        }
    }

    /**
     * Whether this character can make stored text display as something else.
     *
     * <p>The left-to-right and right-to-left marks, the embedding and override
     * block, and the isolate block. These are the characters behind Trojan Source:
     * they reorder how text renders without changing what it contains.
     *
     * @param character the character to test
     * @return true if the character is a bidirectional formatting control
     */
    private static boolean isBidirectionalFormatting(final char character) {
        return character == '\u061c' || character == '\u200e' || character == '\u200f'
                || (character >= '\u202a' && character <= '\u202e')
                || (character >= '\u2066' && character <= '\u2069');
    }
}
