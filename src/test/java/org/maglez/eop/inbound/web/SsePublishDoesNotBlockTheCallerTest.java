package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.config.RealtimeProperties;
import org.maglez.eop.application.port.in.SessionEvent;
import org.maglez.eop.application.port.in.SessionEventType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.maglez.eop.outbound.web.SseSessionEventPublisher;

/**
 * Pins the fix for EOP-223: one subscriber that has stopped reading its socket
 * must not delay the request that published the event.
 *
 * <p>The reported symptom was a join POST hanging for tens of seconds with no
 * server-side log line at all, only ever with several browsers attached to one
 * session. The cause was that the fan-out wrote to every subscriber's socket
 * serially on the publishing request's own thread. A peer that has departed
 * makes that write throw, which the class always handled; a peer that is still
 * connected but has stopped draining its socket -- a page mid-reload, a browser
 * context being torn down -- makes it <em>block</em> instead, which nothing
 * handled and nothing logged.
 *
 * <p>Reproducing that needs an emitter whose write blocks, which no real
 * subscriber can be talked into doing on demand, so the publisher's emitter
 * factory is overridden here. The blocking emitter lets its first write through,
 * because that one is {@code subscribe}'s own "subscribed" frame and is issued
 * before the test has anything to observe.
 *
 * <p>The heartbeat is parked an hour away deliberately. It shares the send pool
 * with publishing, so a fifty-millisecond sweep would keep handing the stalled
 * emitter fresh writes and occupy the pool with them, which is a genuine
 * capacity characteristic but not the thing under test here.
 */
@DisplayName("publishing to a subscriber that has stopped reading")
class SsePublishDoesNotBlockTheCallerTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000fd");

    /** Longer than any assertion below waits, so a regression stalls rather than merely slows. */
    private static final Duration READER_STALL = Duration.ofSeconds(30);

    /** Comfortably above a thread hand-off and far below {@link #READER_STALL}. */
    private static final Duration PUBLISH_BUDGET = Duration.ofSeconds(2);

    private final BlockingEmitter stalled = new BlockingEmitter();

    private final CountingEmitter healthy = new CountingEmitter();

    private final SseSessionEventPublisher publisher = new StubbedEmitterPublisher();

    @AfterEach
    void releaseTheStalledReaderAndShutDown() throws Exception {
        stalled.release();
        publisher.destroy();
    }

    @Test
    @DisplayName("returns to the caller promptly instead of stalling the request that triggered it")
    void shouldNotBlockThePublishingThread() throws Exception {
        publisher.subscribe(SESSION_ID);

        final Instant before = Instant.now();
        publisher.publish(playerJoined());
        final Duration elapsed = Duration.between(before, Instant.now());

        assertThat(stalled.awaitWriteInProgress())
                .as("the stalled subscriber's write must actually be under way, "
                        + "otherwise this test proves nothing about blocking")
                .isTrue();
        assertThat(elapsed)
                .as("publish must hand the write off rather than wait for it")
                .isLessThan(PUBLISH_BUDGET);
    }

    @Test
    @DisplayName("still reaches the other subscribers rather than queueing behind the stalled one")
    void shouldStillDeliverToAHealthySubscriber() throws Exception {
        publisher.subscribe(SESSION_ID);
        publisher.subscribe(SESSION_ID);

        publisher.publish(playerJoined());

        assertThat(stalled.awaitWriteInProgress()).isTrue();

        // Two writes: subscribe's own "subscribed" frame, then the event.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(healthy.writes()).isEqualTo(2));
    }

    private SessionEvent playerJoined() {
        return new SessionEvent(SessionEventType.PLAYER_JOINED, SESSION_ID, Instant.parse("2026-09-06T10:15:00Z"));
    }

    /**
     * Hands out the stalled emitter first, so the fan-out meets it before the
     * healthy one and a serial implementation would never reach the latter.
     */
    private final class StubbedEmitterPublisher extends SseSessionEventPublisher {

        private final AtomicInteger handedOut = new AtomicInteger();

        StubbedEmitterPublisher() {
            super(new RealtimeProperties(Duration.ofHours(1)));
        }

        @Override
        public SseEmitter newEmitter() {
            return handedOut.getAndIncrement() == 0 ? stalled : healthy;
        }
    }

    /**
     * An emitter standing in for a peer that is still connected and no longer
     * draining its socket: the first write succeeds, every later one blocks.
     */
    private static final class BlockingEmitter extends SseEmitter {

        private final CountDownLatch writeInProgress = new CountDownLatch(1);

        private final CountDownLatch released = new CountDownLatch(1);

        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public void send(final SseEventBuilder builder) throws IOException {
            if (writes.getAndIncrement() == 0) {
                return;
            }
            writeInProgress.countDown();
            try {
                if (!released.await(READER_STALL.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("the stall window elapsed before the test released this emitter");
                }
            }
            catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitWriteInProgress() throws InterruptedException {
            return writeInProgress.await(5, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }
    }

    /** An emitter standing in for a peer that is reading normally. */
    private static final class CountingEmitter extends SseEmitter {

        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public void send(final SseEventBuilder builder) {
            writes.incrementAndGet();
        }

        int writes() {
            return writes.get();
        }
    }
}
