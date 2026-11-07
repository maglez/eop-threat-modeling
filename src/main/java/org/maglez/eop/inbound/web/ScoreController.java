package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.application.port.in.GetScorePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the score of a session.
 *
 * <p>Its own controller rather than a sixth handler on the trick routes. Those are about dealing,
 * playing and resolving — things that change the table — and this only reads what they have already
 * done. It carries the same feature flag, because a score of a game nobody can play is nothing worth
 * publishing.
 *
 * <p>One route, and the plural in its response is deliberate and worth saying out loud, because the
 * hand route is forbidden to have one. {@code /hand} is singular because a collection of every hand
 * must never exist (ADR-027), and a score sheet is by construction every player's rows. What makes
 * that legitimate here is the content, not the shape: a score names only cards that have already been
 * played face up, so it discloses nothing the players at the table cannot already see, whereas a hand
 * names cards its holder alone is entitled to see. The prohibition is on exposing every player's
 * private state through one route, and there is no private state in a score.
 *
 * <p>The caller is identified by the credential header and nothing else. No path variable, query
 * parameter or body field names a player, so there is no way to ask for somebody else's view (ADR-015).
 *
 * <p>Nothing here logs, for the same reason nothing in the other controllers does: ADR-026 is still
 * proposed, and one of the arrangements it weighs is logging at the web boundary. Adding a logger to
 * one controller would settle that in a feature slice.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(prefix = "eop.features", name = "trick-play", havingValue = "true")
@Tag(name = "trick-play", description = "Dealing, playing and resolving tricks, and reading the score")
public class ScoreController {

    private final GetScorePort getScorePort;

    /**
     * Creates the controller.
     *
     * @param getScorePort the use case that derives the score
     */
    ScoreController(final GetScorePort getScorePort) {
        this.getScorePort = Objects.requireNonNull(getScorePort, "getScorePort is required");
    }

    /**
     * Reads the score of the session.
     *
     * <p>Any seated player may read it. There is no {@code 409} on this route and the absence is
     * deliberate: a score is reportable in every state a session can reach, and before the deal it is
     * everybody on nothing — a true answer rather than a missing one.
     *
     * @param sessionId   identifier of the session
     * @param playerToken the caller's identity token
     * @return the score of the session as it stands
     */
    @GetMapping("/{sessionId}/score")
    @Operation(summary = "Read the score")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The score of the session."),
        @ApiResponse(responseCode = "400", description = "The session identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "No credential, or one that does not belong to this session."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "429", description = "The read rate limit for this source address is exhausted."),
        @ApiResponse(responseCode = "500",
                description = "The stored game contradicts itself, so no score can be derived from it. The body names nothing.")
    })
    public ScoreSheetDto getScore(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        return ScoreSheetDto.from(getScorePort.execute(sessionId, playerToken));
    }
}
