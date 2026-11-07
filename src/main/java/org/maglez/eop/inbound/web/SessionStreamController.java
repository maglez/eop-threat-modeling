package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.maglez.eop.outbound.web.SseSessionEventPublisher;

/**
 * The change-notification stream for one session.
 *
 * <p>Split out of {@link SessionController} by EOP-190, which found that controller
 * holding eight collaborators. The seam was chosen rather than settled by arithmetic:
 * of the five session routes, this is the only one that holds
 * {@link ResolvePlayerPort} or {@link SseSessionEventPublisher}, and it is the only
 * one that holds nothing else. So the cut leaves no collaborator on both sides — the
 * alternative candidate, lifting session creation out, would have had to keep
 * {@link ClientAddressResolver} in both classes, because joining reads the caller's
 * address too.
 *
 * <p>It is also the one route that is a different protocol. Everything else here is a
 * request and a response; this is a connection held open for the length of a game,
 * answering with {@code text/event-stream} and failing in ways the others cannot — a
 * subscriber limit, a client that goes away without saying so. Keeping that in its own
 * class is what stops those concerns leaking into the four short-lived handlers.
 *
 * <p>This bean only exists when {@code eop.features.session-lifecycle} is true, on the
 * same condition and for the same reasons as {@link SessionController} (ADR-013,
 * ADR-019). The condition is repeated here rather than inherited, because a flag that
 * governs a feature must be repeated on every bean that opens the flagged state — the
 * flag would otherwise leave this route serving while the rest of the lifecycle was
 * withheld, which is the fail-open shape {@code .opencode/rules/feature-flags.md}
 * forbids. {@code SessionControllerDisabledIntegrationTest} asserts this bean's
 * absence, not only that the route answers 404.
 *
 * <p>The credential arrives in {@link SessionController#PLAYER_TOKEN_HEADER}, the same
 * header as everywhere else. The constant stays where it is because it is not this
 * route's property — it is the whole API's — and {@code PlayerTokenHeaderContractTest}
 * pins it against {@code docs/api/openapi.yml} from there.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(prefix = "eop.features", name = "session-lifecycle", havingValue = "true")
@Tag(name = "sessions", description = "Game session lifecycle")
public class SessionStreamController {

    private final ResolvePlayerPort resolvePlayerPort;

    private final SseSessionEventPublisher sessionEventPublisher;

    SessionStreamController(
            final ResolvePlayerPort resolvePlayerPort,
            final SseSessionEventPublisher sessionEventPublisher) {
        this.resolvePlayerPort = Objects.requireNonNull(resolvePlayerPort, "resolvePlayerPort is required");
        this.sessionEventPublisher = Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
    }

    /**
     * Opens a stream of change notifications for one session.
     *
     * <p>The credential is resolved before the stream is opened, so an unrecognised
     * caller receives a problem detail rather than an empty stream that would look
     * like a session where nothing ever happens.
     *
     * <p>The credential arrives in the same header as everywhere else, and there is
     * no query-parameter alternative — not because one was rejected during review,
     * but because no code path here reads one. The browser's {@code EventSource} API
     * cannot set request headers, so the client uses {@code fetch}-based streaming
     * instead; that cost is accepted because a credential in a query string is a
     * credential in the access log, in the browser history, and on screen during a
     * shared call (ADR-019).
     *
     * <p>What arrives on this stream is notification, never state. Each frame says
     * that the session changed; the client then re-reads
     * {@code GET /api/v1/sessions/{sessionId}}. There is no history, so
     * {@code Last-Event-ID} is not honoured.
     *
     * @param sessionId   the session to watch
     * @param playerToken the caller's identity credential
     * @return an open server-sent event stream
     */
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream notifications that the session changed")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The stream is open and will emit a frame on every change."),
        @ApiResponse(responseCode = "400", description = "The identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "The credential is missing, unrecognised, or belongs to another session."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "429", description = "This session already has the maximum number of subscribers.")
    })
    public SseEmitter streamSessionEvents(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        resolvePlayerPort.execute(sessionId, playerToken);
        return sessionEventPublisher.subscribe(sessionId);
    }
}
