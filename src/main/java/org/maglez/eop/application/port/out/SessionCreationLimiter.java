package org.maglez.eop.application.port.out;

import org.maglez.eop.application.RateLimitedException;

/**
 * Port through which session creation attempts are rate limited.
 *
 * <p>Creating a session requires no credential and inserts a permanent row. Without a
 * limit, an unauthenticated caller can flood the database at the rate of the network:
 * at 500 req/s that is 43 million rows per day, filling the join-code keyspace and
 * causing legitimate facilitators to receive opaque 503s once the 5-attempt collision
 * retry exhausts (ADR-033).
 *
 * <p>Unlike the join limiter, only the per-address window is needed here. There is no
 * per-code analogue: a session does not exist until it is created, so there is no code
 * to walk. The address window alone is sufficient to prevent a single caller from
 * flooding the table.
 *
 * <p>Implementations reserve a slot before any database work, so a refusal costs no
 * lookup. If the downstream use case fails, the reserved slot must be returned via
 * {@link #refundCreation} so that a transient error does not permanently consume a
 * creation allowance.
 */
public interface SessionCreationLimiter {

    /**
     * Decides whether one more session creation may proceed from this address.
     *
     * <p>This is a best-effort pre-check. The authoritative gate is the atomic
     * reserve in {@link #recordCreation}.
     *
     * @param clientAddress the address the request arrived from
     * @throws RateLimitedException if the caller has exceeded the creation rate
     *     limit, carrying how long it should wait before trying again
     */
    void checkAllowed(String clientAddress);

    /**
     * Atomically reserves one creation slot for this address.
     *
     * <p>This is the authoritative gate. It must be called <em>before</em> any
     * database work so that a refused request never commits a row. If the downstream
     * use case subsequently fails, the caller must invoke {@link #refundCreation} to
     * return the slot.
     *
     * <p>Creations are counted on success rather than failure. A facilitator who
     * creates five lobbies in a minute is the pattern being limited; there is no
     * "wrong answer" equivalent to a failed join.
     *
     * @param clientAddress the address the creation arrived from
     * @throws RateLimitedException if the window is exhausted after the atomic
     *     check-and-record; this supersedes any domain exception the caller was about
     *     to throw (the caller is throttled, so 429 is the correct response)
     */
    void recordCreation(String clientAddress);

    /**
     * Returns a previously reserved creation slot to the window.
     *
     * <p>Must be called when the use case invoked after {@link #recordCreation}
     * throws an exception, so that a transient error does not permanently consume a
     * creation allowance. If the slot cannot be found (e.g. the window has already
     * expired), the call is a no-op.
     *
     * @param clientAddress the address whose slot should be returned
     */
    void refundCreation(String clientAddress);
}
