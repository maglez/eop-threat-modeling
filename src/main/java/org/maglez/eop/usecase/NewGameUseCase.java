package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.GameNotCompletedException;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.NoTamperingCardDealtException;
import org.maglez.eop.entity.NotFacilitatorException;
import org.maglez.eop.entity.SessionStatus;
import org.maglez.eop.entity.TooFewPlayersException;

/**
 * Resets a completed session and re-deals the same deck to the same seats.
 *
 * <p>This is the "Start new game" action available to the facilitator once the leaderboard
 * is shown. It clears all hands and tricks, moves the session back to {@code IN_PROGRESS},
 * and deals a freshly shuffled deck to the same players in the same seats.
 *
 * <p>The sequence is:
 * <ol>
 *   <li>Authorise: caller must be the facilitator of a completed session.</li>
 *   <li>Clear tricks (plays first, then tricks — FK order).</li>
 *   <li>Clear hands (hand-cards first, then hands — FK order).</li>
 *   <li>Reset session status to {@code IN_PROGRESS} and clear the leader seat.</li>
 *   <li>Deal a freshly shuffled deck and record the opening lead, through
 *       {@link HandDealer}.</li>
 * </ol>
 *
 * <p>Steps 2–4 are separate port calls rather than one atomic operation. A partial failure
 * leaves the session in an inconsistent state, but the facilitator can retry: the reset
 * is idempotent once the session is back in {@code IN_PROGRESS} and the deal is the
 * compare-and-set that prevents a double-deal.
 *
 * <p>Step 5 is not this class's own work. {@link DealHandsUseCase} needs the identical act for a
 * session that has just started, and until EOP-190 both classes carried a verbatim copy of it; it now
 * lives once, in {@link HandDealer}. Two consequences are worth stating here rather than leaving to be
 * rediscovered. The session object handed to the dealer is the one resolved in step 1, so its status
 * still reads {@code COMPLETED} even though step 4 has since reset it &mdash; harmless because the
 * dealer reads only the identifier and the seats, and because whether the session was playable at the
 * moment of the write is decided by {@link HandRepository#recordDeal} against the database rather than
 * by any status held up here (ADR-020). And the seat count is checked twice: once in step 1, before
 * anything has been destroyed, and again inside the dealer where {@link org.maglez.eop.entity.Hands}
 * is actually called. The early check is the one that matters, because a session refused after its
 * tricks and hands were cleared would have paid for the refusal with the game.
 *
 * <p>{@link HandDealer} is not gated on a feature flag, which is what allows this class to reuse it.
 * This use case and {@link DealHandsUseCase} are gated on different flags, so depending on the other
 * use case directly would make one flag silently require the other (ADR-013).
 *
 * <p>Pure use case: no Spring, no Jakarta imports.
 */
public class NewGameUseCase {

    private final ResolvePlayerUseCase resolvePlayerUseCase;
    private final HandRepository handRepository;
    private final TrickRepository trickRepository;
    private final SessionRepository sessionRepository;
    private final HandDealer handDealer;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerUseCase resolves the identity token into a seated player
     * @param handRepository       clears the hands of the finished game
     * @param trickRepository      clears the tricks of the finished game
     * @param sessionRepository    resets session status
     * @param handDealer           deals the new game and records its opening lead
     * @param clock                supplies timestamps
     */
    public NewGameUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository,
            final SessionRepository sessionRepository,
            final HandDealer handDealer,
            final Clock clock) {
        this.resolvePlayerUseCase =
                Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
        this.sessionRepository =
                Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.handDealer = Objects.requireNonNull(handDealer, "handDealer is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Resets the session and deals a new game.
     *
     * @param sessionId   the completed session to reset
     * @param playerToken the identity token of the player requesting the new game
     * @throws NullPointerException                              if sessionId is null
     * @throws org.maglez.eop.entity.SessionNotFoundException   if no such session exists
     * @throws org.maglez.eop.entity.PlayerNotRecognisedException if the token names nobody at this table
     * @throws NotFacilitatorException                           if the caller is not the facilitator
     * @throws org.maglez.eop.entity.GameNotCompletedException  if the session is not completed
     * @throws TooFewPlayersException                            if fewer than the minimum players are seated
     * @throws NoTamperingCardDealtException                     if the shuffled deck contained no Tampering card
     */
    public void execute(final UUID sessionId, final String playerToken) {
        Objects.requireNonNull(sessionId, "sessionId is required");

        final var resolved = resolvePlayerUseCase.execute(sessionId, playerToken);
        final var session = resolved.session();
        final var actingPlayer = resolved.player();

        if (!actingPlayer.canStartPlay()) {
            throw new NotFacilitatorException(sessionId, actingPlayer.playerId());
        }

        if (session.status() != SessionStatus.COMPLETED) {
            throw new GameNotCompletedException(sessionId);
        }

        final var seated = session.players().size();
        if (seated < GameSession.MINIMUM_PLAYERS_TO_START) {
            throw new TooFewPlayersException(sessionId, seated, GameSession.MINIMUM_PLAYERS_TO_START);
        }

        final var now = clock.instant();

        // Clear game state in FK-safe order: plays → tricks → hand-cards → hands
        trickRepository.clearTricksForNewGame(sessionId);
        handRepository.clearHandsForNewGame(sessionId);

        // Reset session to IN_PROGRESS with no leader seat
        sessionRepository.resetToInProgress(sessionId, now);

        // Re-deal a freshly shuffled deck to the same seats
        handDealer.deal(session);
    }
}
