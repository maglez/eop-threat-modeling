package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.maglez.eop.application.RateLimitedException;
import org.maglez.eop.outbound.web.InMemorySessionCreationLimiter;

/**
 * The creation throttle is a security control, so its boundaries are asserted rather
 * than assumed (ADR-033).
 *
 * <p>Without a limit, an unauthenticated caller can flood the database at the rate of
 * the network, filling the join-code keyspace and causing legitimate facilitators to
 * receive opaque 503s. Every test here pins a number that a reviewer would otherwise
 * have to take on trust.
 *
 * <p>Time is injected rather than waited for. A test that slept for a minute to watch
 * a window expire would be a minute of build time spent proving arithmetic.
 */
@DisplayName("InMemorySessionCreationLimiter")
class InMemorySessionCreationLimiterTest {

    /** Mirrors the production window. Pinning it is the point of these tests. */
    private static final Duration WINDOW = InMemorySessionCreationLimiter.WINDOW;

    /** Mirrors the production per-address limit. Pinning it is the point of these tests. */
    private static final int PER_ADDRESS = 5;

    private static final Instant START = Instant.parse("2026-02-01T09:30:00Z");

    private static final String ADDRESS = "203.0.113.9";

    private final TickingClock clock = new TickingClock(START);

    private final InMemorySessionCreationLimiter limiter = new InMemorySessionCreationLimiter(clock);

    @Nested
    @DisplayName("counting creations from one address")
    class TheAddressWindow {

        @Test
        @DisplayName("five creations are tolerated and the sixth attempt is refused")
        void shouldRefuseTheSixthAttempt() {
            for (int creation = 0; creation < PER_ADDRESS - 1; creation++) {
                limiter.recordCreation(ADDRESS);
            }

            assertThatCode(() -> limiter.checkAllowed(ADDRESS))
                    .as("the fifth attempt is still allowed")
                    .doesNotThrowAnyException();

            limiter.recordCreation(ADDRESS);

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> limiter.checkAllowed(ADDRESS));
        }

        @Test
        @DisplayName("the refusal names the wait until the oldest creation leaves the window")
        void shouldReportHowLongToWait() {
            exhaustTheAddressWindow();

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> limiter.checkAllowed(ADDRESS))
                    .satisfies(refusal -> assertThat(refusal.retryAfter()).isEqualTo(WINDOW));
        }

        @Test
        @DisplayName("a creation is forgotten only once it is strictly older than the window")
        void shouldHoldTheWindowOpenForItsFullMinute() {
            exhaustTheAddressWindow();

            // Advance to exactly the window boundary — the oldest entry is now AT the horizon,
            // not before it, so it is still inside the window.
            clock.advance(WINDOW);

            assertThatExceptionOfType(RateLimitedException.class)
                    .as("window boundary is inclusive — still refused at exactly T+60s")
                    .isThrownBy(() -> limiter.checkAllowed(ADDRESS));
        }

        @Test
        @DisplayName("a creation is admitted once the window has fully expired")
        void shouldAdmitAfterWindowExpires() {
            exhaustTheAddressWindow();

            // One tick past the window — the oldest entry is now strictly before the horizon.
            clock.advance(WINDOW.plusSeconds(1));

            assertThatCode(() -> limiter.checkAllowed(ADDRESS))
                    .as("window has expired — new creation is allowed")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("recordCreation is the authoritative gate — refuses after the limit is reached")
        void recordCreationRefusesWhenWindowExhausted() {
            for (int creation = 0; creation < PER_ADDRESS; creation++) {
                limiter.recordCreation(ADDRESS);
            }

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> limiter.recordCreation(ADDRESS));
        }

        @Test
        @DisplayName("different addresses have independent windows")
        void shouldTrackAddressesIndependently() {
            exhaustTheAddressWindow();

            assertThatCode(() -> limiter.checkAllowed("10.0.0.2"))
                    .as("a different address is unaffected")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null address is bucketed as unknown and still rate-limited")
        void shouldBucketNullAddressAsUnknown() {
            for (int creation = 0; creation < PER_ADDRESS; creation++) {
                limiter.recordCreation(null);
            }

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> limiter.checkAllowed(null));
        }

        @Test
        @DisplayName("blank address is bucketed as unknown and still rate-limited")
        void shouldBucketBlankAddressAsUnknown() {
            for (int creation = 0; creation < PER_ADDRESS; creation++) {
                limiter.recordCreation("   ");
            }

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> limiter.checkAllowed("   "));
        }

        @Test
        @DisplayName("refundCreation returns a reserved slot so a transient failure does not consume it")
        void refundCreationRestoresSlot() {
            exhaustTheAddressWindow();

            // Window is now full — next recordCreation would throw.
            // Advance past the window so one slot opens up.
            clock.advance(WINDOW.plusSeconds(1));

            // Record one creation, then refund it.
            limiter.recordCreation(ADDRESS);
            limiter.refundCreation(ADDRESS);

            // After refund the slot is back — another creation should succeed.
            assertThatCode(() -> limiter.recordCreation(ADDRESS))
                    .as("refunded slot is available again")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refundCreation on an untracked address is a no-op")
        void refundCreationOnUntrackedAddressIsNoOp() {
            assertThatCode(() -> limiter.refundCreation("192.0.2.1"))
                    .as("refund on untracked address must not throw")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Duration.ofSeconds(1) fallback when remaining time is zero or negative")
    class RetryAfterFallback {

        @Test
        @DisplayName("checkAllowed returns 1s retry-after when the oldest entry is exactly at the horizon")
        void checkAllowedReturnsFallbackWhenOldestIsAtHorizon() {
            // Fill the window at START.
            exhaustTheAddressWindow();

            // Advance exactly one window — the oldest entry is now AT the horizon (not before it),
            // so Duration.between(horizon, oldest) == ZERO, triggering the fallback.
            clock.advance(WINDOW);

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> limiter.checkAllowed(ADDRESS))
                    .satisfies(ex -> assertThat(ex.retryAfter())
                            .as("fallback retry-after must be 1 second")
                            .isEqualTo(Duration.ofSeconds(1)));
        }

        @Test
        @DisplayName("recordCreation returns 1s retry-after when the oldest entry is exactly at the horizon")
        void recordCreationReturnsFallbackWhenOldestIsAtHorizon() {
            exhaustTheAddressWindow();
            clock.advance(WINDOW);

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> limiter.recordCreation(ADDRESS))
                    .satisfies(ex -> assertThat(ex.retryAfter())
                            .as("fallback retry-after must be 1 second")
                            .isEqualTo(Duration.ofSeconds(1)));
        }
    }

    @Nested
    @DisplayName("saturation — fail-closed when the tracked-key table is full")
    class Saturation {

        /**
         * A limiter with a tiny table (3 keys) so saturation is reachable in tests
         * without filling 10,000 entries.
         */
        private final InMemorySessionCreationLimiter smallLimiter =
                new InMemorySessionCreationLimiter(clock, PER_ADDRESS, 3);

        @Test
        @DisplayName("checkAllowed refuses a new address when the table is full (fail-closed)")
        void checkAllowedRefusesNewAddressWhenTableFull() {
            // Fill the table with 3 distinct addresses.
            smallLimiter.recordCreation("10.0.0.1");
            smallLimiter.recordCreation("10.0.0.2");
            smallLimiter.recordCreation("10.0.0.3");

            // A fourth address must be refused.
            assertThatExceptionOfType(RateLimitedException.class)
                    .as("new address refused when table is saturated")
                    .isThrownBy(() -> smallLimiter.checkAllowed("10.0.0.4"))
                    .satisfies(ex -> assertThat(ex.retryAfter())
                            .isEqualTo(Duration.ofSeconds(1)));
        }

        @Test
        @DisplayName("recordCreation refuses a new address when the table is full (fail-closed)")
        void recordCreationRefusesNewAddressWhenTableFull() {
            smallLimiter.recordCreation("10.0.0.1");
            smallLimiter.recordCreation("10.0.0.2");
            smallLimiter.recordCreation("10.0.0.3");

            assertThatExceptionOfType(RateLimitedException.class)
                    .as("new address refused in recordCreation when table is saturated")
                    .isThrownBy(() -> smallLimiter.recordCreation("10.0.0.4"))
                    .satisfies(ex -> assertThat(ex.retryAfter())
                            .isEqualTo(Duration.ofSeconds(1)));
        }

        @Test
        @DisplayName("a known address is still admitted when the table is full")
        void knownAddressIsAdmittedWhenTableFull() {
            smallLimiter.recordCreation("10.0.0.1");
            smallLimiter.recordCreation("10.0.0.2");
            smallLimiter.recordCreation("10.0.0.3");

            // "10.0.0.1" is already tracked — it must not be refused by saturation.
            assertThatCode(() -> smallLimiter.checkAllowed("10.0.0.1"))
                    .as("known address must not be refused by saturation check")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a new address is admitted after aged-out entries are evicted")
        void newAddressAdmittedAfterEviction() {
            smallLimiter.recordCreation("10.0.0.1");
            smallLimiter.recordCreation("10.0.0.2");
            smallLimiter.recordCreation("10.0.0.3");

            // Advance past the window so all existing entries age out.
            clock.advance(WINDOW.plusSeconds(1));

            // A new address should now be admitted (eviction reclaims the table).
            assertThatCode(() -> smallLimiter.checkAllowed("10.0.0.4"))
                    .as("new address admitted after eviction clears the table")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("maxCreationsPerAddress must be at least 1")
        void rejectsZeroMaxCreations() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new InMemorySessionCreationLimiter(clock, 0, 10))
                    .withMessageContaining("maxCreationsPerAddress must be >= 1");
        }

        @Test
        @DisplayName("negative maxCreationsPerAddress is rejected")
        void rejectsNegativeMaxCreations() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new InMemorySessionCreationLimiter(clock, -1, 10))
                    .withMessageContaining("maxCreationsPerAddress must be >= 1");
        }

        @Test
        @DisplayName("maxTrackedKeys must be at least 1")
        void rejectsZeroMaxTrackedKeys() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new InMemorySessionCreationLimiter(clock, 5, 0))
                    .withMessageContaining("maxTrackedKeys must be >= 1");
        }
    }

    @Nested
    @DisplayName("concurrent creation attempts")
    class ConcurrentCreations {

        @RepeatedTest(5)
        @DisplayName("exactly the limit number of creations succeed under concurrent load")
        void shouldAllowExactlyLimitCreationsConcurrently() throws InterruptedException {
            final int threads = PER_ADDRESS * 3;
            final ExecutorService pool = Executors.newFixedThreadPool(threads);
            final CountDownLatch ready = new CountDownLatch(threads);
            final CountDownLatch go = new CountDownLatch(1);
            final AtomicInteger admitted = new AtomicInteger();
            final AtomicInteger refused = new AtomicInteger();

            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    }
                    catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        limiter.recordCreation(ADDRESS);
                        admitted.incrementAndGet();
                    }
                    catch (final RateLimitedException e) {
                        refused.incrementAndGet();
                    }
                }));
            }

            ready.await();
            go.countDown();

            for (final Future<?> f : futures) {
                try {
                    f.get();
                }
                catch (final ExecutionException e) {
                    // unexpected — rethrow
                    throw new RuntimeException(e);
                }
            }
            pool.shutdown();

            assertThat(admitted.get())
                    .as("exactly the limit number of creations should be admitted")
                    .isEqualTo(PER_ADDRESS);
            assertThat(refused.get())
                    .as("all excess creations should be refused")
                    .isEqualTo(threads - PER_ADDRESS);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void exhaustTheAddressWindow() {
        for (int creation = 0; creation < PER_ADDRESS; creation++) {
            limiter.recordCreation(ADDRESS);
        }
    }

    /**
     * A mutable clock that starts at a fixed instant and advances on demand.
     *
     * <p>Reuses the same pattern as {@link InMemoryJoinAttemptLimiterTest}'s inner
     * {@code TickingClock} — but that class is package-private and not accessible
     * here, so we declare our own. Both are identical in behaviour.
     */
    static final class TickingClock extends Clock {

        private Instant now;

        TickingClock(final Instant start) {
            this.now = start;
        }

        void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            throw new UnsupportedOperationException("withZone not supported in test clock");
        }
    }
}
