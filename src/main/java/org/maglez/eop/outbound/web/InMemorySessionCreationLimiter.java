package org.maglez.eop.outbound.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.maglez.eop.config.TrustedProxyProperties;
import org.maglez.eop.application.RateLimitedException;
import org.maglez.eop.application.port.out.SessionCreationLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Counts successful session creations in a sliding window, in this process's memory.
 *
 * <p>Creating a session requires no credential and inserts a permanent row. Without a
 * limit, an unauthenticated caller can flood the database at the rate of the network:
 * at 500 req/s that is 43 million rows per day, filling the join-code keyspace and
 * causing legitimate facilitators to receive opaque 503s once the 5-attempt collision
 * retry exhausts (ADR-033).
 *
 * <p><strong>Only one window.</strong> Unlike the join limiter, only the per-address
 * window is needed here. There is no per-code analogue: a session does not exist until
 * it is created, so there is no code to walk. The address window alone is sufficient
 * to prevent a single caller from flooding the table.
 *
 * <p><strong>Reserve-before-work.</strong> {@link #recordCreation} is the authoritative
 * atomic gate and must be called <em>before</em> any database work. If the downstream
 * use case fails, the caller must invoke {@link #refundCreation} to return the slot so
 * that a transient error does not permanently consume a creation allowance.
 *
 * <p><strong>Successes are counted, not failures.</strong> A facilitator who creates
 * five lobbies in a minute is the pattern being limited; there is no "wrong answer"
 * equivalent to a failed join. Counting failures would allow unlimited successful
 * creations, which is the attack vector.
 *
 * <p><strong>The counters are in memory and are forgotten on restart.</strong> This
 * is an accepted risk rather than an oversight: a restart is a deployment, and a
 * deployment is not something an attacker can trigger. A shared store would be the
 * right answer for more than one instance, and there is one instance (ADR-012).
 *
 * <p><strong>Fail-closed on saturation.</strong> When the tracked-key table is full
 * and a new address arrives, the attempt is refused rather than silently admitted.
 * The refusal is issued in {@link #checkAllowed}, before any database work, so a
 * saturated table costs no lookup. A flood of distinct keys is itself an attack
 * pattern; admitting requests that cannot be tracked would let an attacker bypass
 * the limiter by exhausting the table first (ADR-033).
 *
 * <p><strong>Atomic check-and-record.</strong> The prune, the limit check, and the
 * insertion are performed under the same lock on the window deque. This prevents two
 * concurrent threads from both passing the limit check before either records its
 * creation. {@link #checkAllowed} is a best-effort pre-check that takes no lock; the
 * authoritative atomic gate is the synchronized block in {@link #recordCreation}.
 */
@Component
public final class InMemorySessionCreationLimiter implements SessionCreationLimiter {

    /** How far back creations are remembered. */
    public static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Default upper bound on tracked keys, so an attacker cannot turn a rate limiter
     * into a memory leak by presenting a new address on every request.
     */
    public static final int DEFAULT_MAX_TRACKED_KEYS = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger(InMemorySessionCreationLimiter.class);

    /** Bucket for a request that arrived with no usable client address. */
    private static final String UNKNOWN_ADDRESS_KEY = "\u0000unknown";

    private final Map<String, Deque<Instant>> creationsByAddress = new ConcurrentHashMap<>();

    private final Clock clock;

    /**
     * Successful creations one client may make in a window before being refused.
     *
     * <p>Bound from {@code eop.web.session-creation-limit} via
     * {@link TrustedProxyProperties}. The production default is 5. The test suite
     * overrides it to {@link Integer#MAX_VALUE} in
     * {@code src/test/resources/application.properties} to avoid exhausting the
     * limiter across the shared Spring context.
     */
    private final int maxCreationsPerAddress;

    /**
     * Upper bound on tracked keys. Configurable for unit tests that need to exercise
     * the saturation path without filling 10,000 entries.
     */
    private final int maxTrackedKeys;

    /**
     * Creates the limiter bound from {@link TrustedProxyProperties}.
     *
     * @param clock the time source for window arithmetic
     * @param properties the bound configuration; the per-address limit is read from it
     */
    @Autowired
    public InMemorySessionCreationLimiter(final Clock clock, final TrustedProxyProperties properties) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.maxCreationsPerAddress = Objects.requireNonNull(properties, "properties is required")
                .sessionCreationLimit();
        this.maxTrackedKeys = DEFAULT_MAX_TRACKED_KEYS;
        LOG.info("Session creation limiter initialised: {} creations per address per {}s",
                maxCreationsPerAddress, WINDOW.toSeconds());
    }

    /**
     * Constructor for unit tests that do not load a Spring context.
     *
     * <p>Uses the production default of 5 creations per window and the default
     * tracked-key table size.
     *
     * @param clock the time source for window arithmetic
     */
    public InMemorySessionCreationLimiter(final Clock clock) {
        this(clock, 5, DEFAULT_MAX_TRACKED_KEYS);
    }

    /**
     * Constructor for unit tests that need to control the per-address
     * limit or the tracked-key table size to exercise saturation paths.
     *
     * @param clock the time source for window arithmetic
     * @param maxCreationsPerAddress creations allowed per address per window; must be &gt;= 1
     * @param maxTrackedKeys         upper bound on the number of tracked addresses; must be &gt;= 1
     */
    public InMemorySessionCreationLimiter(final Clock clock, final int maxCreationsPerAddress,
            final int maxTrackedKeys) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (maxCreationsPerAddress < 1) {
            throw new IllegalArgumentException(
                    "maxCreationsPerAddress must be >= 1, got: " + maxCreationsPerAddress);
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException(
                    "maxTrackedKeys must be >= 1, got: " + maxTrackedKeys);
        }
        this.maxCreationsPerAddress = maxCreationsPerAddress;
        this.maxTrackedKeys = maxTrackedKeys;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation checks the per-address window. It also checks whether
     * the tracked-key table is full: if the table is at capacity and the caller's
     * address is not already tracked, the attempt is refused here, before any
     * database work (fail-closed on saturation, ADR-033).
     *
     * <p>This is a best-effort pre-check. It takes no lock on the window deque, so
     * two concurrent threads at {@code limit - 1} can both pass this check. The
     * authoritative atomic gate is the synchronized block in
     * {@link #recordCreation}.
     */
    @Override
    public void checkAllowed(final String clientAddress) {
        final Instant now = clock.instant();
        final Instant horizon = now.minus(WINDOW);
        final String addrKey = addressKey(clientAddress);

        // Saturation check — fail-closed before any DB work.
        checkSaturation(addrKey, horizon);

        // Window exhaustion check — read-only, no lock, best-effort.
        refuseIfExhausted(creationsByAddress.get(addrKey), horizon);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The window is updated atomically: the prune, the limit check, and the
     * insertion are all performed under the same lock on the window deque. This
     * prevents two concurrent threads from both passing the limit check before
     * either records its creation.
     *
     * <p>This method must be called <em>before</em> any database work. If the
     * downstream use case fails, call {@link #refundCreation} to return the slot.
     *
     * @throws RateLimitedException if the window is exhausted after the atomic
     *     prune-check-add; this is the authoritative gate for concurrent callers
     *     that both passed {@link #checkAllowed}
     */
    @Override
    public void recordCreation(final String clientAddress) {
        final Instant now = clock.instant();
        final Instant horizon = now.minus(WINDOW);
        recordInWindow(addressKey(clientAddress), now, horizon);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes the most-recent creation timestamp from the address window, so
     * that a transient use-case failure does not permanently consume a slot.
     * If the window has already expired or the address is not tracked, this is
     * a no-op.
     */
    @Override
    public void refundCreation(final String clientAddress) {
        final String addrKey = addressKey(clientAddress);
        final Deque<Instant> window = creationsByAddress.get(addrKey);
        if (window == null) {
            return;
        }
        synchronized (window) {
            window.pollLast();
        }
    }

    /**
     * Checks whether the tracked-key table is full and the key is new.
     *
     * <p>If the table is at capacity and the key is not already present, an attempt
     * is made to reclaim aged-out entries. If the table is still full after eviction,
     * {@link RateLimitedException} is thrown.
     */
    private void checkSaturation(final String key, final Instant horizon) {
        if (creationsByAddress.size() >= maxTrackedKeys && !creationsByAddress.containsKey(key)) {
            evictEmptyWindows(horizon);
            if (creationsByAddress.size() >= maxTrackedKeys && !creationsByAddress.containsKey(key)) {
                LOG.warn("Session creation limiter table saturated ({} keys); refusing new key", maxTrackedKeys);
                throw new RateLimitedException(Duration.ofSeconds(1));
            }
        }
    }

    /**
     * Atomically records one creation in the address window.
     *
     * <p>The prune, the limit check, and the insertion are all performed under the
     * same lock on the window deque. This is the authoritative atomic gate: two
     * concurrent threads racing at {@code limit - 1} cannot both pass the check
     * before either records.
     *
     * <p>If the table is at capacity and the key is new, the creation is refused
     * fail-closed: the caller receives a {@link RateLimitedException} rather than
     * being silently admitted untracked.
     *
     * @throws RateLimitedException if the window is exhausted after pruning, or if
     *     the table is saturated and the key is new
     */
    private void recordInWindow(final String key, final Instant now, final Instant horizon) {
        if (creationsByAddress.size() >= maxTrackedKeys && !creationsByAddress.containsKey(key)) {
            evictEmptyWindows(horizon);
            if (creationsByAddress.size() >= maxTrackedKeys && !creationsByAddress.containsKey(key)) {
                LOG.warn("Session creation limiter table saturated ({} keys); refusing record for new key",
                        maxTrackedKeys);
                throw new RateLimitedException(Duration.ofSeconds(1));
            }
        }

        final Deque<Instant> window = creationsByAddress.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());

        synchronized (window) {
            pruneUnderLock(window, horizon);
            if (window.size() >= maxCreationsPerAddress) {
                final Instant oldest = window.peekFirst();
                final Duration remaining = oldest == null ? WINDOW : Duration.between(horizon, oldest);
                throw new RateLimitedException(remaining.isNegative() || remaining.isZero()
                        ? Duration.ofSeconds(1)
                        : remaining);
            }
            window.addLast(now);
        }
    }

    /**
     * Refuses the attempt when the address window is already full.
     *
     * <p>Read-only: this method never mutates any deque. It counts only entries
     * within the current window and throws if the active count is at or above the
     * allowance. Because no lock is held, the count may be stale with respect to
     * concurrent {@link #recordCreation} calls — this is intentional: this is a
     * best-effort pre-check, not the authoritative gate.
     */
    private void refuseIfExhausted(final Deque<Instant> window, final Instant horizon) {
        if (window == null) {
            return;
        }
        final long activeCount = window.stream().filter(t -> !t.isBefore(horizon)).count();
        if (activeCount < maxCreationsPerAddress) {
            return;
        }
        final Instant oldest = window.stream().filter(t -> !t.isBefore(horizon)).findFirst().orElse(null);
        final Duration remaining = oldest == null ? WINDOW : Duration.between(horizon, oldest);
        throw new RateLimitedException(remaining.isNegative() || remaining.isZero()
                ? Duration.ofSeconds(1)
                : remaining);
    }

    /**
     * Drops creations that have aged out of the window.
     *
     * <p>Must only be called from inside {@code synchronized(window)}.
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
     * <p>This method never mutates any deque — it only removes map entries.
     */
    private void evictEmptyWindows(final Instant horizon) {
        creationsByAddress.entrySet().removeIf(entry -> entry.getValue().stream().allMatch(t -> t.isBefore(horizon)));
    }

    private static String addressKey(final String clientAddress) {
        return clientAddress == null || clientAddress.isBlank() ? UNKNOWN_ADDRESS_KEY : clientAddress;
    }
}
