package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.config.RealtimeProperties;
import org.maglez.eop.application.port.in.SessionEvent;
import org.maglez.eop.application.port.in.SessionEventType;
import org.maglez.eop.application.TooManySubscribersException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.maglez.eop.outbound.web.SseSessionEventPublisher;

/**
 * Covers the registry and the heartbeat without a servlet container.
 *
 * <p>Delivery over a real socket is proved elsewhere, by a test that reads the
 * wire. What is worth asserting here is the bookkeeping around it, because every
 * bug this class can have is a leak rather than a visible failure: a subscriber
 * that is never forgotten holds a connection and a slot in a broadcast list for
 * as long as the process lives, and nothing in the application would complain.
 *
 * <p>The heartbeat interval is fifty milliseconds so that a sweep can be observed
 * within a test rather than in fifteen seconds' time.
 */
@DisplayName("SseSessionEventPublisher")
class SseSessionEventPublisherTest {

    private static final Duration BEAT = Duration.ofMillis(50);

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    private static final UUID OTHER_SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ee");

    private final SseSessionEventPublisher publisher = new SseSessionEventPublisher(new RealtimeProperties(BEAT));

    @AfterEach
    void stopTheHeartbeatThread() throws Exception {
        publisher.destroy();
    }

    @Nested
    @DisplayName("registering a listener")
    class Subscribing {

        @Test
        @DisplayName("a subscription is counted against its own session only")
        void shouldCountSubscribersPerSession() {
            publisher.subscribe(SESSION_ID);
            publisher.subscribe(SESSION_ID);
            publisher.subscribe(OTHER_SESSION_ID);

            assertThat(publisher.subscriberCount(SESSION_ID)).isEqualTo(2);
            assertThat(publisher.subscriberCount(OTHER_SESSION_ID)).isEqualTo(1);
        }

        @Test
        @DisplayName("a session nobody is watching has no subscribers rather than no entry to ask about")
        void shouldReportZeroForAnUnwatchedSession() {
            assertThat(publisher.subscriberCount(SESSION_ID)).isZero();
        }

        @Test
        @DisplayName("a missing session identifier is refused rather than registered under null")
        void shouldRejectANullSessionIdentifier() {
            assertThatNullPointerException()
                    .isThrownBy(() -> publisher.subscribe(null))
                    .withMessageContaining("sessionId");
        }

        @Test
        @DisplayName("the thirteenth subscriber for one session is refused, because the per-session cap is 12")
        void shouldRejectAThirteenthSubscriberForTheSameSession() {
            for (int i = 0; i < SseSessionEventPublisher.MAX_SUBSCRIBERS_PER_SESSION; i++) {
                publisher.subscribe(SESSION_ID);
            }

            assertThatThrownBy(() -> publisher.subscribe(SESSION_ID))
                    .isInstanceOf(TooManySubscribersException.class)
                    .hasMessageContaining(SESSION_ID.toString());
        }

        @Test
        @DisplayName("a subscriber is refused when the global cap is reached")
        void shouldEnforceTheGlobalSubscriberCap() throws Exception {
            final int smallCap = 2;
            final SseSessionEventPublisher capped =
                    new SseSessionEventPublisher(new RealtimeProperties(BEAT), smallCap);
            try {
                capped.subscribe(SESSION_ID);
                capped.subscribe(OTHER_SESSION_ID);

                assertThatThrownBy(() -> capped.subscribe(
                        UUID.fromString("00000000-0000-7000-8000-0000000000dd")))
                        .isInstanceOf(TooManySubscribersException.class);
            }
            finally {
                capped.destroy();
            }
        }

        @Test
        @DisplayName("the emitter timeout is ten minutes, not unlimited")
        void shouldUseANonZeroEmitterTimeout() {
            assertThat(SseSessionEventPublisher.EMITTER_TIMEOUT_MILLIS)
                    .isEqualTo(TimeUnit.MINUTES.toMillis(10))
                    .isGreaterThan(0L);
        }

        @Test
        @DisplayName("concurrent subscribers cannot exceed the per-session cap")
        void shouldEnforcePerSessionCapUnderConcurrency() throws Exception {
            final int threads = 50;
            final int cap = 12; // MAX_SUBSCRIBERS_PER_SESSION
            final SseSessionEventPublisher concPublisher =
                    new SseSessionEventPublisher(new RealtimeProperties(BEAT), cap, 500);
            final ExecutorService pool = Executors.newFixedThreadPool(threads);
            final AtomicInteger admitted = new AtomicInteger(0);
            final AtomicInteger refused = new AtomicInteger(0);
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);

            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            concPublisher.subscribe(SESSION_ID);
                            admitted.incrementAndGet();
                        } catch (final TooManySubscribersException e) {
                            refused.incrementAndGet();
                        } catch (final Exception e) {
                            // ignore other exceptions
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                done.await(5, TimeUnit.SECONDS);
                pool.shutdown();

                assertThat(admitted.get()).isEqualTo(cap);
                assertThat(refused.get()).isEqualTo(threads - cap);
                assertThat(concPublisher.subscriberCount(SESSION_ID)).isEqualTo(cap);
            } finally {
                concPublisher.destroy();
            }
        }

        @Test
        @DisplayName("completing a subscriber frees its global slot for a new subscriber")
        void shouldFreeGlobalSlotWhenSubscriberCompletes() throws Exception {
            final SseSessionEventPublisher capped =
                    new SseSessionEventPublisher(new RealtimeProperties(BEAT), 12, 1); // global cap = 1
            try {
                final SseEmitter first = capped.subscribe(SESSION_ID);
                // Global cap is now full
                assertThatThrownBy(() -> capped.subscribe(SESSION_ID))
                        .isInstanceOf(TooManySubscribersException.class);
                // Complete the first subscriber. In unit tests without a servlet container
                // the SseEmitter handler is never initialized, so complete() only marks the
                // emitter as done — it does NOT fire the onCompletion callback directly.
                // The heartbeat discovers the dead emitter on its next sweep (write throws
                // IllegalStateException), which calls forgetOne and decrements the counter.
                first.complete();
                // Wait for the heartbeat to discover the dead emitter and free the slot
                await().atMost(Duration.ofSeconds(5))
                        .pollInterval(BEAT)
                        .untilAsserted(() -> assertThat(capped.subscriberCount(SESSION_ID)).isZero());
                // Now a new subscriber should be admitted
                assertThatCode(() -> capped.subscribe(SESSION_ID))
                        .doesNotThrowAnyException();
            } finally {
                capped.destroy();
            }
        }
    }

    @Nested
    @DisplayName("announcing an event")
    class Publishing {

        @Test
        @DisplayName("an event for a session nobody is watching is dropped without complaint")
        void shouldIgnoreAnEventWithNoSubscribers() {
            assertThatCode(() -> publisher.publish(playerJoined())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a live subscriber is kept, because delivery succeeded")
        void shouldKeepALiveSubscriber() {
            publisher.subscribe(SESSION_ID);

            publisher.publish(playerJoined());

            // The fan-out now runs on a send-pool thread (EOP-223), so the write
            // completes asynchronously. We wait until the pool thread has finished
            // rather than asserting immediately, which would be a race.
            //
            // The sibling test (shouldForgetADepartedSubscriberWithoutFailingTheCaller)
            // also uses await() for the same reason: it waits for the count to reach
            // zero after a departed subscriber is evicted. Here we wait for the count
            // to stabilise at one after a successful write. An immediate assertion
            // would pass today only because SseEmitter.send() happens not to throw
            // without a servlet container — a coincidence, not a guarantee.
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(BEAT)
                    .untilAsserted(() -> assertThat(publisher.subscriberCount(SESSION_ID)).isEqualTo(1));
        }

        @Test
        @DisplayName("a departed subscriber is forgotten on the next write and the join that triggered it still succeeds")
        void shouldForgetADepartedSubscriberWithoutFailingTheCaller() {
            final SseEmitter departed = publisher.subscribe(SESSION_ID);
            departed.complete();

            assertThatCode(() -> publisher.publish(playerJoined())).doesNotThrowAnyException();

            // The write that discovers the departure now happens on a send-pool
            // thread rather than on the publishing thread (EOP-223), so eviction
            // is eventually consistent with the call rather than complete by the
            // time it returns.
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(BEAT)
                    .untilAsserted(() -> assertThat(publisher.subscriberCount(SESSION_ID)).isZero());
        }

        @Test
        @DisplayName("a missing event is refused")
        void shouldRejectANullEvent() {
            assertThatNullPointerException()
                    .isThrownBy(() -> publisher.publish(null))
                    .withMessageContaining("event");
        }

        private SessionEvent playerJoined() {
            return new SessionEvent(SessionEventType.PLAYER_JOINED, SESSION_ID, Instant.parse("2026-02-01T09:30:00Z"));
        }
    }

    @Nested
    @DisplayName("keeping the list honest")
    class Heartbeat {

        @Test
        @DisplayName("a peer that went away is discovered by the heartbeat, with no event to prompt it")
        void shouldDiscoverADepartedPeerUnprompted() {
            final SseEmitter departed = publisher.subscribe(SESSION_ID);
            departed.complete();

            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(BEAT)
                    .untilAsserted(() -> assertThat(publisher.subscriberCount(SESSION_ID)).isZero());
        }

        @Test
        @DisplayName("a live subscriber survives repeated sweeps")
        void shouldLeaveALiveSubscriberAlone() throws InterruptedException {
            publisher.subscribe(SESSION_ID);

            Thread.sleep(BEAT.toMillis() * 4);

            assertThat(publisher.subscriberCount(SESSION_ID)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("losing every listener")
    class Forgetting {

        @Test
        @DisplayName("forgetting everyone leaves no subscriber for any session")
        void shouldDropEveryRegistration() {
            publisher.subscribe(SESSION_ID);
            publisher.subscribe(OTHER_SESSION_ID);

            publisher.forgetEveryone();

            assertThat(publisher.subscriberCount(SESSION_ID)).isZero();
            assertThat(publisher.subscriberCount(OTHER_SESSION_ID)).isZero();
        }

        @Test
        @DisplayName("shutting down closes every stream and stops the heartbeat thread")
        void shouldCompleteEveryStreamOnShutdown() {
            final SseEmitter stream = publisher.subscribe(SESSION_ID);

            assertThatCode(() -> publisher.destroy()).doesNotThrowAnyException();

            assertThat(publisher.subscriberCount(SESSION_ID)).isZero();
            assertThatThrownBy(() -> stream.send(SseEmitter.event().comment("after shutdown")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
