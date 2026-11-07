package org.maglez.eop.application.port.out;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.maglez.eop.application.TooManyJoinAttemptsException;

/**
 * Join attempt limiter that remembers every consultation.
 *
 * <p>The arguments matter as much as the count. The limiter is handed the code
 * exactly as the caller typed it, before any Crockford folding, and recording that
 * verbatim is how a test proves the throttle is keyed on the attempt rather than on
 * a normalised value the caller never sent.
 *
 * <p>The refusal seam exists to prove ordering: if the limiter refuses, a malformed
 * code must still surface as too many attempts rather than as an unknown code, which
 * can only happen when the check runs before the parse.
 */
public final class RecordingJoinAttemptLimiter implements JoinAttemptLimiter {

    /**
     * One consultation of the limiter.
     *
     * @param address the caller address as supplied
     * @param code the join code as supplied, unnormalised
     */
    public record Attempt(String address, String code) {
    }

    private final List<Attempt> checks = new ArrayList<>();
    private final List<Attempt> failures = new ArrayList<>();

    private Duration refusal;

    /**
     * Makes every subsequent check refuse.
     *
     * @param retryAfter the delay the refusal advertises
     */
    public void refuseWith(final Duration retryAfter) {
        this.refusal = retryAfter;
    }

    @Override
    public void checkAllowed(final String clientAddress, final String joinCodeAttempt) {
        checks.add(new Attempt(clientAddress, joinCodeAttempt));
        if (refusal != null) {
            throw new TooManyJoinAttemptsException(refusal);
        }
    }

    @Override
    public void recordFailure(final String clientAddress, final String joinCodeAttempt) {
        failures.add(new Attempt(clientAddress, joinCodeAttempt));
    }

    /**
     * @return every consultation, in order
     */
    public List<Attempt> checks() {
        return List.copyOf(checks);
    }

    /**
     * @return every failure attributed, in order
     */
    public List<Attempt> failures() {
        return List.copyOf(failures);
    }
}
