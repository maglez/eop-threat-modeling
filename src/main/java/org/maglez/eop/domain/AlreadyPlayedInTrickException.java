package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a seat plays a second card into a trick it has already played into.
 *
 * <p>Signalled by the persistence layer, from {@code uq_trick_play_trick_seat} or
 * {@code uq_trick_play_trick_player} rejecting an insert, and translated here so
 * the use case layer never sees a database-specific type. Two constraints raise one
 * exception because they are two spellings of one rule — a seat plays once per
 * trick, and the player at that seat plays once per trick — and a caller cannot act
 * differently depending on which index noticed.
 *
 * <p>An expected outcome rather than a failure, which is why it is a conflict with
 * current state and not a malformed request: the ordinary cause is a double-submit,
 * a retried request after a timeout, or two clients driven by the same impatient
 * player. The state that resolves it is the next trick, so the caller re-reads and
 * carries on.
 *
 * <p>It is not a substitute for checking whose turn it is. {@link Trick} refuses an
 * out-of-turn play before storage is reached, and this exception exists for the
 * narrow window where two requests pass that check concurrently. A firing here is
 * the backstop working; a frequent firing is a client that retries without reading.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class AlreadyPlayedInTrickException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID trickId;

    private final int seatOrder;

    /**
     * Creates the exception for a seat that has already played.
     *
     * @param trickId   the trick already holding a play from this seat
     * @param seatOrder the seat that tried to play twice
     */
    public AlreadyPlayedInTrickException(final UUID trickId, final int seatOrder) {
        super("Seat " + seatOrder + " has already played into trick " + trickId);
        this.trickId = trickId;
        this.seatOrder = seatOrder;
    }

    /**
     * The trick that already holds a play from this seat.
     *
     * @return the trick identifier
     */
    public UUID trickId() {
        return trickId;
    }

    /**
     * The seat that tried to play twice.
     *
     * @return the seat order
     */
    public int seatOrder() {
        return seatOrder;
    }
}
