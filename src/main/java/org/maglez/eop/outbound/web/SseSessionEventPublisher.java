package org.maglez.eop.outbound.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.maglez.eop.config.RealtimeProperties;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.application.port.in.SessionEvent;
import org.maglez.eop.application.port.out.SessionEventPublisher;
import org.maglez.eop.application.TooManySubscribersException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.maglez.eop.inbound.web.SessionEventDto;

/**
 * Fans session change notifications out to whoever is listening.
 *
 * <p>Server-sent events over the emitter that ships with Spring MVC, so no new
 * dependency and no second protocol (ADR-014).
 *
 * <p><strong>The registry is a broadcast list, not a presence list.</strong> An
 * entry means "the last write to this connection succeeded", which is a weaker claim
 * than "somebody is there": a client that closed its laptop lid is only discovered on
 * the next write. The EOP-8 spike watched this happen — two subscribers reported
 * live after both browsers had gone. Anything that needs to know who is actually
 * present must ask the database, and {@code connectionStatus} is documented as
 * advisory for the same reason.
 *
 * <p><strong>No event history is kept.</strong> There is nothing to replay and
 * {@code Last-Event-ID} is not honoured, because a restart resets any counter and an
 * identifier minted by a previous process names an event this one cannot produce.
 * Reconnection is a re-read of {@code GET /api/v1/sessions/{sessionId}}, never a
 * replay. Keeping a replayable log would mean a second source of truth, and the
 * database is the only authority.
 *
 * <p><strong>Publishing never fails <em>or delays</em> the request that triggered
 * it.</strong> A departed subscriber is the normal case, not an error: a player
 * whose join succeeded must not receive a failure because somebody else closed a
 * tab. Nor may it receive a slow one. Swallowing {@code IOException} only ever
 * covered half of that promise, because a peer that has stopped draining its
 * socket makes the write <em>block</em> instead of throwing; until EOP-223 the
 * fan-out ran on the triggering request's own thread, so one such peer stalled
 * that request for tens of seconds while logging nothing at all. Per-subscriber
 * writes are handed to a bounded pool and the caller does not wait for them.
 *
 * <p><strong>Subscriber caps prevent unbounded resource consumption (EOP-20, ADR-034).</strong>
 * A per-session cap of {@value #MAX_SUBSCRIBERS_PER_SESSION} (2× MAXIMUM_PLAYERS to allow
 * reconnect churn) and a global cap of {@value #MAX_TOTAL_SUBSCRIBERS} are enforced before
 * any emitter is created. Both caps throw {@link TooManySubscribersException}, mapped to
 * HTTP 429 by {@code GlobalExceptionHandler}.
 */
@Component
public class SseSessionEventPublisher implements SessionEventPublisher, DisposableBean {

    /**
     * Emitter timeout in milliseconds. Matches {@code spring.mvc.async.request-timeout}.
     *
     * <p>A non-zero timeout means the servlet container will close an idle stream after
     * ten minutes, which bounds the maximum time a file descriptor is held by a client
     * that has silently disappeared without the heartbeat noticing.
     */
    public static final long EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /**
     * Maximum number of concurrent SSE subscribers per session.
     *
     * <p>2× {@link GameSession#MAXIMUM_PLAYERS} (6) = 12, allowing reconnect churn
     * where the old connection has not yet been garbage-collected when the client
     * reconnects.
     */
    public static final int MAX_SUBSCRIBERS_PER_SESSION = 2 * GameSession.MAXIMUM_PLAYERS;

    /**
     * Maximum total SSE subscribers across all sessions.
     *
     * <p>Hard ceiling to prevent descriptor exhaustion. See ADR-034.
     */
    public static final int MAX_TOTAL_SUBSCRIBERS = 500;

    /**
     * How long a client should wait before reconnecting, in milliseconds.
     *
     * <p>Written once per subscription as the stream's {@code retry:} field. Three
     * seconds is long enough that a restarting container is not hammered and short
     * enough that a facilitator does not narrate the wait.
     */
    private static final long RECONNECT_HINT_MILLIS = 3000L;

    /**
     * Threads the send pool keeps alive whether or not there is anything to write.
     *
     * <p>Four carries a heartbeat sweep of an ordinary lobby without creating anything.
     * The pool grows past this on demand rather than queueing — see the constructor.
     */
    private static final int SEND_POOL_CORE_THREADS = 4;

    /**
     * How long a thread created above {@code SEND_POOL_CORE_THREADS} waits for further
     * work before retiring, in seconds.
     *
     * <p>Long enough that a burst of play does not churn threads, short enough that the
     * pool returns to its resting size well within a single game.
     */
    private static final long SEND_POOL_KEEP_ALIVE_SECONDS = 60L;

    private static final Logger LOG = LoggerFactory.getLogger(SseSessionEventPublisher.class);

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** Per-session lock objects, kept in sync with the subscribers map. */
    private final Map<UUID, Object> sessionLocks = new ConcurrentHashMap<>();

    private final AtomicInteger totalSubscriberCount = new AtomicInteger();

    private final AtomicInteger sendThreadCounter = new AtomicInteger();

    private final ScheduledExecutorService heartbeats;

    private final ExecutorService sendPool;

    private final Duration heartbeatInterval;

    private final int maxPerSessionSubscribers;

    private final int maxTotalSubscribers;

    /**
     * Creates the publisher Spring uses, with the production subscriber ceilings.
     *
     * <p>Delegates to the three-argument constructor with
     * {@value #MAX_SUBSCRIBERS_PER_SESSION} as the per-session cap and
     * {@value #MAX_TOTAL_SUBSCRIBERS} as the global cap. Tests that need lower
     * ceilings use the package-private constructors below rather than lowering
     * these constants.
     *
     * @param properties heartbeat configuration
     */
    @Autowired
    public SseSessionEventPublisher(final RealtimeProperties properties) {
        this(properties, MAX_SUBSCRIBERS_PER_SESSION, MAX_TOTAL_SUBSCRIBERS);
    }

    /**
     * Package-private constructor for tests that need a reduced global cap.
     *
     * @param properties          heartbeat configuration
     * @param maxTotalSubscribers override for the global subscriber ceiling
     */
    public SseSessionEventPublisher(final RealtimeProperties properties, final int maxTotalSubscribers) {
        this(properties, MAX_SUBSCRIBERS_PER_SESSION, maxTotalSubscribers);
    }

    /**
     * Package-private constructor for tests that need a reduced per-session and/or global cap.
     *
     * <p><strong>The send pool grows rather than queues, and its ceiling is the subscriber
     * ceiling.</strong> A fixed pool of four was tried first and failed: a peer that has
     * stopped draining its socket occupies a thread for as long as the write blocks, so four
     * such peers starved every healthy subscriber behind them and notifications arrived
     * minutes late or not at all (EOP-223). A {@code SynchronousQueue} never accepts a task
     * that no thread is ready to take, so the pool creates a thread instead of making a
     * healthy subscriber wait behind a sick one, and {@code maxTotalSubscribers} bounds that
     * growth by the same number that already bounds file descriptors. Threads above the core
     * size retire again once play goes quiet. The {@code Math.max} is not decoration: the
     * test constructors pass ceilings below the core size, and a maximum pool size below the
     * core size is an {@code IllegalArgumentException}.
     *
     * <p>The rejection policy therefore fires only when every thread up to that ceiling is
     * already parked on a write, which means as many stalled peers as the deployment allows
     * subscribers. It is logged rather than silent — see {@code LoggingDiscardPolicy}.
     *
     * @param properties             heartbeat configuration
     * @param maxPerSessionSubscribers override for the per-session subscriber ceiling
     * @param maxTotalSubscribers    override for the global subscriber ceiling
     */
    public SseSessionEventPublisher(final RealtimeProperties properties,
            final int maxPerSessionSubscribers, final int maxTotalSubscribers) {
        Objects.requireNonNull(properties, "properties is required");
        this.heartbeatInterval = properties.heartbeatInterval();
        this.maxPerSessionSubscribers = maxPerSessionSubscribers;
        this.maxTotalSubscribers = maxTotalSubscribers;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.sendPool = new ThreadPoolExecutor(
                SEND_POOL_CORE_THREADS,
                Math.max(SEND_POOL_CORE_THREADS, maxTotalSubscribers),
                SEND_POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    final Thread thread = new Thread(r, "sse-send-" + sendThreadCounter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new LoggingDiscardPolicy()
        );
        final long period = this.heartbeatInterval.toMillis();
        this.heartbeats.scheduleAtFixedRate(this::beat, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers a new listener for one session and returns its open stream.
     *
     * <p>A per-session cap of {@value #MAX_SUBSCRIBERS_PER_SESSION} and a global cap of
     * {@value #MAX_TOTAL_SUBSCRIBERS} are checked before the emitter is created. If either
     * is exceeded, {@link TooManySubscribersException} is thrown and no emitter is allocated.
     *
     * <p>The emitter uses a timeout of ten minutes, held in {@code EMITTER_TIMEOUT_MILLIS} and
     * matching {@code spring.mvc.async.request-timeout}. A non-zero timeout means the container
     * will clean up a stale connection even if the heartbeat sweep has not yet written to it.
     * That constant is computed by a method call rather than written as a literal, so it is not
     * a compile-time constant and cannot be inlined here with {@code @value}.
     *
     * @param sessionId the session whose changes should be delivered
     * @return the stream to return from a controller method
     * @throws TooManySubscribersException if the per-session or global cap is already reached
     */
    public SseEmitter subscribe(final UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");

        // Get or create the lock first — this is safe because we never remove entries
        // from sessionLocks, so the identity of the lock object is stable forever.
        final Object lock = sessionLocks.computeIfAbsent(sessionId, key -> new Object());

        final SseEmitter emitter = newEmitter();

        synchronized (lock) {
            // Get or create the subscriber list inside the lock — also never removed,
            // so no orphan window where a list is added after publish()/beat() checked.
            final CopyOnWriteArrayList<SseEmitter> forSession =
                    subscribers.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());

            if (forSession.size() >= maxPerSessionSubscribers) {
                throw new TooManySubscribersException(
                        "Session " + sessionId + " already has too many subscribers");
            }
            int current;
            do {
                current = totalSubscriberCount.get();
                if (current >= maxTotalSubscribers) {
                    throw new TooManySubscribersException("Subscriber limit reached");
                }
            } while (!totalSubscriberCount.compareAndSet(current, current + 1));

            emitter.onCompletion(() -> forgetOne(forSession, emitter));
            emitter.onTimeout(() -> forgetOne(forSession, emitter));
            emitter.onError(failure -> forgetOne(forSession, emitter));
            forSession.add(emitter);
        }

        try {
            emitter.send(SseEmitter.event()
                    .reconnectTime(RECONNECT_HINT_MILLIS)
                    .comment("subscribed"));
        }
        catch (final IOException | IllegalStateException gone) {
            forgetOne(subscribers.get(sessionId), emitter);
        }
        return emitter;
    }

    /**
     * Creates the emitter handed back to one subscriber.
     *
     * <p>Package-private and overridable so a test can substitute an emitter whose write
     * blocks, which is the only way to reproduce the stall EOP-223 fixed without a real
     * peer that has stopped reading its socket. Production has one implementation.
     *
     * @return a new emitter with the ten-minute timeout in {@code EMITTER_TIMEOUT_MILLIS}
     */
    public SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    }

    /**
     * Announces one event to every stream registered for its session.
     *
     * <p><strong>Each per-subscriber write is handed to the send pool, and this method does
     * not wait for it.</strong> Writing on the calling thread was a latency defect rather
     * than an untidy one: a peer that has stopped draining its socket — a browser tab
     * mid-reload, or a context being torn down — makes the write block instead of throwing,
     * so the fan-out ran to tens of seconds and the HTTP request that triggered the event
     * hung with it, silently, because a blocked write logs nothing and raises nothing.
     * Handing the writes to the pool bounds the caller's cost to the submissions themselves
     * and stops one stalled reader delaying either the caller or the other subscribers
     * (EOP-223). It is what {@code beat()} already did for the heartbeat sweep, and for the
     * same reason (EOP-20, ADR-034).
     *
     * <p>The payload is built once, on the calling thread, so a serialisation fault remains
     * the caller's to see rather than a warning nobody reads on a pool thread.
     *
     * <p><strong>Every event gets its own write.</strong> Notifications are deliberately not
     * coalesced per subscriber: a frame is a doorbell, and only a <em>named</em> frame makes a
     * client re-read {@code GET /api/v1/sessions/{sessionId}}. Skipping one because another
     * write happened to be in flight would therefore lose it outright whenever the write in
     * flight is an unnamed heartbeat comment, which no re-read follows — a client that never
     * learns its hand was dealt. Coalescing was measured on the EOP-217 suite and moved
     * nothing, so the lossless shape is kept (EOP-223).
     *
     * <p>Two consequences of the hand-off are worth knowing. Eviction of a departed
     * subscriber now happens on a pool thread, so {@code subscriberCount} is eventually
     * rather than immediately consistent after a publish. And a write that fails for a
     * reason other than a departed peer can no longer reach the caller, so it is logged
     * here — otherwise it would escape a pool thread and reach {@code System.err}, which
     * the observability rules forbid.
     *
     * @param event the change to announce
     */
    @Override
    public void publish(final SessionEvent event) {
        Objects.requireNonNull(event, "event is required");

        final CopyOnWriteArrayList<SseEmitter> forSession = subscribers.get(event.sessionId());
        if (forSession == null || forSession.isEmpty()) {
            return;
        }

        final SessionEventDto payload = SessionEventDto.from(event);
        for (final SseEmitter emitter : forSession) {
            sendPool.execute(() -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.type().wireName())
                            .data(payload));
                }
                catch (final IOException | IllegalStateException gone) {
                    forgetOne(forSession, emitter);
                }
                catch (final RuntimeException unexpected) {
                    LOG.warn("Could not announce {} to a subscriber of session {}; the stream stays open",
                            event.type().wireName(), event.sessionId(), unexpected);
                }
            });
        }
    }

    /**
     * Reports how many streams are currently registered for a session.
     *
     * <p>For tests only, and deliberately not exposed over HTTP: this number
     * over-reports, so publishing it would invite somebody to treat it as a count of
     * people in the lobby.
     *
     * @param sessionId the session to count
     * @return the number of registered emitters, which is an upper bound on listeners
     */
    public int subscriberCount(final UUID sessionId) {
        final CopyOnWriteArrayList<SseEmitter> forSession = subscribers.get(sessionId);
        return forSession == null ? 0 : forSession.size();
    }

    /**
     * Drops every registration without touching the database.
     *
     * <p>For tests: this is the closest in-process equivalent of the container being
     * replaced by a deployment. What survives it is what a reconnecting player will
     * actually see.
     */
    public void forgetEveryone() {
        subscribers.clear();
        sessionLocks.clear();
        totalSubscriberCount.set(0);
    }

    @Override
    public void destroy() throws Exception {
        heartbeats.shutdownNow();
        heartbeats.awaitTermination(5, TimeUnit.SECONDS);
        subscribers.values().forEach(forSession -> forSession.forEach(this::completeQuietly));
        subscribers.clear();
        sessionLocks.clear();
        totalSubscriberCount.set(0);
        sendPool.shutdownNow();
        sendPool.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Writes a comment frame to every open stream.
     *
     * <p>The frame carries no data and no event name, so a client ignores it. Its
     * only purpose is to be a write: a write is the only thing that discovers a
     * connection the peer has already abandoned, and it also keeps intermediaries
     * from closing a quiet lobby as idle.
     *
     * <p>Per-emitter sends are submitted to {@code sendPool} so that a slow reader
     * cannot block the heartbeat thread and stall the entire sweep. The heartbeat
     * thread submits tasks and returns immediately; it does not wait for them to
     * complete (EOP-20, ADR-034).
     *
     * <p>The whole body is guarded, because an exception escaping a scheduled task
     * cancels the schedule silently — the failure mode would be heartbeats simply
     * stopping, which is precisely the condition they exist to detect.
     */
    private void beat() {
        try {
            subscribers.forEach((sessionId, forSession) -> {
                for (final SseEmitter emitter : forSession) {
                    sendPool.execute(() -> {
                        try {
                            emitter.send(SseEmitter.event().comment("heartbeat"));
                        }
                        catch (final IOException | IllegalStateException gone) {
                            forgetOne(forSession, emitter);
                        }
                        catch (final RuntimeException unexpected) {
                            LOG.warn("Heartbeat to a subscriber of session {} failed; the stream stays open",
                                    sessionId, unexpected);
                        }
                    });
                }
            });
        }
        catch (final RuntimeException unexpected) {
            LOG.warn("Heartbeat sweep failed; streams remain open and the next sweep will retry", unexpected);
        }
    }

    private void forgetOne(final CopyOnWriteArrayList<SseEmitter> list,
            final SseEmitter emitter) {
        if (list != null && list.remove(emitter)) {
            totalSubscriberCount.decrementAndGet();
        }
    }

    private void completeQuietly(final SseEmitter emitter) {
        try {
            emitter.complete();
        }
        catch (final RuntimeException alreadyGone) {
            LOG.debug("Stream was already closed when shutting down", alreadyGone);
        }
    }

    /**
     * Drops a write the send pool has no thread for, and says so.
     *
     * <p>Rejection means every thread up to the subscriber ceiling is already parked on a
     * write, which should not happen in ordinary play — which is precisely why it must be
     * visible when it does, rather than inferred later from a lobby that did not update.
     * This class keeps no event history and does not honour {@code Last-Event-ID}, so a
     * dropped notification is a client that learns nothing until something else prompts it
     * to re-read.
     *
     * <p>Dropping outright rather than reusing Spring's {@code DiscardOldestPolicy} is
     * forced by the queue: that policy polls the queue and then re-enters
     * {@code execute}, and a {@code SynchronousQueue} holds nothing to poll, so the
     * re-entry would be rejected again and recurse.
     */
    private static final class LoggingDiscardPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(final Runnable task, final ThreadPoolExecutor executor) {
            LOG.warn("No SSE send thread available among {}; dropping one write. "
                    + "A subscriber may miss one notification and recovers on its next read.",
                    executor.getMaximumPoolSize());
        }
    }
}
