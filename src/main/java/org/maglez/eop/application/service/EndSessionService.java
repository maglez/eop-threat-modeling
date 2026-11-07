package org.maglez.eop.application.service;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.GameSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.maglez.eop.application.port.in.EndSessionPort;
import org.maglez.eop.application.port.in.PersistGameResultPort;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.in.ResolveTrickPort;
import org.maglez.eop.application.port.in.SessionEvent;
import org.maglez.eop.application.port.in.SessionEventType;
import org.maglez.eop.application.port.out.SessionEventPublisher;
import org.maglez.eop.application.port.out.SessionRepository;

/**
 * Ends a session early at the facilitator's explicit request.
 *
 * <p>The automatic path — all tricks played — does not go through this use case.
 * It is handled inside {@link ResolveTrickPort} when the last trick resolves
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
public class EndSessionService implements EndSessionPort {

    private static final Logger LOG = LoggerFactory.getLogger(EndSessionService.class);

    private final SessionRepository sessionRepository;
    private final ResolvePlayerPort resolvePlayerPort;
    private final SessionEventPublisher sessionEventPublisher;
    private final Optional<PersistGameResultPort> persistGameResultPort;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository the port used to record the completion
     * @param resolvePlayerPort the use case that turns a token into a seated player
     * @param sessionEventPublisher the port that announces the completion
     * @param persistGameResultPort writes the final standings; the Optional is retained
     *     although its former {@code eop.features.game-over} gate was removed, so it is
     *     always present
     * @param clock the clock used to stamp the transition, injected so tests are
     *     deterministic
     */
    public EndSessionService(
            final SessionRepository sessionRepository,
            final ResolvePlayerPort resolvePlayerPort,
            final SessionEventPublisher sessionEventPublisher,
            final Optional<PersistGameResultPort> persistGameResultPort,
            final Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.resolvePlayerPort = Objects.requireNonNull(resolvePlayerPort, "resolvePlayerPort is required");
        this.sessionEventPublisher = Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.persistGameResultPort =
                Objects.requireNonNull(persistGameResultPort, "persistGameResultPort is required");
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
     * @throws org.maglez.eop.domain.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token names no
     *     player seated in that session
     * @throws org.maglez.eop.domain.NotFacilitatorException if the caller is a participant
     * @throws org.maglez.eop.domain.SessionNotInProgressException if the session is not
     *     currently in progress
     */
    public GameSession execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerPort.execute(sessionId, playerToken);
        final var now = clock.instant();
        final var completed = resolved.session().complete(resolved.player().playerId(), now);

        sessionRepository.recordCompleted(completed.sessionId(), now);
        persistGameResultPort.ifPresent(useCase -> {
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
