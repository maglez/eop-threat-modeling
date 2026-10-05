package org.maglez.eop.adapter.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.usecase.GetLeaderboardUseCase;
import org.maglez.eop.usecase.NewGameUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the game-over leaderboard and the "Start new game" action.
 *
 * <p>Both routes are gated on the {@code eop.features.game-over} flag. While the flag is
 * {@code false} neither bean nor routes exist, so the paths return the framework's own 404.
 *
 * <p>The leaderboard is read-only and available to any seated player once the session is
 * {@code COMPLETED}. The new-game action is facilitator-only and resets the session to
 * {@code IN_PROGRESS} with a freshly shuffled deck.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@Tag(name = "game-over", description = "Final leaderboard and new-game reset")
public class GameOverController {

    private final GetLeaderboardUseCase getLeaderboardUseCase;

    private final NewGameUseCase newGameUseCase;

    /**
     * Creates the controller.
     *
     * @param getLeaderboardUseCase reads the persisted game result and score sheet
     * @param newGameUseCase        resets the session and re-deals
     */
    GameOverController(
            final GetLeaderboardUseCase getLeaderboardUseCase,
            final NewGameUseCase newGameUseCase) {
        this.getLeaderboardUseCase =
                Objects.requireNonNull(getLeaderboardUseCase, "getLeaderboardUseCase is required");
        this.newGameUseCase = Objects.requireNonNull(newGameUseCase, "newGameUseCase is required");
    }

    /**
     * Returns the final leaderboard for a completed session.
     *
     * <p>Any seated player may read it. The leaderboard is only available once the session
     * is {@code COMPLETED}; requesting it earlier returns {@code 409}.
     *
     * <p>Two distinct conditions return {@code 404}, and the problem detail tells them apart: no
     * session has that identifier, or the session is completed but no result was ever recorded for
     * it — a facilitator who ended play early, or a best-effort result write that failed. Only a
     * seated player can see the second, so the two share a status without disclosing anything.
     *
     * @param sessionId   identifier of the session
     * @param playerToken the caller's identity token
     * @return the leaderboard with per-player STRIDE breakdown
     */
    @GetMapping("/{sessionId}/leaderboard")
    @Operation(summary = "Read the final leaderboard")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The leaderboard for the completed session."),
        @ApiResponse(responseCode = "400", description = "The session identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "No credential, or one that does not belong to this session."),
        @ApiResponse(responseCode = "404",
                description = "No session exists with that identifier, or the session is completed with no recorded result."),
        @ApiResponse(responseCode = "409", description = "The session is not yet completed."),
        @ApiResponse(responseCode = "429", description = "The read rate limit for this source address is exhausted.")
    })
    public LeaderboardDto getLeaderboard(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false)
                    final String playerToken) {
        final var leaderboard = getLeaderboardUseCase.execute(sessionId, playerToken);
        return LeaderboardDto.from(leaderboard.scoreSheet(), leaderboard.sessionStatus().name());
    }

    /**
     * Resets a completed session and deals a new game.
     *
     * <p>Only the facilitator may call this. The session must be {@code COMPLETED}; calling
     * it while the session is still in progress returns {@code 409}.
     *
     * @param sessionId   identifier of the session to reset
     * @param playerToken the caller's identity token
     */
    @PostMapping("/{sessionId}/new-game")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Start a new game with the same players")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "The session has been reset and a new game has been dealt."),
        @ApiResponse(responseCode = "400", description = "The session identifier is not a UUID."),
        @ApiResponse(responseCode = "403",
                description = "No credential, the caller is not the facilitator, or the token does not belong to this session."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "409", description = "The session is not yet completed.")
    })
    public void newGame(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false)
                    final String playerToken) {
        newGameUseCase.execute(sessionId, playerToken);
    }
}
