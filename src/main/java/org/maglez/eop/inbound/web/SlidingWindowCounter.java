package org.maglez.eop.inbound.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.maglez.eop.application.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts events per key over a rolling one-minute window and refuses the event that would exceed a limit.
 *
 * <p>This is the mechanism behind the read-route rate limit introduced by EOP-88 (ADR-051). It is deliberately
 * free of any notion of <em>what</em> is being counted: it takes an opaque key, and the caller decides what that
 * key means. {@code ReadRateLimitInterceptor} keys on the resolved client address; a future caller might key on
 * something else.
 *
 * <p><strong>The check and the record are one atomic step.</strong> {@link #admit(String)} prunes the window,
 * tests the limit and inserts the new timestamp while holding a single lock on that key's window, so two threads
 * arriving at {@code limit - 1} cannot both be admitted. There is no separate "may I?" method: a lock-free
 * pre-check would be stale by the time the caller acted on it, and a caller that acted on a stale answer would be
 * reintroducing exactly the race this class exists to close. {@code InMemorySessionCreationLimiter} does expose
 * such a pre-check, but only because its use case needs a cheap early refusal before doing database work; an
 * interceptor has no such work to avoid.
 *
 * <p><strong>Saturation fails closed.</strong> The key table is bounded at {@code maxTrackedKeys}. When it is
 * full and the key is new, empty windows are evicted and the test repeated; if the table is still full the event
 * is refused rather than admitted untracked. A flood of distinct keys is itself an attack pattern, and admitting
 * requests that cannot be counted would let an attacker bypass the limiter by exhausting the table first — the
 * same reasoning ADR-033 records for the creation and join limiters.
 *
 * <p><strong>The counters are in memory and are forgotten on restart.</strong> That is acceptable for the same
 * reason it is acceptable for the other two limiters: a restart is a deployment, and a deployment is not
 * something an attacker can trigger. A shared store would be the right answer for more than one instance, and
 * there is one instance (ADR-012).
 *
 * <p>Two races are reachable only at saturation and are accepted rather than locked out. The tracked-key count
 * can overshoot {@code maxTrackedKeys} slightly, because the saturation test and the insertion of the new window
 * are not one atomic step. And a window evicted while another thread holds it loses that thread's count. Both
 * cost at most a handful of admitted events at the point where the table is already full, and closing them would
 * mean a global lock on every request.
 *
 * <p>This class is package-private and stateful; it is held by a Spring-managed component rather than being a
 * bean itself, so that one process can run several independently-configured counters.
 */
final class SlidingWindowCounter {

    /**
     * The width of the rolling window. Package-private so tests can express expectations against it rather than
     * hard-coding sixty seconds.
     */
    static final Duration WINDOW = Duration.ofMinutes(1);

    private static final Logger LOG = LoggerFactory.getLogger(SlidingWindowCounter.class);

    private final Map<String, Deque<Instant>> windowsByKey = new ConcurrentHashMap<>();

    private final Clock clock;

    private final String name;

    private final int limit;

    private final int maxTrackedKeys;

    /**
     * Creates a counter.
     *
     * @param clock          the clock used to timestamp events and compute the window horizon; must not be null
     * @param name           a short label for this counter, used only in log messages so that an operator can
     *                       tell which limiter saturated; must not be null
     * @param limit          the maximum number of events admitted per key per {@link #WINDOW}; must be at least 1
     * @param maxTrackedKeys the maximum number of distinct keys tracked at once; must be at least 1
     */
    SlidingWindowCounter(final Clock clock, final String name, final int limit, final int maxTrackedKeys) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.name = Objects.requireNonNull(name, "name is required");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1 but was " + limit);
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("maxTrackedKeys must be at least 1 but was " + maxTrackedKeys);
        }
        this.limit = limit;
        this.maxTrackedKeys = maxTrackedKeys;
    }

    /**
     * Records one event against a key, or refuses it if the key has already had its allowance for the window.
     *
     * <p>The refusal carries the delay after which the oldest counted event falls out of the window, which is the
     * earliest moment the caller could succeed. That is the value the caller should put in a {@code Retry-After}
     * header.
     *
     * @param key the key to count against; must not be null
     * @throws RateLimitedException if the key has reached its limit within the window, or if the key table is
     *                              saturated and this key is not already being tracked
     */
    void admit(final String key) {
        Objects.requireNonNull(key, "key is required");
        final var now = clock.instant();
        final var horizon = now.minus(WINDOW);
        refuseIfSaturated(key, horizon);
        final var window = windowsByKey.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        synchronized (window) {
            pruneUnderLock(window, horizon);
            if (window.size() >= limit) {
                throw new RateLimitedException(retryAfter(window.peekFirst(), horizon));
            }
            window.addLast(now);
        }
    }

    /**
     * Reports how many keys are currently tracked, for tests that exercise the saturation guard.
     *
     * @return the number of keys with a window in the table, including windows whose entries have all expired but
     *         which have not yet been evicted
     */
    int trackedKeys() {
        return windowsByKey.size();
    }

    /**
     * Refuses a new key when the table is full, after first trying to make room.
     *
     * <p>An already-tracked key is never refused here: it costs no new table entry, and its own window will
     * decide whether it is over the limit.
     *
     * @param key     the key about to be counted
     * @param horizon the start of the current window
     * @throws RateLimitedException if the table is full and the key is new
     */
    private void refuseIfSaturated(final String key, final Instant horizon) {
        if (windowsByKey.size() < maxTrackedKeys || windowsByKey.containsKey(key)) {
            return;
        }
        evictEmptyWindows(horizon);
        // The key was absent at the guard above and eviction only removes entries, so it is still absent here:
        // re-testing containsKey would add a branch that no input can take.
        if (windowsByKey.size() >= maxTrackedKeys) {
            LOG.warn("{} table saturated ({} keys); refusing new key", name, windowsByKey.size());
            throw new RateLimitedException(Duration.ofSeconds(1));
        }
    }

    /**
     * Removes table entries whose windows hold no events inside the current window.
     *
     * <p>Only map entries are removed. A deque is never replaced or mutated outside its own lock, so a thread
     * holding one keeps operating on a valid object even if its entry is dropped.
     *
     * @param horizon the start of the current window
     */
    private void evictEmptyWindows(final Instant horizon) {
        windowsByKey.entrySet().removeIf(entry -> {
            final var window = entry.getValue();
            synchronized (window) {
                pruneUnderLock(window, horizon);
                return window.isEmpty();
            }
        });
    }

    /**
     * Drops events that have fallen out of the window. Must be called while holding the window's lock.
     *
     * @param window  the window to prune
     * @param horizon the start of the current window
     */
    private static void pruneUnderLock(final Deque<Instant> window, final Instant horizon) {
        for (var oldest = window.peekFirst(); oldest != null && oldest.isBefore(horizon); oldest = window.peekFirst()) {
            window.pollFirst();
        }
    }

    /**
     * Computes how long the caller must wait for the oldest counted event to leave the window.
     *
     * <p>Floored at one second: a zero delay would invite an immediate retry that is certain to be refused again,
     * and {@code Retry-After: 0} reads as "no wait required" to a client. The floor is expressed as a single
     * comparison rather than separate zero and negative tests, because a negative remainder is not reachable —
     * pruning has already dropped every entry older than the horizon, so the oldest entry is never behind it —
     * and a guard no input can take is a branch that can never be tested.
     *
     * @param oldest  the oldest event still counted, never null — the caller holds the window's lock and has just
     *                observed at least {@code limit} entries in it
     * @param horizon the start of the current window
     * @return a delay of at least one second
     */
    private static Duration retryAfter(final Instant oldest, final Instant horizon) {
        final var minimum = Duration.ofSeconds(1);
        final var remaining = Duration.between(horizon, Objects.requireNonNull(oldest, "oldest is required"));
        return remaining.compareTo(minimum) < 0 ? minimum : remaining;
    }

    /**
     * Describes the counter's configuration, deliberately omitting the keys it is tracking so that a client
     * address cannot reach a log line through this method.
     *
     * @return a description naming the counter and its limits
     */
    @Override
    public String toString() {
        return name + "[limit=" + limit + " per " + WINDOW.toSeconds() + "s, maxTrackedKeys=" + maxTrackedKeys + "]";
    }
}
