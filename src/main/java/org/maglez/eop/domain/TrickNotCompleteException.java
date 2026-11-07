package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a trick is asked to resolve while a seat that holds cards has not
 * played into it.
 *
 * <p>Resolving early is not a corrupt request, it is a premature one, and the
 * distinction matters because the remedy is different: the caller should read the
 * trick again once the missing card arrives, and its request will then succeed
 * unchanged. That is a 409, not a 422. A 422 would say the request could never
 * succeed, which would be wrong and would push a client into treating a normal
 * race as a bug.
 *
 * <p>The seat still to play is carried and disclosed. Whose turn it is, is public
 * information at the table — everyone can see who has not put a card down — so
 * naming it costs nothing and saves a client a second round trip to work out what
 * it is waiting for. What is deliberately not carried is anything about the cards
 * involved: which cards are already down is for the read model to serve, and what
 * the absent player holds is nobody's business.
 *
 * <p>A trick is complete when every seat that still holds a card has played into
 * it, which is not the same as every seat at the table. The last trick of a hand
 * is short, because the whole deck is dealt and hands are not all the same size
 * (ADR-023). Checking against the seats that hold cards, rather than the seat
 * count, is the only formulation that is right for both the first trick and the
 * last one.
 *
 * <p>Raised by the resolve use case. The domain can answer whether a trick is
 * complete, but only the use case has both the trick and the current hands in
 * hand, and it is the layer that decided a resolution was being requested at all.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class TrickNotCompleteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID trickId;

    private final int seatStillToPlay;

    /**
     * Creates the exception for a trick that is still waiting on a seat.
     *
     * @param trickId the trick that is not yet complete
     * @param seatStillToPlay the seat whose turn it is
     */
    public TrickNotCompleteException(final UUID trickId, final int seatStillToPlay) {
        super("Trick " + trickId + " is waiting on seat " + seatStillToPlay);
        this.trickId = trickId;
        this.seatStillToPlay = seatStillToPlay;
    }

    /**
     * The trick that is not yet complete.
     *
     * @return the trick identifier
     */
    public UUID trickId() {
        return trickId;
    }

    /**
     * The seat whose turn it is.
     *
     * @return the seat order of the seat still to play
     */
    public int seatStillToPlay() {
        return seatStillToPlay;
    }
}
