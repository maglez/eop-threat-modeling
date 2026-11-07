package org.maglez.eop.domain;

/**
 * The six STRIDE threat categories. In the physical Elevation of Privilege deck
 * each category is a suit.
 *
 * <p>Declaration order is the order of the acronym, and that order is load
 * bearing: {@link #deckOrder()} derives from it, and the seeded
 * {@code card.suit_order} column mirrors it so the database can return the deck
 * in the order a player expects. Reordering these constants without updating the
 * seed data is a breaking change; a test asserts the two agree.
 *
 * <p>{@link #ELEVATION_OF_PRIVILEGE} is the trump suit — only a card of that
 * suit, or of the suit that was led, can take a trick.
 */
public enum StrideCategory {

    /** Pretending to be something or someone other than yourself. */
    SPOOFING,
    /** Modifying something you are not supposed to modify. */
    TAMPERING,
    /** Claiming you did not do something, whether or not you did. */
    REPUDIATION,
    /** Exposing information to someone not authorised to see it. */
    INFORMATION_DISCLOSURE,
    /** Absorbing resources needed to provide service. */
    DENIAL_OF_SERVICE,
    /** Doing something you are not authorised to do. The trump suit. */
    ELEVATION_OF_PRIVILEGE;

    /**
     * This suit's one-based position in the STRIDE acronym.
     *
     * <p>One-based rather than zero-based so that the value reads naturally in
     * the database and a missing value is distinguishable from a first suit.
     *
     * @return the position, 1 through 6
     */
    public int deckOrder() {
        return ordinal() + 1;
    }
}
