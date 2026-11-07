package org.maglez.eop.application;

/**
 * Thrown when a session already has the maximum number of SSE subscribers.
 *
 * <p>Mapped to HTTP 429 by {@code GlobalExceptionHandler}. The cap is 12 per session
 * (2× MAXIMUM_PLAYERS to allow reconnect churn) and 500 globally (EOP-20, ADR-034).
 */
public final class TooManySubscribersException extends RuntimeException {

    /**
     * Records that a subscription was refused because a cap was already reached.
     *
     * <p>The message is written by the caller that detected the breach, because only it knows
     * which of the two caps was hit. It reaches the client as an RFC 9457 problem detail, so it
     * must name the limit rather than the subscribers already holding it.
     *
     * @param message which cap was reached, safe to disclose to the refused subscriber
     */
    public TooManySubscribersException(final String message) {
        super(message);
    }
}
