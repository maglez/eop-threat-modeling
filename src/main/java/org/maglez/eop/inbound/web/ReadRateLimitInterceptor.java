package org.maglez.eop.inbound.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import org.maglez.eop.config.ReadRateLimitProperties;
import org.maglez.eop.application.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Limits how many read requests one client address may make per minute, across every read route.
 *
 * <p>Introduced by EOP-88 (ADR-051). Before it, no read route was rate limited at all: the two existing limiters
 * cover session creation and join attempts, both of which are writes. {@code GET /leaderboard} was the route that
 * prompted the ticket, because it re-derives every score from the session's whole trick history on each read
 * (ADR-030) and the game-over screen offers a retry button that issues one such read per click.
 *
 * <p><strong>Why an interceptor and not a servlet filter.</strong> A filter runs outside the
 * {@code DispatcherServlet}, so a {@link RateLimitedException} thrown there would never reach
 * {@code GlobalExceptionHandler} and the problem+json body would have to be written by hand — a second place that
 * renders errors, which {@code error-handling.md} forbids. {@code HandlerInterceptor#preHandle} runs inside the
 * dispatcher, so the throw is mapped to 429 with an RFC 9457 body and a {@code Retry-After} header by the handler
 * that already exists. The alternatives are compared in ADR-051.
 *
 * <p><strong>The key is the resolved client address and nothing else.</strong> It comes from
 * {@link ClientAddressResolver}, so a client-supplied {@code X-Forwarded-For} is honoured only when the peer is a
 * configured trusted proxy — and {@code eop.web.trusted-proxies} is empty by default, so by default no header can
 * influence it (ADR-021). {@code server.forward-headers-strategy: none} is a second, independent guard: the
 * container never rewrites {@code getRemoteAddr()} from a header either.
 *
 * <p>Keying on the player token was considered and rejected. A token is client-supplied and unvalidated at this
 * layer, so an attacker could present a fresh bogus token per request; since the counter must fail closed when its
 * key table saturates, that would turn a spoofable header into a way to refuse service to everyone. An address is
 * the coarsest thing a caller cannot choose. The cost is that players sharing one NAT share one allowance, which
 * the limit is sized to absorb.
 *
 * <p><strong>Scope.</strong> Registered on {@code /api/v1/**} so that a read route added later is covered without
 * anyone remembering to add it, and counts nothing but GET and HEAD — writes keep their own dedicated limiters.
 * The SSE stream is excluded at registration; see the configurer for why.
 */
@Component
public class ReadRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(ReadRateLimitInterceptor.class);

    /**
     * The methods this interceptor counts. HEAD is included because it reaches the same handler as GET and so
     * costs the same work; anything else is a write and is left to the limiter that guards it.
     */
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");

    /**
     * The counter's label. It reaches an operator only through the saturation warning, so it is named here
     * rather than inline: a second counter in this class would otherwise be easy to give the same label.
     */
    private static final String COUNTER_NAME = "Read rate limiter";

    private final ClientAddressResolver clientAddressResolver;

    private final SlidingWindowCounter counter;

    /**
     * Creates the interceptor.
     *
     * @param clientAddressResolver resolves the key from the request; must not be null
     * @param clock                 the clock the counter uses; must not be null
     * @param properties            the configured limit and key-table bound; must not be null
     */
    ReadRateLimitInterceptor(
            final ClientAddressResolver clientAddressResolver,
            final Clock clock,
            final ReadRateLimitProperties properties) {
        this.clientAddressResolver = Objects.requireNonNull(clientAddressResolver, "clientAddressResolver is required");
        Objects.requireNonNull(properties, "properties is required");
        this.counter = new SlidingWindowCounter(clock, COUNTER_NAME, properties.limit(), properties.maxTrackedKeys());
        LOG.info(
                "Read rate limiter initialised: {} reads per address per {}s",
                properties.limit(),
                SlidingWindowCounter.WINDOW.toSeconds());
    }

    /**
     * Counts one read against the caller's address, or refuses it.
     *
     * <p>Returns true without counting for any method that is not a read, so that a POST passing through this
     * interceptor's path pattern is unaffected.
     *
     * @param request  the request being dispatched
     * @param response the response, unused — a refusal is signalled by an exception so that the single
     *                 {@code @ControllerAdvice} renders it
     * @param handler  the handler about to run, unused
     * @return always true; a refusal is an exception, never a false. Returning false would abort the dispatch
     *         with an empty body, which is why this method has one exit point and no branch yields false
     * @throws RateLimitedException if the caller has exhausted its allowance for the current window
     */
    @Override
    public boolean preHandle(
            final HttpServletRequest request, final HttpServletResponse response, final Object handler) {
        // One exit point, deliberately. A refusal here is the throw out of admit, never a returned false, so
        // do not replace this `return true` with `return false` on either path: false tells the
        // DispatcherServlet the interceptor has already written the response, so the caller would get an
        // empty 200 instead of the 429 problem+json that GlobalExceptionHandler renders from the throw.
        if (READ_METHODS.contains(request.getMethod())) {
            counter.admit(clientAddressResolver.of(request));
        }
        return true;
    }
}
