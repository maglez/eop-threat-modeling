package org.maglez.eop.domain;

/**
 * Raised when a deal contains no tampering card, so no opening leader exists.
 *
 * <p>The player holding the lowest-ranked tampering card in the deck leads the
 * first trick. That rank is deliberately never written down in code: the printed
 * 68-card deck starts on the three of tampering. The rule is "lowest tampering
 * card actually dealt", so it holds for any future deck variant that starts
 * differently.
 *
 * <p>Which leaves one case the rule cannot answer: a deck with no tampering suit
 * at all. That cannot happen with the seeded deck, so this exception exists to
 * fail loudly if some future deck variant or a mistaken test fixture reaches the
 * dealing code, rather than letting a silent fallback pick an arbitrary leader.
 */
public class NoTamperingCardDealtException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int cardsDealt;

    /**
     * Creates the exception for a deal with no tampering card in it.
     *
     * @param cardsDealt how many cards were dealt, for diagnosis
     */
    public NoTamperingCardDealtException(final int cardsDealt) {
        super("No " + StrideCategory.TAMPERING + " card was dealt among " + cardsDealt
                + " cards, so the opening leader cannot be derived");
        this.cardsDealt = cardsDealt;
    }

    /**
     * How many cards were dealt.
     *
     * @return the number of cards dealt
     */
    public int cardsDealt() {
        return cardsDealt;
    }
}
