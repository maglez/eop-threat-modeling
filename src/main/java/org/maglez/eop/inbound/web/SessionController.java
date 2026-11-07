package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.DisplayName;
import org.maglez.eop.application.port.in.CreateSessionPort;
import org.maglez.eop.application.port.in.GetSessionStatePort;
import org.maglez.eop.application.port.in.JoinSessionPort;
import org.maglez.eop.application.port.out.SessionCreationLimiter;
import org.maglez.eop.application.port.in.StartSessionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.maglez.eop.outbound.web.SseSessionEventPublisher;

/**
 * HTTP access to the session lifecycle: create a lobby, join one, read its state,
 * close it and begin play.
 *
 * <p>The change-notification stream for a session is not here. It lives in
 * {@link SessionStreamController}, moved there by EOP-190 because it was the only one
 * of the five routes holding {@code ResolvePlayerPort} or
 * {@link SseSessionEventPublisher} and the only one holding nothing else, so the cut
 * left no collaborator on both sides. Read that class for why a stream held open for
 * the length of a game does not belong beside four short-lived handlers.
 *
 * <p>This bean only exists when {@code eop.features.session-lifecycle} is true
 * (ADR-013, ADR-019). That is the whole of the feature flag: while the flag is off
 * there are no handlers registered for these paths, so Spring's own no-handler
 * response answers them, and that response is already a problem detail. Nothing
 * branches on the flag at request time, because behaviour that is switched off by
 * the absence of code cannot be switched on by accident. The same condition is
 * repeated on {@link SessionStreamController}, so the flag withholds the whole
 * lifecycle rather than most of it.
 *
 * <p>{@code havingValue = "true"} is not decoration. Without it the condition matches
 * any present value that is not literally {@code false}, so {@code off}, {@code no},
 * {@code 0} and {@code disabled} would all switch these four routes <em>on</em> — and
 * {@code off} is both the spelling an operator reaching for a kill switch is likeliest
 * to use and a boolean false in YAML 1.1. A flag whose off position depends on picking
 * one of several synonyms for off is fail-open, which
 * {@code .opencode/rules/security.md} forbids. ADR-013 therefore requires the explicit
 * form on every flag in this repository (EOP-48).
 *
 * <p>The credential header is declared {@code required = false} on purpose. Letting
 * Spring reject a missing header would produce a 400 describing a missing header,
 * and the absence of a credential is not a malformed request — it is a refused one.
 * Passing the null through to the use case makes a missing credential and an
 * unrecognised one arrive at the same place and leave as the same 403.
 *
 * <p>Descriptions here are deliberately brief: {@code docs/api/openapi.yml} is the
 * contract (ADR-004), springdoc is disabled by default (ADR-049), and duplicating prose in
 * two places is how the two come to disagree.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(prefix = "eop.features", name = "session-lifecycle", havingValue = "true")
@Tag(name = "sessions", description = "Game session lifecycle")
public class SessionController {

    /**
     * The header carrying a player's identity credential.
     *
     * <p>Custom rather than {@code Authorization: Bearer}, because this is not
     * OAuth and naming it so would invite the assumption that it behaves like one
     * (ADR-015). It is never accepted as a query parameter, on any endpoint,
     * including the event stream (ADR-019).
     */
    static final String PLAYER_TOKEN_HEADER = "X-EoP-Player-Token";

    private final CreateSessionPort createSessionPort;

    private final JoinSessionPort joinSessionPort;

    private final GetSessionStatePort getSessionStatePort;

    private final StartSessionPort startSessionPort;

    private final ClientAddressResolver clientAddressResolver;

    private final SessionCreationLimiter sessionCreationLimiter;

    SessionController(
            final CreateSessionPort createSessionPort,
            final JoinSessionPort joinSessionPort,
            final GetSessionStatePort getSessionStatePort,
            final StartSessionPort startSessionPort,
            final ClientAddressResolver clientAddressResolver,
            final SessionCreationLimiter sessionCreationLimiter) {
        this.createSessionPort = Objects.requireNonNull(createSessionPort, "createSessionPort is required");
        this.joinSessionPort = Objects.requireNonNull(joinSessionPort, "joinSessionPort is required");
        this.getSessionStatePort = Objects.requireNonNull(getSessionStatePort, "getSessionStatePort is required");
        this.startSessionPort = Objects.requireNonNull(startSessionPort, "startSessionPort is required");
        this.clientAddressResolver = Objects.requireNonNull(clientAddressResolver, "clientAddressResolver is required");
        this.sessionCreationLimiter = Objects.requireNonNull(sessionCreationLimiter, "sessionCreationLimiter is required");
    }

    /**
     * Opens a lobby and seats its facilitator.
     *
     * <p>Rate-limited by {@code eop.web.session-creation-limit} creations per address
     * per minute (default 5, ADR-033). The slot is reserved atomically before any
     * database work via {@link SessionCreationLimiter#recordCreation}, so a refused
     * request never commits a row. If the use case fails after the slot is reserved,
     * {@link SessionCreationLimiter#refundCreation} returns it so that a transient
     * error does not permanently consume a creation allowance.
     *
     * @param request     the display name to seat the facilitator under
     * @param httpRequest the servlet request, read only for the caller's address
     * @return 201 with the new session, the facilitator's identifier and its credential
     */
    @PostMapping
    @Operation(summary = "Create a session and become its facilitator")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "The session was created and the caller admitted as facilitator."),
        @ApiResponse(responseCode = "400", description = "The display name is missing or fails validation."),
        @ApiResponse(responseCode = "429", description = "The session creation limit for this source address is exhausted."),
        @ApiResponse(responseCode = "503", description = "No unused join code could be minted; retry after the given delay.")
    })
    public ResponseEntity<SessionAdmissionDto> createSession(
            @Valid @RequestBody final CreateSessionRequest request,
            final HttpServletRequest httpRequest) {
        final String clientAddress = clientAddressResolver.of(httpRequest);
        sessionCreationLimiter.checkAllowed(clientAddress);
        sessionCreationLimiter.recordCreation(clientAddress);
        try {
            final var admission = createSessionPort.execute(DisplayName.of(request.displayName()));
            final var location = URI.create("/api/v1/sessions/" + admission.session().sessionId());
            return ResponseEntity.created(location).body(SessionAdmissionDto.from(admission));
        }
        catch (final RuntimeException ex) {
            sessionCreationLimiter.refundCreation(clientAddress);
            throw ex;
        }
    }

    /**
     * Takes the next seat at a table identified by its join code.
     *
     * @param joinCode    the code as typed, normalised and matched further in
     * @param request     the display name to seat the player under
     * @param httpRequest the servlet request, read only for the caller's address
     * @return 200 with the joined session, the player's identifier and its credential
     */
    @PostMapping("/{joinCode}/players")
    @Operation(summary = "Join a session using its code")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The participant was admitted."),
        @ApiResponse(responseCode = "400", description = "The display name is missing or fails validation."),
        @ApiResponse(responseCode = "404", description = "No joinable session matches the code."),
        @ApiResponse(responseCode = "409", description = "The table is full or the lobby has already closed."),
        @ApiResponse(responseCode = "429", description = "The join attempt limit for this source address is exhausted.")
    })
    public SessionAdmissionDto joinSession(
            @PathVariable final String joinCode,
            @Valid @RequestBody final JoinSessionRequest request,
            final HttpServletRequest httpRequest) {
        final var admission = joinSessionPort.execute(
                joinCode, DisplayName.of(request.displayName()), clientAddressResolver.of(httpRequest));
        return SessionAdmissionDto.from(admission);
    }

    /**
     * Reads a session as it is stored.
     *
     * <p>This is the reconnect endpoint and the first-load endpoint at once. It
     * consults the database and nothing else, so a client returning after a refresh,
     * a dropped connection or a deployment follows the same path as one arriving for
     * the first time (ADR-014).
     *
     * @param sessionId   the session to read
     * @param playerToken the caller's credential, absent if it sent none
     * @return 200 with the current state
     */
    @GetMapping("/{sessionId}")
    @Operation(summary = "Read the current state of a session")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The current state of the session."),
        @ApiResponse(responseCode = "400", description = "The identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "The credential is missing, unrecognised, or belongs to another session."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "429", description = "The read rate limit for this source address is exhausted.")
    })
    public SessionStateDto getSessionState(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        return SessionStateDto.from(getSessionStatePort.execute(sessionId, playerToken));
    }

    /**
     * Closes the lobby and begins play.
     *
     * <p>Dealing is not part of this: starting establishes that no more players
     * will arrive, and nothing more.
     *
     * @param sessionId   the session to start
     * @param playerToken the caller's credential, absent if it sent none
     * @return 200 with the started session
     */
    @PostMapping("/{sessionId}/start")
    @Operation(summary = "Start play, closing the lobby")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Play has started."),
        @ApiResponse(responseCode = "400", description = "The identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "The caller is not the facilitator of this session."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "409", description = "The session has already started, or too few players are seated.")
    })
    public SessionStateDto startSession(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        return SessionStateDto.from(startSessionPort.execute(sessionId, playerToken));
    }
}
