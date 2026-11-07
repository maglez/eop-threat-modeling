package org.maglez.eop.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of the official Elevation of Privilege Score Card: a card that has been played, who played it, what it was connected to, and
 * what it scored.
 *
 * <p>The Score Card shipped with the game (<em>docs/EoP_Microsoft_Docs/EoP_Score Card.pdf</em>) has five columns — Name, Points, Card,
 * Component(s) and Notes on Threat — and this record carries all five, which is the whole reason it exists as a type rather than as a
 * pair of integers. A client that renders these rows in play order reproduces the paper sheet a facilitator would otherwise fill in by
 * hand (PRD §3.4). Component(s) is plural in the original and plural here: one card's threat may name several parts of the system.</p>
 *
 * <p>The shipped scoring rule is deliberately small and is <strong>not</strong> to be embellished: one point for a threat on your card,
 * plus one point for taking the trick. Those are the two booleans below, kept separate rather than pre-summed so that a reader of the
 * sheet can see <em>why</em> a row scored what it did. {@link #points()} adds them, and a row can therefore score zero, one or two.</p>
 *
 * <p>A play with no threat linked to it scores nothing for the threat, and that is an ordinary outcome rather than an error: a player who
 * cannot connect their card to the system still plays it, the card still competes for the trick, and it may still win one (PRD §3.3). So
 * {@code threatPoint} false with {@code trickPoint} true is a perfectly normal row. A linked threat that names no component still scores:
 * the rule keys on the threat being connected, not on the naming, and the application holds no model of the system against which a
 * component name could be checked in any case.</p>
 *
 * <p>Nothing here reveals a card a player still holds. Every row describes a card already on the table, which is public knowledge at the
 * moment it is played, so no redaction is called for beyond the display name being carried for display only — {@link DisplayName} is free
 * text and not unique, so it must never be used as a key. Key on {@code playerId} or {@code seatOrder}.</p>
 *
 * @param playerId    identifier of the player who made the play
 * @param seatOrder   seat the play was made from, zero-based
 * @param displayName the player's chosen name, for display only
 * @param card        the card that was played
 * @param components  parts of the system the threat was connected to, possibly empty
 * @param notes       the player's note about the threat, which becomes the Score Card's "Notes on Threat" column
 * @param threatPoint whether this play earned the point for connecting a threat
 * @param trickPoint  whether this play took the trick
 */
public record ScoredPlay(UUID playerId, int seatOrder, DisplayName displayName, Card card, List<String> components,
        Optional<String> notes, boolean threatPoint, boolean trickPoint) {

    /**
     * Rejects a row that could not describe a real play, and defends the component list against later mutation by the caller.
     */
    public ScoredPlay {
        Objects.requireNonNull(playerId, "playerId is required");
        Objects.requireNonNull(displayName, "displayName is required");
        Objects.requireNonNull(card, "card is required");
        Objects.requireNonNull(components, "components is required");
        Objects.requireNonNull(notes, "notes is required");
        if (seatOrder < 0 || seatOrder >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException("A seat is 0 through " + (GameSession.MAXIMUM_PLAYERS - 1) + ", was " + seatOrder);
        }
        components = List.copyOf(components);
    }

    /**
     * Scores a single play, given the player who made it and whether it took the trick.
     *
     * <p>Whether the trick was taken is not something a play can answer about itself — it depends on every other card in the trick and on
     * which suit was led — so it is passed in by whoever resolved the trick rather than derived here.</p>
     *
     * @param player    the seated player who made the play
     * @param play      the play as it was recorded
     * @param tookTrick whether this play was the winning play of its trick
     * @return the corresponding Score Card row
     * @throws ScoreNotDerivableException if the play was not made by the given player
     */
    public static ScoredPlay of(final Player player, final TrickPlay play, final boolean tookTrick) {
        Objects.requireNonNull(player, "player is required");
        Objects.requireNonNull(play, "play is required");
        if (!player.playerId().equals(play.playerId())) {
            throw ScoreNotDerivableException.playNotByThisPlayer(play.trickPlayId(), player.playerId());
        }
        return new ScoredPlay(player.playerId(), play.seatOrder(), player.displayName(), play.card(), play.components(),
                play.notesIfGiven(), play.threatLinked(), tookTrick);
    }

    /**
     * The STRIDE components this play was linked to.
     *
     * <p>The record's generated accessor is overridden so that the returned list cannot be used to reach the
     * instance's own state, keeping the row immutable however it was constructed.</p>
     *
     * @return an unmodifiable copy of the linked components, empty when the play linked none
     */
    @Override
    public List<String> components() {
        return List.copyOf(components);
    }

    /**
     * Points this row scored: one for a connected threat, one for taking the trick.
     *
     * @return zero, one or two
     */
    public int points() {
        return (threatPoint ? 1 : 0) + (trickPoint ? 1 : 0);
    }

    /**
     * Renders the row without repeating what a player typed.
     *
     * <p>The generated record {@code toString} would reproduce the note and every component name verbatim, and
     * {@link ScoreSheet#rows()} cascades straight into it, so one debug statement over a sheet would emit every note at the table —
     * up to two thousand characters of note and twenty component names per row. {@link TrickPlay} redacts the same two fields for the
     * same reason, and this row copies that text out of it, so it inherits the obligation. The component list becomes a count and the
     * note becomes its presence; the card is left to {@link Card#toString()}, which already drops its threat prompt. The card itself is
     * face up the moment it is played, so naming it discloses nothing.</p>
     *
     * <p>{@link Standing} keeps the generated {@code toString} deliberately: the only free text it carries is a
     * {@link DisplayName}, which is validated at construction to forty characters with no control characters, and which every player
     * at the table can already see.</p>
     *
     * @return a description that names no player-supplied text
     */
    @Override
    public String toString() {
        return "ScoredPlay[playerId=" + playerId + ", seatOrder=" + seatOrder + ", card=" + card + ", components=" + components.size()
                + ", notes=" + (notes.isPresent() ? "given" : "none") + ", points=" + points() + "]";
    }
}
