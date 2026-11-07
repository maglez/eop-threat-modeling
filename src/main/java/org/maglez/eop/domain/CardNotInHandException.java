package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a player tries to play a card that is not in their hand.
 *
 * <p>This is the check that stops a card being played twice, and today it is
 * the only such check: it compares a request against a hand held in memory, so
 * two simultaneous requests can both pass it against the same starting hand and
 * both be accepted. It is therefore not yet a concurrency control. The second,
 * independent half — a database unique constraint on a played card, which is
 * what will actually settle two simultaneous requests under ADR-020 — arrives
 * with the {@code trick_play} table in EOP-14 Slice B. There is no such table
 * at the time of writing, and this Javadoc previously described that constraint
 * as though it already existed.
 *
 * <p>It is also the check that stops a player playing a card that was dealt to
 * somebody else, so a hand identifier is carried alongside the card.
 *
 * <p>A candidate card naming no identifier at all is reported through this
 * exception too, rather than as a null-argument programming error: a request
 * that names nothing has named no card this hand holds, and routing both
 * through one fail-closed path means the boundary answers a missing card field
 * with a client error rather than a server one.
 */
public class CardNotInHandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID handId;

    private final UUID cardId;

    /**
     * Creates the exception for a card the hand does not hold.
     *
     * @param handId the hand that was asked for the card
     * @param cardId the card that is not in it
     */
    public CardNotInHandException(final UUID handId, final UUID cardId) {
        super("Hand " + handId + " does not hold card " + cardId);
        this.handId = handId;
        this.cardId = cardId;
    }

    /**
     * The hand that was asked for the card.
     *
     * @return the hand identifier
     */
    public UUID handId() {
        return handId;
    }

    /**
     * The card the hand does not hold.
     *
     * @return the card identifier
     */
    public UUID cardId() {
        return cardId;
    }
}
