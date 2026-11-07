package org.maglez.eop.application.service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.NoTrickToResolveException;

import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.TrickAlreadyResolvedException;
import org.maglez.eop.domain.TrickNotCompleteException;
import org.maglez.eop.domain.WinningPlayNotInTrickException;

import org.maglez.eop.application.port.in.PlayCardPort;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.in.ResolveTrickPort;
import org.maglez.eop.application.port.in.SessionEvent;
import org.maglez.eop.application.port.out.HandRepository;
import org.maglez.eop.application.port.out.SessionEventPublisher;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotJoinableException;

/**
 * Resolves the current trick: decides which play took it, records the winner and moves the lead to
 * whoever plays first next.
 *
 * <p>Any seated player may ask for this, not only the facilitator. Resolution is a mechanical
 * consequence of a complete trick rather than a decision anybody makes — the winner is already
 * determined by the cards on the table the moment the last one is played — so gating it on the
 * facilitator would stall the table for everyone if that one person's connection drops. Membership
 * is still required, because who leads next is not information a stranger is owed.
 *
 * <p>Authorising the requester is the first statement here, as it is in every use case that touches
 * this data. Neither port takes an acting player, and the refusals underneath answer one of five
 * distinguishable states — the session exists, its status, whether hands are dealt, which seat leads
 * — which is the right answer for a member and an oracle for anyone else (ADR-024). Only this layer
 * can tell the two callers apart.
 *
 * <p>Three states that are not a complete trick are answered by three different types, because a
 * client that wants to say "waiting for the deal", "waiting for the lead" or "waiting for Dana"
 * cannot tell them apart from one shared type: {@link HandNotDealtException},
 * {@link NoTrickToResolveException} and {@link TrickNotCompleteException}. All three are 409 and all
 * three are worth retrying once the table has moved on.
 *
 * <p>The already-resolved check is made here rather than left to the domain. {@link Trick#resolved()}
 * answers a second resolution with an {@link IllegalStateException}, which would reach a caller as a
 * 500 for what is an ordinary duplicate request — two clients watching the same complete trick both
 * asking to resolve it is a race that happens every hand, not a fault. The port's own
 * {@link TrickAlreadyResolvedException} still stands behind this one for the narrower race where the
 * winner is recorded between this read and that write (ADR-020); this check is what keeps the
 * common case out of the logs.
 *
 * <p>The {@link WinningPlayNotInTrickException} guard before the write is unreachable through
 * {@link Trick#resolved()} today, because the trick's own constructor already refuses a winner that
 * is not among its plays. It is written anyway because it is the only check that will ever exist on
 * this path: {@code fk_trick_winner_play} confines the winning play to the {@code trick_play} table
 * and nothing more, so a play from another trick — or another session — satisfies the schema, and the
 * composite key that would confine it cannot be expressed in Liquibase (ADR-023, ADR-024). The day a
 * winner arrives from anywhere other than that constructor, this is what refuses it.
 *
 * <p>When the hand is spent, {@link Trick#nextLeaderSeat} answers empty: no seat holds a card, so
 * nobody leads next, and that absence is passed straight to the port as an empty
 * {@link java.util.OptionalInt}. Slice D sent the winning seat instead, because the port took an
 * {@code int} and had no value meaning "nobody"; it was harmless only in the sense that no seat could
 * act on it, and it left the session row asserting that a seat led when that seat held nothing to
 * lead with. The port now records the absence, which is what makes the end of a hand a fact the
 * database states rather than one every reader has to re-derive.
 *
 * <p>Note which seats the lead is chosen from. {@code hands} is read after the plays in this trick
 * removed their cards, so {@code seatsHoldingCards} is the set as it stands once the trick is over,
 * and the winner is only handed the lead if it appears in that set. A winner that has just played its
 * last card passes the lead clockwise to the next seat that still holds one — handing it to a
 * card-less seat would open a trick nobody could legally play into, and the table would stop with no
 * exception raised and nothing logged (ADR-023).
 *
 * <p>When the last trick resolves — {@link Trick#nextLeaderSeat} answers empty because no seat holds
 * a card — the session is automatically transitioned to
 * {@link org.maglez.eop.domain.SessionStatus#COMPLETED} and a {@code game-completed} event is
 * published. The transition is a compare-and-swap on {@code IN_PROGRESS}: if the facilitator's
 * end-session call wins the race in the window between {@code recordResolution} committing and
 * {@code recordCompleted} being called, the CAS finds zero rows and throws
 * {@code SessionNotInProgressException}. The auto-complete branch catches that exception and treats
 * it as success — the session is already {@code COMPLETED}, which is the desired outcome, and the
 * trick resolution itself was already durably committed. The two writes are in separate transactions
 * (each adapter method carries its own {@code @Transactional}), so the race is real and the
 * tolerance is necessary (EOP-15 Slice C, ADR-032). Since EOP-190 that branch lives in
 * {@link TrickJournal} rather than here, which is why this class no longer names the exception in
 * code and refers to it as {@code SessionNotInProgressException} rather than linking it.
 *
 * <p>The resolved trick is returned because everything in it is public: every card in it was played
 * face up and the winner is what the whole table is waiting to see.
 *
 * <p>{@code trick-resolved} is announced once the resolution is recorded, so a refused resolution
 * announces nothing and the winner is never announced twice by two callers racing to resolve the same
 * trick — the second is refused before it reaches the write. The announcement carries no part of the
 * outcome: {@link SessionEvent} names a type, a session and an instant, and every recipient re-reads
 * the state of play for itself, which is also how it learns which seat leads next (ADR-014). It is
 * published rather than returned to the resolving caller alone because the seat that leads next is
 * usually somebody else's news. Publishing is not guarded here because it must not fail a request, an
 * obligation {@link SessionEventPublisher} places on its implementation.
 *
 * <p>The recording, the announcement and the completion cascade described above are all carried out
 * by {@link TrickJournal}. What stays here is the part that is this route's own: deciding whether
 * there is a complete, unresolved trick to resolve at all, and refusing with the three distinct
 * exceptions above when there is not. {@link PlayCardPort} resolves inline through the same
 * journal, so the two routes write and announce identically by construction rather than by two
 * copies of the same twenty-four lines being kept in step by hand (EOP-190).
 */
public class ResolveTrickService implements ResolveTrickPort {

    private final ResolvePlayerPort resolvePlayerPort;

    private final HandRepository handRepository;

    private final Clock clock;

    private final TrickJournal trickJournal;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerPort resolves the acting player from the identity token
     * @param handRepository reads the hands, which say which seats still hold cards
     * @param clock supplies the resolution timestamp
     * @param trickJournal reads the current trick, records its resolution, announces it, and
     *     completes the game when that was the last trick. EOP-190 moved that cascade there because
     *     {@code PlayCardPort} resolves inline and held a second copy of it, which is why no
     *     {@code TrickRepository}, {@code SessionRepository}, {@code SessionEventPublisher} or
     *     {@code PersistGameResultPort} is held here any more
     */
    public ResolveTrickService(
            final ResolvePlayerPort resolvePlayerPort,
            final HandRepository handRepository,
            final Clock clock,
            final TrickJournal trickJournal) {
        this.resolvePlayerPort = Objects.requireNonNull(resolvePlayerPort, "resolvePlayerPort is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.trickJournal = Objects.requireNonNull(trickJournal, "trickJournal is required");
    }

    /**
     * Resolves the session's current trick.
     *
     * <p>When this resolution empties the last hand, the game is completed too. That completion is
     * a compare-and-set against a session a concurrent facilitator call may already have completed,
     * in which case {@code SessionNotInProgressException} is raised and swallowed as success. Since
     * EOP-190 both the completion and that catch live in {@link TrickJournal} rather than here, so
     * this method neither throws nor catches it and it is deliberately absent from the list below.
     *
     * @param sessionId the session whose trick is to be resolved
     * @param playerToken the requester's identity token, which may be null or unrecognised
     * @return the trick with its winner decided
     * @throws NullPointerException if sessionId is null
     * @throws SessionNotFoundException if no session has that identifier
     * @throws PlayerNotRecognisedException if the token names nobody at this table
     * @throws HandNotDealtException if the hands have not been dealt
     * @throws NoTrickToResolveException if no trick has been opened yet
     * @throws TrickNotCompleteException if a seat holding cards has not played into the trick
     * @throws TrickAlreadyResolvedException if the trick already has a winner
     * @throws WinningPlayNotInTrickException if the winning play was not made into this trick
     * @throws SessionNotJoinableException if the session is no longer playable
     */
    public Trick execute(final UUID sessionId, final String playerToken) {
        resolvePlayerPort.execute(sessionId, playerToken);

        final var hands = handRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new HandNotDealtException(sessionId));
        final var trick = trickJournal.currentTrick(sessionId)
                .orElseThrow(() -> new NoTrickToResolveException(sessionId));

        if (trick.winner().isPresent()) {
            throw new TrickAlreadyResolvedException(trick.trickId());
        }

        final var seatsHoldingCards = hands.seatsHoldingCards();
        if (!trick.isComplete(seatsHoldingCards)) {
            throw new TrickNotCompleteException(trick.trickId(),
                    trick.seatToPlay(seatsHoldingCards).orElse(trick.leaderSeat()));
        }

        final var resolved = trick.resolved();
        final var winner = resolved.winner()
                .orElseThrow(() -> new IllegalStateException("Trick " + trick.trickId() + " resolved to no winner"));
        final var winnerWasPlayedHere = resolved.plays().stream()
                .anyMatch(play -> play.trickPlayId().equals(winner.trickPlayId()));
        if (!winnerWasPlayedHere) {
            throw new WinningPlayNotInTrickException(trick.trickId(), winner.trickPlayId());
        }

        final var nextLeaderSeat = resolved.nextLeaderSeat(seatsHoldingCards);
        final var now = clock.instant();
        trickJournal.recordResolution(sessionId, resolved, trick.leaderSeat(), nextLeaderSeat, now);

        return resolved;
    }
}
