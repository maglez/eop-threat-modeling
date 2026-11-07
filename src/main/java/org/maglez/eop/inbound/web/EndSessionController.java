package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.application.port.in.EndSessionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP access to the facilitator's early-end operation.
 *
 * <p>This bean only exists when {@code eop.features.trick-play} is true (ADR-013).
 * The end-session route is part of the trick-play feature because it is only
 * meaningful once a hand is in progress: ending a session that has never been
 * dealt is a state error, not a lifecycle operation. While the flag is off there
 * are no handlers for this path and Spring's own no-handler response answers it.
 *
 * <p><strong>The acting player is never read from a request.</strong> The caller's
 * credential is taken from the header and passed to the use case, which resolves
 * it to exactly one seated player and verifies they are the facilitator (ADR-015).
 *
 * <p>The credential header is declared {@code required = false} for the same reason
 * as in {@link SessionController}: a missing credential is a refused request, not a
 * malformed one, so the null travels to the use case and a missing and an
 * unrecognised token leave as the same 403.
 *
 * <p>{@link SessionController#PLAYER_TOKEN_HEADER} is referenced rather than copied,
 * and deliberately not lifted into a shared constants class. Such a class would hold
 * one compile-time-constant {@code String} and a private constructor; since the
 * constant is inlined by the compiler and the constructor is never called, JaCoCo
 * would find no covered instruction in it, and the coverage gate admits no
 * per-class exclusions.
 *
 * <p>Nothing here logs. That is not an omission: ADR-026 is {@code Proposed} and has
 * not yet decided where use-case observability lives. Adding a logger to this one
 * controller would pick that option in a feature slice, and would break the
 * uniformity that ADR-026 records as the reason the gap is tolerable at all.
 *
 * <p>Descriptions here are deliberately brief: {@code docs/api/openapi.yml} is the
 * contract (ADR-004), springdoc is disabled by default (ADR-049), and prose duplicated in
 * two places is how the two come to disagree.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(prefix = "eop.features", name = "trick-play", havingValue = "true")
@Tag(name = "trick-play", description = "Dealing, playing and resolving tricks, and reading the score")
public class EndSessionController {

    private final EndSessionPort endSessionPort;

    EndSessionController(final EndSessionPort endSessionPort) {
        this.endSessionPort = Objects.requireNonNull(endSessionPort, "endSessionPort is required");
    }

    /**
     * Ends the session early, before all cards have been played.
     *
     * <p>Facilitator only. Moves the session from {@code IN_PROGRESS} to
     * {@code COMPLETED} and publishes a {@code game-completed} event to all
     * connected clients. The score derived from the plays made so far is still
     * readable from {@code GET /api/v1/sessions/{sessionId}/score}.
     *
     * @param sessionId   the session to end
     * @param playerToken the caller's credential, absent if it sent none
     * @return 204 No Content on success
     */
    @PostMapping("/{sessionId}/end")
    @Operation(summary = "End the session early")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "The session is now COMPLETED."),
        @ApiResponse(responseCode = "400", description = "The session identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "No credential, an unrecognised one, or a player who is not the facilitator."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "409", description = "The session is not IN_PROGRESS.")
    })
    public ResponseEntity<Void> endSession(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        endSessionPort.execute(sessionId, playerToken);
        return ResponseEntity.noContent().build();
    }
}
