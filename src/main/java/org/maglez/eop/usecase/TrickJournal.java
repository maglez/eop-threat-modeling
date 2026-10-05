package org.maglez.eop.usecase;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.maglez.eop.entity.SessionNotInProgressException;
import org.maglez.eop.entity.Trick;
import org.maglez.eop.entity.TrickPlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes tricks, and announces each write.
 *
 * <p>Extracted by EOP-190 from {@code PlayCardUseCase} and {@code ResolveTrickUseCase}, which held
 * the same twenty-four lines twice: record the resolution, announce it, and if that was the last
 * trick complete the session, persist the result best-effort and announce the game over. Two copies
 * of a cascade that ends in a durable write is the shape where the copies drift, and a drift here is
 * not cosmetic — the second half decides whether a finished game is ever scoreable.
 *
 * <p>Every write is paired with its announcement <em>in this class</em>, which is the point of the
 * type. A caller cannot append a play and forget to say so, because appending is what says so. The
 * order is always write-then-announce: an SSE frame cannot be recalled, so nothing is announced
 * until the row it describes is committed (ADR-014, ADR-025).
 *
 * <p>It authorises nobody. Neither this class nor {@link TrickRepository} takes an acting player, so
 * establishing that the caller may play or resolve is the calling use case's obligation and is the
 * first statement of each one's {@code execute} (ADR-024). Never reach this class from an adapter.
 *
 * <p>{@link #currentTrick(UUID)} is here even though it only forwards, because both write paths
 * begin by reading the current trick and that read is half of a compare-and-set: the leader seat it
 * is checked against is passed straight back in as {@code expectedLeaderSeat}, and the database is
 * the authority that decides whether the write lands (ADR-020). Holding the read next to the writes
 * it guards is what makes that pairing visible. This is not a repository replacement, though —
 * {@code GetTrickStateUseCase} is a pure read and goes to {@link TrickRepository} directly.
 *
 * <p>It is deliberately <strong>ungated</strong>. Its two callers are gated on the same
 * {@code eop.features.trick-play} flag today, but gating a collaborator buys nothing that gating
 * its callers has not already bought, and it would turn any future caller on another flag into an
 * unsatisfied dependency somewhere further away from the cause — the reasoning already recorded for
 * {@code DeckShuffler} and {@code HandDealer} (ADR-013).
 *
 * <p>Pure use case collaborator: no Spring, no Jakarta imports.
 */
public class TrickJournal {

    private static final Logger LOG = LoggerFactory.getLogger(TrickJournal.class);

    private final TrickRepository trickRepository;

    private final SessionRepository sessionRepository;

    private final SessionEventPublisher sessionEventPublisher;

    private final Optional<PersistGameResultUseCase> persistGameResultUseCase;

    /**
     * Creates a journal over the trick and session ports.
     *
     * @param trickRepository port the tricks are written through
     * @param sessionRepository port the session is completed through when the last trick resolves
     * @param sessionEventPublisher announces each write once it has landed
     * @param persistGameResultUseCase records the final score. Absent rather than null so that a
     *     game can finish without a leaderboard
     */
    public TrickJournal(
            final TrickRepository trickRepository,
            final SessionRepository sessionRepository,
            final SessionEventPublisher sessionEventPublisher,
            final Optional<PersistGameResultUseCase> persistGameResultUseCase) {
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.sessionEventPublisher = Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.persistGameResultUseCase =
                Objects.requireNonNull(persistGameResultUseCase, "persistGameResultUseCase is required");
    }

    /**
     * Reads the trick currently on the table, resolved or not.
     *
     * @param sessionId session to read
     * @return the most recent trick, or empty when none has been opened
     */
    public Optional<Trick> currentTrick(final UUID sessionId) {
        return trickRepository.findCurrentTrick(sessionId);
    }

    /**
     * Opens a trick.
     *
     * <p>Nothing is announced. Opening a trick is not a fact a client needs told: it happens in the
     * same request as the play that opens it, and that play's own announcement follows immediately.
     *
     * @param sessionId session the trick belongs to
     * @param trick the trick to open
     * @param expectedLeaderSeat seat the caller believes leads, the compare-and-set witness
     * @param occurredAt instant the opening is recorded at
     */
    public void openTrick(
            final UUID sessionId, final Trick trick, final int expectedLeaderSeat, final Instant occurredAt) {
        trickRepository.openTrick(sessionId, trick, expectedLeaderSeat, occurredAt);
    }

    /**
     * Appends a play to an open trick, then announces it.
     *
     * <p>{@link TrickRepository#appendPlay} takes no instant of its own — a play carries the instant
     * it was made — so {@code occurredAt} is here only to stamp the announcement.
     *
     * @param sessionId session the trick belongs to
     * @param trickId trick to append to
     * @param expectedLeaderSeat seat the caller believes leads, the compare-and-set witness
     * @param play the play to append, already accepted by the trick
     * @param occurredAt instant to stamp the announcement with
     */
    public void appendPlay(
            final UUID sessionId,
            final UUID trickId,
            final int expectedLeaderSeat,
            final TrickPlay play,
            final Instant occurredAt) {
        trickRepository.appendPlay(sessionId, trickId, expectedLeaderSeat, play);
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.CARD_PLAYED, sessionId, occurredAt));
    }

    /**
     * Records a resolved trick, announces it, and completes the game if that was the last trick.
     *
     * <p>The whole cascade in order: write the resolution; announce {@code TRICK_RESOLVED}; and when
     * no seat holds a card, complete the session, persist the result, and announce
     * {@code GAME_COMPLETED}.
     *
     * <p>Completing the session tolerates {@link SessionNotInProgressException}. A facilitator ending
     * the session concurrently gets there first, and the trick is already durably committed by then,
     * so treating the refusal as failure would fail a request whose work had all landed.
     *
     * <p>Persisting the result is best-effort and its failure is logged rather than thrown, because a
     * game that was played is over whether or not its score was written down. The consequence is
     * visible: the leaderboard answers 404 until the result is recorded. The log marker is
     * {@code [EOP-64/EOP-65]} because the two call sites this cascade was extracted from carried one
     * marker each, and the union keeps a search for either of them working.
     *
     * @param sessionId session the trick belongs to
     * @param resolved the trick, already resolved, carrying its winner
     * @param expectedLeaderSeat seat the caller believes leads, the compare-and-set witness
     * @param nextLeaderSeat seat that leads the next trick, empty when the hand is over
     * @param occurredAt instant the resolution is recorded and announced at
     */
    public void recordResolution(
            final UUID sessionId,
            final Trick resolved,
            final int expectedLeaderSeat,
            final OptionalInt nextLeaderSeat,
            final Instant occurredAt) {
        trickRepository.recordResolution(sessionId, resolved, expectedLeaderSeat, nextLeaderSeat, occurredAt);
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.TRICK_RESOLVED, sessionId, occurredAt));

        if (nextLeaderSeat.isEmpty()) {
            completeGame(sessionId, occurredAt);
        }
    }

    /**
     * Completes the session, persists the result best-effort, and announces the game over.
     *
     * @param sessionId session to complete
     * @param occurredAt instant the completion is recorded and announced at
     */
    private void completeGame(final UUID sessionId, final Instant occurredAt) {
        try {
            sessionRepository.recordCompleted(sessionId, occurredAt);
        }
        catch (SessionNotInProgressException ignored) {
            // A concurrent facilitator already ended the session. The trick above is durably
            // committed, so the caller's work landed in full; treat the refusal as success.
        }

        persistGameResultUseCase.ifPresent(useCase -> {
            try {
                useCase.execute(sessionId);
            }
            catch (RuntimeException ex) {
                LOG.warn(
                        "[EOP-64/EOP-65] Failed to persist game result for session {}; "
                                + "leaderboard will return 404 until the result is recorded.",
                        sessionId,
                        ex);
            }
        });

        sessionEventPublisher.publish(new SessionEvent(SessionEventType.GAME_COMPLETED, sessionId, occurredAt));
    }
}
