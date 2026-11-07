package org.maglez.eop.domain;

/**
 * Raised when a player who holds the led suit tries to play a different suit.
 *
 * <p>Following suit is the rule that makes the game a trick-taking game rather
 * than a free-for-all: it is why holding a high card of the led suit is worth
 * something, and why trumping is a decision rather than a reflex.
 *
 * <p>The check belongs on the server, against the player's actual hand. A user
 * interface that merely greys out the illegal cards is a courtesy, not a
 * control: the cards a client is willing to offer say nothing about the request
 * a client is able to send.
 */
public class MustFollowSuitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final StrideCategory ledSuit;

    private final StrideCategory attemptedSuit;

    /**
     * Creates the exception for a play that ignores the led suit.
     *
     * @param ledSuit       the suit that was led and must be followed
     * @param attemptedSuit the suit the player tried to play instead
     */
    public MustFollowSuitException(final StrideCategory ledSuit, final StrideCategory attemptedSuit) {
        super("The led suit is " + ledSuit + " and the hand holds it, so " + attemptedSuit + " cannot be played");
        this.ledSuit = ledSuit;
        this.attemptedSuit = attemptedSuit;
    }

    /**
     * The suit that was led and must be followed.
     *
     * @return the led suit
     */
    public StrideCategory ledSuit() {
        return ledSuit;
    }

    /**
     * The suit the player tried to play instead.
     *
     * @return the attempted suit
     */
    public StrideCategory attemptedSuit() {
        return attemptedSuit;
    }
}
