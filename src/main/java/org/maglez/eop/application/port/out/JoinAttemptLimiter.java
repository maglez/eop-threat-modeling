package org.maglez.eop.application.port.out;

import org.maglez.eop.application.TooManyJoinAttemptsException;

/**
 * Port through which join attempts are rate limited.
 *
 * <p>A join code is eight characters, which is exactly forty bits. Length bounds how
 * much of the keyspace a blind search must cover; this limiter bounds how fast any
 * one address may cover it. Neither is sufficient alone, so removing this would be a
 * security regression rather than a simplification, and lengthening the code did not
 * make it optional (ADR-019).
 *
 * <p>Only failed attempts are counted. A facilitator sharing one code with five
 * players produces five successful joins from five addresses and must not be
 * throttled, while an attacker walking the keyspace produces nothing but misses.
 * Counting successes as well would punish the expected workflow and reward the
 * attacker with a cheaper signal.
 *
 * <p>Implementations are consulted before any lookup happens, so a refusal costs
 * no database work.
 */
public interface JoinAttemptLimiter {

    /**
     * Decides whether one more join attempt may proceed.
     *
     * @param clientAddress the address the attempt arrived from, used to throttle
     *     one caller walking many codes
     * @param joinCodeAttempt the raw code being attempted, used to throttle many
     *     callers walking one code
     * @throws TooManyJoinAttemptsException if the attempt must be refused,
     *     carrying how long the caller should wait
     */
    void checkAllowed(String clientAddress, String joinCodeAttempt);

    /**
     * Records that an attempt failed to identify a session.
     *
     * <p>Called for a code that could not be parsed and for a code that parsed but
     * matched nothing, because from the outside those two are the same answer and
     * counting them differently would make the limiter itself an oracle.
     *
     * <p>Implementations perform the authoritative atomic check-and-record: the
     * prune, the limit check, and the insertion are performed under the same lock,
     * so two concurrent threads racing at the limit boundary cannot both pass.
     * {@link #checkAllowed} is a best-effort pre-check; this method is the gate.
     *
     * @param clientAddress the address the attempt arrived from
     * @param joinCodeAttempt the raw code that failed
     * @throws TooManyJoinAttemptsException if either the per-address or per-code
     *     window is exhausted after the atomic check-and-record; this supersedes any
     *     domain exception the caller was about to throw (the caller is throttled,
     *     so 429 is the correct response regardless of the domain outcome)
     */
    void recordFailure(String clientAddress, String joinCodeAttempt);
}
