package org.maglez.eop.domain;

/**
 * The rank printed on a threat card.
 *
 * <p>Rank does double duty in Elevation of Privilege. It decides which card
 * takes a trick, and it encodes a rough ordering of how commonly the threat is
 * encountered, how much impact it has and how easy it is to exploit — higher
 * cards carry the threats the deck's authors considered more significant.
 *
 * <p>The physical printed deck runs from Two to King (twelve ranks). Aces do
 * not appear in the game; King is the highest rank.
 *
 * <p>Pure domain type: no Spring, no Jakarta, no persistence annotations.
 */
public enum Rank {

    /** Two — the lowest rank. */
    TWO(2, "2"),
    /** Three. */
    THREE(3, "3"),
    /** Four. */
    FOUR(4, "4"),
    /** Five. */
    FIVE(5, "5"),
    /** Six. */
    SIX(6, "6"),
    /** Seven. */
    SEVEN(7, "7"),
    /** Eight. */
    EIGHT(8, "8"),
    /** Nine. */
    NINE(9, "9"),
    /** Ten. */
    TEN(10, "10"),
    /** Jack. */
    JACK(11, "J"),
    /** Queen. */
    QUEEN(12, "Q"),
    /** King — the highest rank. */
    KING(13, "K");

    private final int value;
    private final String symbol;

    Rank(final int rankValue, final String rankSymbol) {
        this.value = rankValue;
        this.symbol = rankSymbol;
    }

    /**
     * Resolves a rank from its numeric value.
     *
     * @param value the numeric value, 2 through 13
     * @return the matching rank
     * @throws IllegalArgumentException if no rank has that value
     */
    public static Rank ofValue(final int value) {
        for (final Rank rank : values()) {
            if (rank.value == value) {
                return rank;
            }
        }
        throw new IllegalArgumentException("No card rank has the value " + value);
    }

    /**
     * The numeric value used to compare ranks. King is the highest, so 13.
     *
     * @return the numeric value
     */
    public int value() {
        return value;
    }

    /**
     * How the rank is printed on the card face.
     *
     * @return the printed symbol
     */
    public String symbol() {
        return symbol;
    }

    /**
     * Whether this rank beats another. Comparison is only meaningful between
     * cards of the same suit, or between trump cards.
     *
     * @param other the rank to compare against
     * @return true if this rank is strictly higher
     */
    public boolean beats(final Rank other) {
        return this.value > other.value;
    }
}
