package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.application.RateLimitedException;

/**
 * Unit tests for the sliding-window counter behind the read rate limiter
 * (EOP-88). No Spring context: the counter is constructed directly with a clock
 * the test advances by hand, so window expiry is exercised without sleeping.
 */
@DisplayName("SlidingWindowCounter")
class SlidingWindowCounterTest {

    private static final Instant START = Instant.parse("2026-08-22T10:00:00Z");
    private static final String KEY = "203.0.113.7";
    private static final String NAME = "Test counter";
    private static final int GENEROUS_KEY_TABLE = 100;

    private MutableClock clock;

    /**
     * A clock the test moves forward explicitly. {@link Clock#fixed} cannot be
     * used on its own because the counter captures the clock once in its
     * constructor and reads {@code instant()} on every call.
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(final Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(final Duration amount) {
            now = now.plus(amount);
        }
    }

    private SlidingWindowCounter counter(final int limit) {
        return counter(limit, GENEROUS_KEY_TABLE);
    }

    private SlidingWindowCounter counter(final int limit, final int maxTrackedKeys) {
        clock = new MutableClock(START);
        return new SlidingWindowCounter(clock, NAME, limit, maxTrackedKeys);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects a limit below one, because a counter that admits nothing is a mistake not a policy")
        void shouldRejectALimitBelowOne() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SlidingWindowCounter(Clock.systemUTC(), NAME, 0, GENEROUS_KEY_TABLE))
                    .withMessageContaining("limit must be at least 1");
        }

        @Test
        @DisplayName("rejects a key table below one, which would refuse every request once fail-closed")
        void shouldRejectAKeyTableBelowOne() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SlidingWindowCounter(Clock.systemUTC(), NAME, 1, 0))
                    .withMessageContaining("maxTrackedKeys must be at least 1");
        }

        @Test
        @DisplayName("rejects a null clock")
        void shouldRejectANullClock() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SlidingWindowCounter(null, NAME, 1, 1));
        }

        @Test
        @DisplayName("rejects a null name, which would leave a saturation warning unattributable")
        void shouldRejectANullName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SlidingWindowCounter(Clock.systemUTC(), null, 1, 1));
        }
    }

    @Nested
    @DisplayName("admission")
    class Admission {

        @Test
        @DisplayName("admits calls up to the limit")
        void shouldAdmitUpToTheLimit() {
            final var subject = counter(3);

            assertThatCode(() -> {
                subject.admit(KEY);
                subject.admit(KEY);
                subject.admit(KEY);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses the call after the limit is reached")
        void shouldRefuseBeyondTheLimit() {
            final var subject = counter(2);
            subject.admit(KEY);
            subject.admit(KEY);

            assertThatExceptionOfType(RateLimitedException.class).isThrownBy(() -> subject.admit(KEY));
        }

        @Test
        @DisplayName("counts each key separately, so one caller cannot exhaust another's allowance")
        void shouldCountKeysSeparately() {
            final var subject = counter(1);
            subject.admit("198.51.100.1");

            assertThatCode(() -> subject.admit("198.51.100.2")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a null key rather than counting it in an unnamed bucket")
        void shouldRejectANullKey() {
            final var subject = counter(1);

            assertThatNullPointerException().isThrownBy(() -> subject.admit(null));
        }

        @Test
        @DisplayName("a refused call is not counted, so a throttled caller cannot extend its own penalty")
        void shouldNotCountARefusedCall() {
            final var subject = counter(1);
            subject.admit(KEY);
            assertThatExceptionOfType(RateLimitedException.class).isThrownBy(() -> subject.admit(KEY));

            // The single recorded entry expires one window after it was made, not one
            // window after the last refusal.
            clock.advance(SlidingWindowCounter.WINDOW.plusSeconds(1));

            assertThatCode(() -> subject.admit(KEY)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the window slides")
    class WindowSliding {

        @Test
        @DisplayName("an entry older than the window no longer counts")
        void shouldForgetEntriesOlderThanTheWindow() {
            final var subject = counter(2);
            subject.admit(KEY);
            subject.admit(KEY);

            clock.advance(SlidingWindowCounter.WINDOW.plusSeconds(1));

            assertThatCode(() -> {
                subject.admit(KEY);
                subject.admit(KEY);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an entry exactly one window old still counts, and asks for the one-second floor")
        void shouldFloorRetryAfterAtOneSecond() {
            final var subject = counter(1);
            subject.admit(KEY);

            // Exactly on the horizon: the entry is not *before* it, so it is not pruned,
            // and the computed wait is zero. Retry-After: 0 reads as "no wait required",
            // so the counter must floor it.
            clock.advance(SlidingWindowCounter.WINDOW);

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.admit(KEY))
                    .satisfies(thrown -> assertThat(thrown.retryAfter()).isEqualTo(Duration.ofSeconds(1)));
        }

        @Test
        @DisplayName("Retry-After names the wait until the oldest entry leaves the window")
        void shouldReportTheWaitUntilTheOldestEntryExpires() {
            final var subject = counter(1);
            subject.admit(KEY);

            clock.advance(Duration.ofSeconds(20));

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.admit(KEY))
                    .satisfies(thrown -> assertThat(thrown.retryAfter())
                            .isEqualTo(SlidingWindowCounter.WINDOW.minusSeconds(20)));
        }

        @Test
        @DisplayName("partial expiry frees exactly one slot")
        void shouldFreeOneSlotAtATime() {
            final var subject = counter(2);
            subject.admit(KEY);
            clock.advance(Duration.ofSeconds(30));
            subject.admit(KEY);

            // The first entry has now expired; the second has not.
            clock.advance(SlidingWindowCounter.WINDOW.minusSeconds(29));

            assertThatCode(() -> subject.admit(KEY)).doesNotThrowAnyException();
            assertThatExceptionOfType(RateLimitedException.class).isThrownBy(() -> subject.admit(KEY));
        }
    }

    @Nested
    @DisplayName("fail-closed saturation (ADR-033)")
    class Saturation {

        @Test
        @DisplayName("refuses a new key once the table is full, rather than admitting what it cannot track")
        void shouldRefuseANewKeyWhenTheTableIsFull() {
            final var subject = counter(10, 2);
            subject.admit("198.51.100.1");
            subject.admit("198.51.100.2");

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.admit("198.51.100.3"))
                    .satisfies(thrown -> assertThat(thrown.retryAfter()).isEqualTo(Duration.ofSeconds(1)));
            assertThat(subject.trackedKeys()).isEqualTo(2);
        }

        @Test
        @DisplayName("a key already tracked is still served when the table is full")
        void shouldStillServeATrackedKeyWhenTheTableIsFull() {
            final var subject = counter(10, 2);
            subject.admit("198.51.100.1");
            subject.admit("198.51.100.2");

            assertThatCode(() -> subject.admit("198.51.100.1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("expired windows are evicted on saturation, so the table recovers without a restart")
        void shouldEvictExpiredWindowsOnSaturation() {
            final var subject = counter(10, 2);
            subject.admit("198.51.100.1");
            subject.admit("198.51.100.2");
            assertThat(subject.trackedKeys()).isEqualTo(2);

            clock.advance(SlidingWindowCounter.WINDOW.plusSeconds(1));

            assertThatCode(() -> subject.admit("198.51.100.3")).doesNotThrowAnyException();
            assertThat(subject.trackedKeys()).isEqualTo(1);
        }

        @Test
        @DisplayName("eviction keeps windows that are still live")
        void shouldKeepLiveWindowsOnEviction() {
            final var subject = counter(10, 2);
            subject.admit("198.51.100.1");
            clock.advance(SlidingWindowCounter.WINDOW.plusSeconds(1));
            subject.admit("198.51.100.2");

            // Only the first key has expired, so the table has room for exactly one more.
            assertThatCode(() -> subject.admit("198.51.100.3")).doesNotThrowAnyException();
            assertThat(subject.trackedKeys()).isEqualTo(2);
            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.admit("198.51.100.4"));
        }
    }

    @Test
    @DisplayName("toString names the configuration but never a tracked key")
    void shouldNotExposeTrackedKeysInToString() {
        final var subject = counter(7, 11);
        subject.admit(KEY);

        assertThat(subject).asString()
                .contains(NAME)
                .contains("limit=7")
                .contains("maxTrackedKeys=11")
                .doesNotContain(KEY);
    }

    @Nested
    @DisplayName("admission is atomic, which is the property the lock exists for")
    class Atomicity {

        private static final int ROUNDS = 100;
        private static final int RACERS = 4;

        /**
         * The class Javadoc claims that two threads arriving at {@code limit - 1} cannot both be admitted. That
         * claim is the whole reason {@code admit} prunes, tests and inserts under one lock, so it is asserted
         * directly rather than left to inspection: without the {@code synchronized} block several racers see a
         * window of size {@code limit - 1} and all of them insert.
         *
         * <p>Each round uses a fresh counter with one slot already spent, so exactly one racer may take the
         * remaining slot. The clock is not advanced during a round, so no entry can expire mid-race and the
         * arithmetic is exact rather than probabilistic.
         *
         * @throws Exception if a racer thread fails or does not finish
         */
        @Test
        @DisplayName("exactly one of several threads racing for the last slot is admitted")
        void shouldAdmitExactlyOneRacerForTheLastSlot() throws Exception {
            final var executor = Executors.newFixedThreadPool(RACERS);
            try {
                for (var round = 0; round < ROUNDS; round++) {
                    final var subject = counter(2, GENEROUS_KEY_TABLE);
                    subject.admit(KEY);

                    final var start = new CyclicBarrier(RACERS);
                    final var admitted = new AtomicInteger();
                    final var refused = new AtomicInteger();
                    final var futures = new ArrayList<Future<?>>(RACERS);
                    for (var racer = 0; racer < RACERS; racer++) {
                        futures.add(executor.submit(() -> {
                            start.await(10, TimeUnit.SECONDS);
                            try {
                                subject.admit(KEY);
                                admitted.incrementAndGet();
                            } catch (final RateLimitedException expected) {
                                refused.incrementAndGet();
                            }
                            return null;
                        }));
                    }
                    for (final var future : futures) {
                        future.get(10, TimeUnit.SECONDS);
                    }

                    assertThat(admitted.get())
                            .as("round %d admitted more callers than the one remaining slot", round)
                            .isEqualTo(1);
                    assertThat(refused.get()).isEqualTo(RACERS - 1);
                    assertThat(subject.trackedKeys()).isEqualTo(1);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
