package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.entity.GameSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ends a session early at the facilitator's explicit request.
 *
 * <p>The automatic path — all tricks played — does not go through this use case.
 * It is handled inside {@link ResolveTrickUseCase} when the last trick resolves
 * and {@code nextLeaderSeat} is empty. This use case is for the facilitator's
 * deliberate decision to stop play before every card has been played.
 *
 * <p>Only the facilitator may end a session. Any seated player may resolve a
 * trick (because resolution is mechanical), but ending early is a decision that
 * belongs to the person who opened the lobby.
 *
 * <p>The transition is a compare-and-swap on {@code IN_PROGRESS}: a concurrent
 * call from the automatic path is safe because exactly one will update the row
 * and the other will find zero rows changed (EOP-15 Slice C, ADR-032).
 *
 * <p>Completing the row is not the whole of it. The game-over screen reads the
 * leaderboard, and the leaderboard refuses a completed session that has no recorded
 * result (ADR-030). The automatic path persists the result inside
 * {@link TrickJournal}; this use case has to do the same, or a game the facilitator
 * ends early answers the leaderboard with a 404 and the screen shows an error
 * instead of the standings.
 */
public class EndSessionUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(EndSessionUseCase.class);

    private final SessionRepository sessionRepository;
    private final ResolvePlayerUseCase resolvePlayerUseCase;
    private final SessionEventPublisher sessionEventPublisher;
    private final Optional<PersistGameResultUseCase> persistGameResultUseCase;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository the port used to record the completion
     * @param resolvePlayerUseCase the use case that turns a token into a seated player
     * @param sessionEventPublisher the port that announces the completion
     * @param persistGameResultUseCase writes the final standings; the Optional is retained
     *     although its former {@code eop.features.game-over} gate was removed, so it is
     *     always present
     * @param clock the clock used to stamp the transition, injected so tests are
     *     deterministic
     */
    public EndSessionUseCase(
            final SessionRepository sessionRepository,
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final SessionEventPublisher sessionEventPublisher,
            final Optional<PersistGameResultUseCase> persistGameResultUseCase,
            final Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.resolvePlayerUseCase = Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.sessionEventPublisher = Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.persistGameResultUseCase =
                Objects.requireNonNull(persistGameResultUseCase, "persistGameResultUseCase is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Moves a session from in-progress to completed at the facilitator's request.
     *
     * <p>Every rule applied here — that the caller is seated, that the caller is the
     * facilitator, and that the session is currently in progress — is decided by
     * {@link GameSession}. This method only supplies the clock and persists the outcome.
     *
     * @param sessionId the session being ended
     * @param playerToken the caller's identity token, as received in the request header
     * @return the session as it stands once it is completed
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.entity.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.entity.PlayerNotRecognisedException if the token names no
     *     player seated in that session
     * @throws org.maglez.eop.entity.NotFacilitatorException if the caller is a participant
     * @throws org.maglez.eop.entity.SessionNotInProgressException if the session is not
     *     currently in progress
     */
    public GameSession execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerUseCase.execute(sessionId, playerToken);
        final var now = clock.instant();
        final var completed = resolved.session().complete(resolved.player().playerId(), now);

        sessionRepository.recordCompleted(completed.sessionId(), now);
        persistGameResultUseCase.ifPresent(useCase -> {
            try {
                useCase.execute(completed.sessionId());
            }
            catch (RuntimeException ex) {
                LOG.warn(
                        "[EOP-256] Failed to persist game result for session {}; "
                                + "leaderboard will return 404 until the result is recorded.",
                        completed.sessionId(),
                        ex);
            }
        });
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.GAME_COMPLETED, completed.sessionId(), now));
        return completed;
    }
}
