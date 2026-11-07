package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.config.RealtimeProperties;
import org.maglez.eop.application.port.in.SessionEvent;
import org.maglez.eop.application.port.in.SessionEventType;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.maglez.eop.outbound.web.SseSessionEventPublisher;

/**
 * Covers the three error paths in {@link SseSessionEventPublisher} that the main
 * test suite does not reach: the {@code LoggingDiscardPolicy} rejection path, the
 * {@code RuntimeException} WARN path inside the {@code publish()} task, and the
 * same path inside the {@code beat()} heartbeat task.
 *
 * <p>All three tests use the {@code newEmitter()} override seam established by
 * {@code SsePublishDoesNotBlockTheCallerTest} and log-capture via Logback's
 * {@code ListAppender}, the same mechanism used in {@code GlobalExceptionHandlerTest}.
 *
 * <p>Assertions that wait for a pool thread to log take a {@code List.copyOf} snapshot
 * of {@code captured.list} before iterating, because {@code ListAppender} uses a plain
 * {@code ArrayList} that the logger thread can modify concurrently with the assertion.
 */
@DisplayName("SseSessionEventPublisher — error paths")
class SseSessionEventPublisherEdgeCasesTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000fc");

    // -------------------------------------------------------------------------
    // Gap 1: LoggingDiscardPolicy.rejectedExecution()
    // -------------------------------------------------------------------------

    /**
     * Verifies that when every send-pool thread is occupied and a new task cannot be
     * accepted, {@code LoggingDiscardPolicy} logs a WARN and the write is dropped
     * silently rather than throwing back to the caller.
     *
     * <p><strong>Construction arithmetic.</strong> The pool's maximum size is
     * {@code Math.max(SEND_POOL_CORE_THREADS=4, maxTotalSubscribers)}, so passing
     * {@code maxTotalSubscribers=4} gives a ceiling of exactly 4. A
     * {@code SynchronousQueue} accepts a task only when a thread is ready to take it
     * immediately; if all 4 threads are parked on blocking writes, the 5th submission
     * hits the rejection policy.
     *
     * <p>The 4 blocking emitters each let their first write through (the
     * {@code subscribe} "subscribed" frame), then block on the second. A single
     * {@code publish()} call submits one task per subscriber, parking all 4 threads.
     * A second {@code publish()} then offers 4 more tasks; at least one must be
     * rejected because all threads are still parked.
     *
     * <p>The rejection fires on the calling thread (the {@code SynchronousQueue}
     * rejects synchronously), so there is no concurrent modification of
     * {@code captured.list} and no snapshot is needed here.
     */
    @Nested
    @DisplayName("rejection policy")
    class RejectionPolicy {

        private final Logger logger = (Logger) LoggerFactory.getLogger(SseSessionEventPublisher.class);
        private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
        private Level originalLevel;

        private final AtomicInteger emitterIndex = new AtomicInteger();
        private final BlockingEmitter[] blockers = {
            new BlockingEmitter(), new BlockingEmitter(),
            new BlockingEmitter(), new BlockingEmitter()
        };

        /**
         * Publisher with maxTotalSubscribers=4 so the pool ceiling is exactly 4.
         * Heartbeat is parked an hour away so it does not consume pool threads
         * during the test.
         */
        private final SseSessionEventPublisher publisher = new SseSessionEventPublisher(
                new RealtimeProperties(Duration.ofHours(1)), 4) {
            @Override
            public SseEmitter newEmitter() {
                final int idx = emitterIndex.getAndIncrement();
                return idx < blockers.length ? blockers[idx] : new SseEmitter();
            }
        };

        @BeforeEach
        void captureLogging() {
            originalLevel = logger.getLevel();
            logger.setLevel(Level.WARN);
            captured.start();
            logger.addAppender(captured);
        }

        @AfterEach
        void releaseLoggingAndShutDown() throws Exception {
            logger.detachAppender(captured);
            captured.stop();
            logger.setLevel(originalLevel);
            for (final BlockingEmitter b : blockers) {
                b.release();
            }
            publisher.destroy();
        }

        @Test
        @DisplayName("a WARN is logged and the write is dropped when every send thread is occupied")
        void shouldLogWarnAndDropWhenAllThreadsAreOccupied() throws Exception {
            // Arrange: subscribe 4 times — each gets a blocking emitter.
            for (int i = 0; i < 4; i++) {
                publisher.subscribe(SESSION_ID);
            }

            // Act: first publish parks all 4 pool threads on blocking writes.
            publisher.publish(playerJoined());

            // Wait until all 4 blocking emitters have their second write in progress,
            // confirming all pool threads are occupied before we offer the 5th task.
            for (final BlockingEmitter b : blockers) {
                assertThat(b.awaitWriteInProgress())
                        .as("blocking emitter must be in progress before the 5th task is offered")
                        .isTrue();
            }

            // Act: second publish offers 4 more tasks; at least one must be rejected
            // because all threads are still parked.
            publisher.publish(playerJoined());

            // Assert: the rejection policy must have logged at least one WARN.
            // Rejection fires on the calling thread, so captured.list is safe to read
            // directly without a snapshot.
            assertThat(captured.list)
                    .as("LoggingDiscardPolicy must emit a WARN when a task is rejected")
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("No SSE send thread available"));
        }
    }

    // -------------------------------------------------------------------------
    // Gap 2: RuntimeException WARN path inside publish() task
    // -------------------------------------------------------------------------

    /**
     * Verifies that when an emitter's {@code send()} throws an unexpected
     * {@code RuntimeException} (not {@code IOException} or
     * {@code IllegalStateException}, which are handled as departed-peer signals),
     * the {@code publish()} task logs a WARN and leaves the subscriber registered
     * rather than evicting it.
     */
    @Nested
    @DisplayName("unexpected RuntimeException during publish")
    class PublishRuntimeException {

        /**
         * Heartbeat interval is deliberately long so the heartbeat does not fire
         * during this test and add its own WARN to the captured list before the
         * publish WARN arrives.
         */
        private static final Duration LONG_BEAT = Duration.ofHours(1);

        private final Logger logger = (Logger) LoggerFactory.getLogger(SseSessionEventPublisher.class);
        private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
        private Level originalLevel;

        private final ThrowingEmitter thrower = new ThrowingEmitter();

        private final SseSessionEventPublisher publisher = new SseSessionEventPublisher(
                new RealtimeProperties(LONG_BEAT)) {
            @Override
            public SseEmitter newEmitter() {
                return thrower;
            }
        };

        @BeforeEach
        void captureLogging() {
            originalLevel = logger.getLevel();
            logger.setLevel(Level.WARN);
            captured.start();
            logger.addAppender(captured);
        }

        @AfterEach
        void releaseLoggingAndShutDown() throws Exception {
            logger.detachAppender(captured);
            captured.stop();
            logger.setLevel(originalLevel);
            publisher.destroy();
        }

        @Test
        @DisplayName("a WARN is logged and the subscriber is kept when send() throws an unexpected RuntimeException")
        void shouldLogWarnAndKeepSubscriberOnUnexpectedRuntimeException() {
            // Arrange: subscribe — the thrower lets the "subscribed" frame through,
            // then throws RuntimeException on every subsequent write.
            publisher.subscribe(SESSION_ID);

            // Act: publish triggers the RuntimeException path on a pool thread.
            publisher.publish(playerJoined());

            // Assert: a WARN naming the event type and session must appear.
            // The message text "Could not announce" distinguishes this from the
            // heartbeat WARN ("Heartbeat to a subscriber"), which uses the same
            // logger and the same session ID.
            // A defensive List.copyOf snapshot is taken before asserting because
            // the logger appends to ListAppender.list from a pool thread concurrently
            // with the assertion iterator.
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() ->
                        assertThat(List.copyOf(captured.list))
                                .as("publish() must WARN on unexpected RuntimeException")
                                .anyMatch(e -> e.getLevel() == Level.WARN
                                        && e.getFormattedMessage().contains("Could not announce")
                                        && e.getFormattedMessage().contains(SESSION_ID.toString())));

            // The subscriber must NOT have been evicted — the stream stays open.
            assertThat(publisher.subscriberCount(SESSION_ID))
                    .as("subscriber must remain registered after an unexpected RuntimeException")
                    .isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // Gap 3: RuntimeException WARN path inside beat() heartbeat task
    // -------------------------------------------------------------------------

    /**
     * Verifies that when an emitter's {@code send()} throws an unexpected
     * {@code RuntimeException} during a heartbeat sweep, the beat task logs a WARN
     * and leaves the subscriber registered rather than evicting it.
     */
    @Nested
    @DisplayName("unexpected RuntimeException during heartbeat")
    class BeatRuntimeException {

        /** Short enough that a sweep fires within the test's wait window. */
        private static final Duration BEAT = Duration.ofMillis(50);

        private final Logger logger = (Logger) LoggerFactory.getLogger(SseSessionEventPublisher.class);
        private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
        private Level originalLevel;

        private final ThrowingEmitter thrower = new ThrowingEmitter();

        private final SseSessionEventPublisher publisher = new SseSessionEventPublisher(
                new RealtimeProperties(BEAT)) {
            @Override
            public SseEmitter newEmitter() {
                return thrower;
            }
        };

        @BeforeEach
        void captureLogging() {
            originalLevel = logger.getLevel();
            logger.setLevel(Level.WARN);
            captured.start();
            logger.addAppender(captured);
        }

        @AfterEach
        void releaseLoggingAndShutDown() throws Exception {
            logger.detachAppender(captured);
            captured.stop();
            logger.setLevel(originalLevel);
            publisher.destroy();
        }

        @Test
        @DisplayName("a WARN is logged and the subscriber is kept when the heartbeat send() throws an unexpected RuntimeException")
        void shouldLogWarnAndKeepSubscriberOnUnexpectedRuntimeExceptionDuringBeat() {
            // Arrange: subscribe — the thrower lets the "subscribed" frame through,
            // then throws RuntimeException on every subsequent write (including heartbeats).
            publisher.subscribe(SESSION_ID);

            // Assert: wait for the heartbeat to fire and log the WARN.
            // The message text "Heartbeat to a subscriber" distinguishes this from
            // the publish WARN ("Could not announce"), which uses the same logger
            // and the same session ID.
            // No explicit Act needed — the heartbeat fires automatically.
            // A defensive List.copyOf snapshot is taken before asserting because
            // the logger appends to ListAppender.list from a pool thread concurrently
            // with the assertion iterator.
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(BEAT)
                    .untilAsserted(() ->
                        assertThat(List.copyOf(captured.list))
                                .as("beat() must WARN on unexpected RuntimeException")
                                .anyMatch(e -> e.getLevel() == Level.WARN
                                        && e.getFormattedMessage().contains("Heartbeat to a subscriber")
                                        && e.getFormattedMessage().contains(SESSION_ID.toString())));

            // The subscriber must NOT have been evicted — the stream stays open.
            assertThat(publisher.subscriberCount(SESSION_ID))
                    .as("subscriber must remain registered after an unexpected RuntimeException in beat()")
                    .isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static SessionEvent playerJoined() {
        return new SessionEvent(SessionEventType.PLAYER_JOINED, SESSION_ID,
                Instant.parse("2026-09-06T10:00:00Z"));
    }

    /**
     * An emitter that lets its first write through (the {@code subscribe}
     * "subscribed" frame) and then blocks on every subsequent write.
     *
     * <p>Used to park a send-pool thread so that the pool fills up and the
     * rejection policy fires.
     */
    static final class BlockingEmitter extends SseEmitter {

        private final CountDownLatch writeInProgress = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public void send(final SseEventBuilder builder) throws IOException {
            if (writes.getAndIncrement() == 0) {
                // Let the subscribe "subscribed" frame through.
                return;
            }
            writeInProgress.countDown();
            try {
                if (!released.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("stall window elapsed before release");
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

    /**
     * An emitter that lets its first write through (the {@code subscribe}
     * "subscribed" frame) and then throws an unexpected {@code RuntimeException}
     * on every subsequent write.
     *
     * <p>This exercises the {@code catch (RuntimeException unexpected)} branch in
     * both {@code publish()} and {@code beat()}, which is distinct from the
     * {@code IOException}/{@code IllegalStateException} departed-peer branch.
     */
    static final class ThrowingEmitter extends SseEmitter {

        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public void send(final SseEventBuilder builder) throws IOException {
            if (writes.getAndIncrement() == 0) {
                // Let the subscribe "subscribed" frame through.
                return;
            }
            throw new RuntimeException("simulated unexpected failure — not a departed peer");
        }
    }
}
