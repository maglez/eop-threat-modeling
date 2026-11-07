package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a card already lying in a trick is played into it a second time.
 *
 * <p>Signalled by the persistence layer, from {@code uq_trick_play_trick_card}
 * rejecting an insert, and translated here so the use case layer never sees a
 * database-specific type.
 *
 * <p>Kept separate from {@link AlreadyPlayedInTrickException} because the two
 * describe different situations even though both are conflicts. That one is one
 * seat playing twice; this one is one card arriving from two seats, which is only
 * possible if the same card was dealt into two hands or a card was fabricated. The
 * caller sees a 409 either way, but the operator reading the logs needs to be able
 * to tell a double-submit from a duplicated deck, and a shared exception type would
 * erase that difference at the point it is most worth having.
 *
 * <p>{@link Hands} already refuses to hold one card at two seats, so a legal deal
 * cannot reach this constraint. It remains the only guard on the storage side, and
 * ADR-023 records that nothing in the schema scopes a card to one hand per session,
 * so this is a genuine backstop rather than a redundant one.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class CardAlreadyPlayedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID trickId;

    private final UUID cardId;

    /**
     * Creates the exception for a card already in the trick.
     *
     * @param trickId the trick already holding the card
     * @param cardId  the card played twice
     */
    public CardAlreadyPlayedException(final UUID trickId, final UUID cardId) {
        super("Card " + cardId + " has already been played into trick " + trickId);
        this.trickId = trickId;
        this.cardId = cardId;
    }

    /**
     * The trick already holding the card.
     *
     * @return the trick identifier
     */
    public UUID trickId() {
        return trickId;
    }

    /**
     * The card played twice.
     *
     * @return the card identifier
     */
    public UUID cardId() {
        return cardId;
    }
}
