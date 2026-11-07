package org.maglez.eop.outbound.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.application.port.out.JoinAttemptLimiter;
import org.maglez.eop.application.TooManyJoinAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Counts failed join attempts in a sliding window, in this process's memory.
 *
 * <p>This is a security control, not a courtesy about load. A join code is eight
 * characters over a thirty-two symbol alphabet: exactly forty bits, or about 1.1
 * trillion possibilities. Ten failures a minute allows roughly fourteen thousand
 * guesses a day from one address, so a single attacker is not the interesting case
 * and never was. What this class bounds is the guess rate <em>per address</em>; what
 * bounds the search overall is the length of the code.
 *
 * <p>Read the two together, because neither is sufficient alone. Against a pool of
 * a thousand proxied addresses and a few dozen lobbies live at any moment, the
 * expected time to stumble onto a real session is years at forty bits. The same
 * pool needed days when the code was six characters, which is why EOP-24 lengthened
 * it — an earlier version of this comment claimed centuries, but that arithmetic
 * quietly assumed the attacker owned one address. Note also that the per-code
 * window below does nothing against such a search: every guess is a different code
 * and lands in its own fresh counter, so only the per-address window ever fires.
 * Any change that weakens either is a security change and should be reviewed as
 * one (ADR-019).
 *
 * <p><strong>Only failures are counted.</strong> A facilitator shares one code with
 * five people who all join successfully, and none of them should be throttled. An
 * attacker walking the keyspace produces nothing but misses. Counting successes
 * would penalise the first case to no benefit in the second.
 *
 * <p><strong>Two windows, not one.</strong> The address window stops one client
 * trying many codes. The code window stops many clients trying one code, which is
 * what a shared or leaked partial code looks like. Either window alone leaves the
 * other attack open.
 *
 * <p><strong>The code key is normalised before it is counted.</strong> Counting the
 * raw string would let an attacker vary the case, or substitute an {@code O} for a
 * zero, to be handed a fresh bucket for every guess — which is the same as having no
 * per-code limit at all. Anything that could never be a code shares a single bucket,
 * because those attempts are still attempts.
 *
 * <p><strong>The counters are in memory and are forgotten on restart.</strong> This
 * is an accepted risk rather than an oversight: a restart is a deployment, and a
 * deployment is not something an attacker can trigger. A shared store would be the
 * right answer for more than one instance, and there is one instance (ADR-012).
 *
 * <p><strong>Fail-closed on saturation.</strong> When the tracked-key table is full
 * and a new address or code arrives, the attempt is refused rather than silently
 * admitted. The refusal is issued in {@link #checkAllowed}, before any database
 * work, so a saturated table costs no lookup. A flood of distinct keys is itself an
 * attack pattern; admitting requests that cannot be tracked would let an attacker
 * bypass the limiter by exhausting the table first (ADR-019).
 *
 * <p><strong>Atomic check-and-record.</strong> The prune, the limit check, and the
 * insertion for each window are performed under the same lock on the window deque.
 * This prevents two concurrent threads from both passing the limit check before
 * either records its failure. {@link #checkAllowed} is a best-effort pre-check that
 * takes no lock; the authoritative atomic gate is the synchronized block in
 * {@link #recordFailure}.
 *
 * <p><strong>Lock discipline.</strong> The only method that mutates a window deque
 * is {@link #recordFailure} (via {@link #recordInWindow}), and it always does so
 * inside {@code synchronized(window)}. {@link #refuseIfExhausted} mutates nothing.
 * {@link #checkAllowed} mutates no deque, though it may remove aged-out map entries
 * via {@link #evictEmptyWindows} (a map mutation, not a deque mutation).
 * {@link #evictEmptyWindows} removes map entries whose deques are aged out;
 * it never calls {@code pollFirst} or {@code addLast} on any deque. A bounded race
 * exists: a freshly-created empty deque can be evicted before its first
 * {@code addLast}, causing one failure to be silently lost under saturation. See
 * {@link #evictEmptyWindows} for the full analysis.
 *
 * <p><strong>Both windows are always evaluated.</strong> The address window is not
 * allowed to short-circuit the code window: either window alone leaves the other
 * attack open. In {@link #recordFailure}, both windows are always recorded, even if
 * one is at saturation. A saturation condition in one window silently drops that
 * window's record but does not suppress the other.
 */
@Component
public class InMemoryJoinAttemptLimiter implements JoinAttemptLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryJoinAttemptLimiter.class);

    /** How far back failures are remembered. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Failed attempts one client may make in a window before being refused. */
    private static final int MAX_FAILURES_PER_ADDRESS = 10;

    /**
     * Failed attempts against one code, from all clients together, per window.
     *
     * <p>Higher than the per-address allowance because several people mistyping the
     * same code in the same minute is an ordinary meeting, not an attack.
     */
    private static final int MAX_FAILURES_PER_CODE = 30;

    /**
     * Upper bound on tracked keys, so an attacker cannot turn a rate limiter into a
     * memory leak by presenting a new code on every request.
     */
    private static final int MAX_TRACKED_KEYS = 10_000;

    /** Bucket for attempts whose text could never be a join code. */
    private static final String MALFORMED_KEY = "\u0000malformed";

    /** Bucket for a request that arrived with no usable client address. */
    private static final String UNKNOWN_ADDRESS_KEY = "\u0000unknown";

    private final Map<String, Deque<Instant>> failuresByAddress = new ConcurrentHashMap<>();

    private final Map<String, Deque<Instant>> failuresByCode = new ConcurrentHashMap<>();

    private final Clock clock;

    /**
     * Creates the limiter.
     *
     * @param clock the time source for window arithmetic
     */
    public InMemoryJoinAttemptLimiter(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation checks both the per-address and per-code windows. It
     * also checks whether the tracked-key table is full: if the table is at capacity
     * and the caller's address or code is not already tracked, the attempt is refused
     * here, before any database work (fail-closed on saturation, ADR-019).
     *
     * <p>This is a best-effort pre-check. It takes no lock on the window deques, so
     * two concurrent threads at {@code limit - 1} can both pass this check. The
     * authoritative atomic gate is the synchronized block in
     * {@link #recordFailure}/{@link #recordInWindow}.
     */
    @Override
    public void checkAllowed(final String clientAddress, final String joinCodeAttempt) {
        final Instant now = clock.instant();
        final Instant horizon = now.minus(WINDOW);
        final String addrKey = addressKey(clientAddress);
        final String codeKey = codeKey(joinCodeAttempt);

        // Saturation check — fail-closed before any DB work.
        // Both maps are checked: a new address AND a new code both trigger refusal.
        checkSaturation(failuresByAddress, addrKey, horizon);
        checkSaturation(failuresByCode, codeKey, horizon);

        // Window exhaustion check — read-only, no lock, best-effort.
        refuseIfExhausted(failuresByAddress.get(addrKey), horizon, MAX_FAILURES_PER_ADDRESS);
        refuseIfExhausted(failuresByCode.get(codeKey), horizon, MAX_FAILURES_PER_CODE);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each window is updated atomically: the prune, the limit check, and the
     * insertion are all performed under the same lock on the window deque. This
     * prevents two concurrent threads from both passing the limit check before
     * either records its failure.
     *
     * <p>Both windows are always evaluated. The address window is not allowed to
     * short-circuit the code window: either window alone leaves the other attack
     * open. If the address window throws, the code window is still recorded before
     * the exception propagates.
     *
     * <p>If the tracked-key table is full and the key is new, the failure is
     * silently dropped for that window. The key stays untracked while the table
     * remains saturated, so this caller's subsequent attempts are refused by
     * {@link #checkAllowed}'s saturation check; {@link #recordFailure} need not
     * duplicate that refusal.
     *
     * @throws TooManyJoinAttemptsException if either window is exhausted after the
     *     atomic prune-check-add; this is the authoritative gate for concurrent
     *     callers that both passed {@link #checkAllowed}
     */
    @Override
    public void recordFailure(final String clientAddress, final String joinCodeAttempt) {
        final Instant now = clock.instant();
        final Instant horizon = now.minus(WINDOW);

        // Both windows are always recorded. Collect the first exception (if any) and
        // rethrow after both windows have been evaluated, so the per-code counter
        // always advances even when the address window is exhausted.
        TooManyJoinAttemptsException firstException = null;

        try {
            recordInWindow(failuresByAddress, addressKey(clientAddress), now, horizon, MAX_FAILURES_PER_ADDRESS);
        }
        catch (final TooManyJoinAttemptsException e) {
            firstException = e;
        }

        try {
            recordInWindow(failuresByCode, codeKey(joinCodeAttempt), now, horizon, MAX_FAILURES_PER_CODE);
        }
        catch (final TooManyJoinAttemptsException e) {
            if (firstException == null) {
                firstException = e;
            }
        }

        if (firstException != null) {
            throw firstException;
        }
    }

    /**
     * Checks whether the tracked-key table is full and the key is new.
     *
     * <p>If the table is at capacity and the key is not already present, an attempt
     * is made to reclaim aged-out entries. If the table is still full after eviction,
     * {@link TooManyJoinAttemptsException} is thrown.
     *
     * <p>This is called from {@link #checkAllowed}, before any database work, so a
     * saturated table costs no lookup. The check is not atomic with the subsequent
     * {@link #recordInWindow}: a concurrent thread could insert a new key between
     * this check and the {@code computeIfAbsent} in {@link #recordInWindow}, causing
     * the table to transiently exceed {@code MAX_TRACKED_KEYS} by the number of
     * concurrent new-key requests. This is a soft cap, not a hard one, and the
     * overshoot is bounded by the Tomcat thread pool size.
     */
    private void checkSaturation(
            final Map<String, Deque<Instant>> windows,
            final String key,
            final Instant horizon) {
        if (windows.size() >= MAX_TRACKED_KEYS && !windows.containsKey(key)) {
            evictEmptyWindows(windows, horizon);
            if (windows.size() >= MAX_TRACKED_KEYS && !windows.containsKey(key)) {
                LOG.warn("Join attempt limiter table saturated ({} keys); refusing new key", MAX_TRACKED_KEYS);
                throw new TooManyJoinAttemptsException(Duration.ofSeconds(1));
            }
        }
    }

    /**
     * Atomically records one failure in the given window map.
     *
     * <p>The prune, the limit check, and the insertion are all performed under the
     * same lock on the window deque. This is the authoritative atomic gate: two
     * concurrent threads racing at {@code limit - 1} cannot both pass the check
     * before either records.
     *
     * <p>If the table is at capacity and the key is new, the failure is silently
     * dropped. The key stays untracked while the table remains saturated, so this
     * caller's subsequent attempts are refused by {@link #checkAllowed}'s saturation
     * check. Note that a freshly-created deque can be evicted by
     * {@link #evictEmptyWindows} before the first {@code addLast}, causing one
     * failure to be silently lost; see {@link #evictEmptyWindows} for the bounded
     * race analysis.
     *
     * @throws TooManyJoinAttemptsException if the window is exhausted after pruning
     */
    private void recordInWindow(
            final Map<String, Deque<Instant>> windows,
            final String key,
            final Instant now,
            final Instant horizon,
            final int allowance) {

        // If the table is full and the key is new, try to reclaim aged-out entries
        // before deciding to drop. This mirrors the eviction logic in checkSaturation
        // so that recordFailure can track a new key after the flood ages out, even
        // without a prior checkAllowed call.
        if (windows.size() >= MAX_TRACKED_KEYS && !windows.containsKey(key)) {
            evictEmptyWindows(windows, horizon);
            if (windows.size() >= MAX_TRACKED_KEYS && !windows.containsKey(key)) {
                // Table still full after eviction — silently drop this window's record.
                // The key stays untracked while the table remains saturated, so this
                // caller's subsequent attempts are refused by checkAllowed's saturation check.
                return;
            }
        }

        final Deque<Instant> window = windows.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());

        // Atomic prune-check-add under the window's own monitor.
        // This is the only site that mutates a window deque.
        synchronized (window) {
            pruneUnderLock(window, horizon);
            if (window.size() >= allowance) {
                final Instant oldest = window.peekFirst();
                final Duration remaining = oldest == null ? WINDOW : Duration.between(horizon, oldest);
                throw new TooManyJoinAttemptsException(remaining.isNegative() || remaining.isZero()
                        ? Duration.ofSeconds(1)
                        : remaining);
            }
            window.addLast(now);
        }
    }

    /**
     * Refuses the attempt when a window is already full.
     *
     * <p>Read-only: this method never mutates any deque. It counts only entries
     * within the current window (filtering out aged-out entries) and throws if the
     * active count is at or above the allowance. Because no lock is held, the count
     * may be stale with respect to concurrent {@link #recordInWindow} calls — a
     * concurrent thread may have just added an entry that is not yet visible here.
     * This is intentional: {@link #checkAllowed} is a best-effort pre-check, not
     * the authoritative gate.
     *
     * <p>The wait reported is the time until the oldest remembered failure leaves
     * the window, which is the earliest moment the caller could succeed. Reporting
     * the whole window instead would be a lie in the caller's favour, and reporting
     * nothing would leave a well-behaved client guessing.
     */
    private void refuseIfExhausted(final Deque<Instant> window, final Instant horizon, final int allowance) {
        if (window == null) {
            return;
        }
        // Count only entries still inside the window, without removing them.
        // The filter excludes aged-out entries, so the count is accurate for the
        // current horizon. Concurrent recordInWindow calls may add entries that are
        // not yet visible here — this is the accepted best-effort approximation.
        final long activeCount = window.stream().filter(t -> !t.isBefore(horizon)).count();
        if (activeCount < allowance) {
            return;
        }
        final Instant oldest = window.stream().filter(t -> !t.isBefore(horizon)).findFirst().orElse(null);
        final Duration remaining = oldest == null ? WINDOW : Duration.between(horizon, oldest);
        throw new TooManyJoinAttemptsException(remaining.isNegative() || remaining.isZero()
                ? Duration.ofSeconds(1)
                : remaining);
    }

    /**
     * Drops failures that have aged out of the window.
     *
     * <p>Must only be called from inside {@code synchronized(window)}, so that the
     * mutation is exclusive with respect to other writers.
     */
    private static void pruneUnderLock(final Deque<Instant> window, final Instant horizon) {
        for (Instant oldest = window.peekFirst();
                oldest != null && oldest.isBefore(horizon);
                oldest = window.peekFirst()) {
            window.pollFirst();
        }
    }

    /**
     * Removes map entries whose windows are already empty (all entries aged out).
     *
     * <p>This method never mutates any deque — it only removes map entries. A deque
     * is removed only if all its entries are before the horizon (i.e. aged out).
     *
     * <p><strong>Bounded race under saturation.</strong> A thread that has just
     * obtained a fresh empty deque via {@code computeIfAbsent} (in
     * {@link #recordInWindow}) but has not yet entered {@code synchronized(window)}
     * can have that deque removed by this method's {@code removeIf} predicate, which
     * evaluates vacuously true for an empty deque. The thread then appends its failure
     * to an orphaned deque no longer reachable from the map; the next request for the
     * same key creates a fresh deque and the counter resets. This can cause one
      * failure to be silently lost per race. The race is bounded: it requires the map
      * to be at {@code MAX_TRACKED_KEYS} (the only condition under which this method
      * is called), and the lost record is a single undercount for a caller that was
      * admitted (eviction freed space, so {@link #checkAllowed} passed). This permits
      * at most one extra failed attempt per race and cannot be amplified beyond that.
      * The security impact is negligible.
     *
     * <p>Eviction is O(N) over the map and is called only under saturation pressure,
     * on the request thread. The cost is bounded by {@code MAX_TRACKED_KEYS}. Under
     * sustained saturation (attacker holding 10 000 live keys), each refused request
     * triggers a full sweep; this is an accepted trade-off against the alternative of
     * admitting untracked requests.
     */
    private static void evictEmptyWindows(final Map<String, Deque<Instant>> windows, final Instant horizon) {
        // Remove entries whose windows contain only aged-out entries.
        // We do NOT prune here (no deque mutation) — we only check and remove.
        windows.entrySet().removeIf(entry -> entry.getValue().stream().allMatch(t -> t.isBefore(horizon)));
    }

    private static String addressKey(final String clientAddress) {
        return clientAddress == null || clientAddress.isBlank() ? UNKNOWN_ADDRESS_KEY : clientAddress;
    }

    private static String codeKey(final String joinCodeAttempt) {
        return JoinCode.parse(joinCodeAttempt).map(JoinCode::value).orElse(MALFORMED_KEY);
    }
}
