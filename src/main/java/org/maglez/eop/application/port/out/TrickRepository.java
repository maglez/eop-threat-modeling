package org.maglez.eop.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.maglez.eop.domain.Trick;

import org.maglez.eop.domain.TrickPlay;
import org.maglez.eop.application.port.in.NewGamePort;
import org.maglez.eop.domain.AlreadyPlayedInTrickException;
import org.maglez.eop.domain.CardAlreadyPlayedException;
import org.maglez.eop.domain.CardNotInHandException;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.NotYourSeatException;
import org.maglez.eop.domain.OutOfTurnException;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.TrickAlreadyOpenException;
import org.maglez.eop.domain.TrickAlreadyResolvedException;

/**
 * Port through which the application reads and writes tricks and the plays made into
 * them.
 *
 * <p>As with {@link HandRepository}, a session identifier leads every signature because
 * {@link Trick} does not carry one — it holds an identifier, a sequence, a leader seat,
 * its plays and its winner, and nothing else.
 *
 * <p>Every write takes an {@code expectedLeaderSeat}. That is the compare-and-set
 * witness, and it is the whole turn-order guard: the statement names the leader seat the
 * caller's snapshot showed, and a snapshot taken before somebody else moved matches
 * nothing. The value is always derived — from the trick the caller read, or from
 * {@link HandRepository#findCurrentLeaderSeat} — and is never taken from a request body.
 * ADR-020 is explicit that serialisation rests on the row lock the conditional statement
 * takes rather than on the value it returns, and that {@code @Version} is mapped but is
 * deliberately not the enforcement mechanism.
 *
 * <p>The recorded leader seat advances <em>once per trick</em>, not once per play. A play
 * reads it and locks the row without changing it; a resolution moves it on. Whose turn it
 * is within a trick is derived by {@code Trick} from the plays already made, because the
 * trick's own plays already determine it and a stored per-play pointer would be a second
 * authority on a fact that is already written down.
 *
 * <p>Appending a play is one method rather than two on purpose. It removes the card from
 * a hand and records the play, and those must happen together: two transactions would
 * admit a card gone from a hand with no play recorded, or a play recorded for a card the
 * player still holds.
 *
 * <p>An earlier version of this paragraph justified that by claiming a single transaction
 * spanning two port calls would put {@code org.springframework.transaction} into this
 * package. That was false and is retracted. A caller in the outer ring can compose two
 * calls into one transaction without this package importing anything: both adapter methods
 * are already transactional, and Spring's default {@code REQUIRED} propagation makes them
 * join an enclosing transaction rather than start their own. Atomicity is reachable from
 * outside; the claim that it was not merely saved the argument from being made.
 *
 * <p>The reason that survives is about ownership. The transaction is a detail of the
 * persistence mechanism, so the ring that owns the mechanism should own its atomic unit.
 * Splitting this into two methods would move an invariant that is this port's
 * responsibility out to every caller, and a second place to state an invariant is a second
 * place for it to drift — a cost, deliberately paid here rather than passed upwards, not an
 * impossibility.
 *
 * <p><strong>Authorising the requester is the caller's obligation, not this port's.</strong>
 * Nothing here takes an acting player, so no implementation can check one. {@code
 * appendPlay} is the single exception and only partly: the play it is handed names a
 * player and a seat, and the implementation asserts those agree with the session's
 * seating — a check on the play, not on the requester. {@code openTrick} and {@code
 * recordResolution} carry no player at all, so for them there is no check to make here
 * and the use case is the only place one can happen. {@code expectedLeaderSeat} is a
 * turn-order witness and must never be read from a request body; it is not a substitute
 * for membership, since a stranger who guesses it correctly still passes it.
 */
public interface TrickRepository {

    /**
     * Reads the trick most recently opened in a session.
     *
     * <p>The result may be resolved or unresolved; the caller asks the trick itself.
     * Empty means no trick has been opened, which is the state of a session between the
     * deal and the first lead.
     *
     * @param sessionId the session whose current trick to read
     * @return the highest-numbered trick of that session, or empty if it has none
     */
    Optional<Trick> findCurrentTrick(UUID sessionId);

    /**
     * Opens a new trick.
     *
     * @param sessionId the session the trick belongs to
     * @param trick a freshly opened trick, holding no plays
     * @param expectedLeaderSeat the leader seat the caller's snapshot showed
     * @param occurredAt the instant the trick was opened, recorded against the session so that
     *     expiry sweeping sees the session as active
     * @throws SessionNotFoundException if the session no longer exists
     * @throws SessionNotJoinableException if the session is not in play
     * @throws HandNotDealtException if no deal has established a leader seat yet, so there is
     *     no turn order for a trick to be opened against
     * @throws OutOfTurnException if the leader seat has moved on since the caller's snapshot
     * @throws TrickAlreadyOpenException if a trick with that sequence is already open in
     *     the session, which is how two requests opening the same trick are resolved
     */
    void openTrick(UUID sessionId, Trick trick, int expectedLeaderSeat, Instant occurredAt);

    /**
     * Records one play into an open trick and takes the card out of the player's hand.
     *
     * <p>There is no {@code occurredAt} parameter. A {@link TrickPlay} carries its own
     * {@code playedAt}, which is the value the row stores, and a second timestamp
     * argument would be a second authority on one fact.
     *
     * @param sessionId the session the trick belongs to
     * @param trickId the trick to play into
     * @param expectedLeaderSeat the leader seat the caller's snapshot showed
     * @param play the play to record, carrying the seat, the card and the instant
     * @throws SessionNotFoundException if the session no longer exists
     * @throws SessionNotJoinableException if the session is not in play
     * @throws HandNotDealtException if no deal has established a leader seat yet
     * @throws OutOfTurnException if the leader seat has moved on since the caller's snapshot
     * @throws PlayerNotInSessionException if the play names a player who does not sit in
     *     that session
     * @throws NotYourSeatException if the play names a seat its player does not occupy
     * @throws CardNotInHandException if the player's hand does not hold the card, which
     *     includes the case where another request played it first
     * @throws AlreadyPlayedInTrickException if that seat has already played into the
     *     trick
     * @throws CardAlreadyPlayedException if that card has already been played into the
     *     trick
     */
    void appendPlay(UUID sessionId, UUID trickId, int expectedLeaderSeat, TrickPlay play);

    /**
     * Records which play took a trick and moves the leader on to the next one.
     *
     * <p>The next leader is a parameter rather than something derived here, because
     * deriving it needs the seats that still hold cards — a session-wide fact this port
     * has no business computing, and one the domain already answers.
     *
     * <p>It is an {@link OptionalInt} because the last trick of a hand has no next leader.
     * Every seat is out of cards by then, so there is no seat that could lead and no seat
     * that could be named without saying something false. EOP-14 Slice D sent the winning
     * seat in that case, which was harmless only because nobody could play; the type now
     * carries the distinction rather than relying on that. An empty value means no seat
     * leads, and the implementation is expected to record the absence rather than a
     * substitute.
     *
     * @param sessionId the session the trick belongs to
     * @param resolved the trick, carrying its winning play
     * @param expectedLeaderSeat the leader seat the caller's snapshot showed
     * @param nextLeaderSeat the seat that leads the next trick, or empty when the hand is
     *     played out and no seat leads
     * @param occurredAt the instant recorded as the session's last update
     * @throws SessionNotFoundException if the session no longer exists
     * @throws SessionNotJoinableException if the session is no longer in play
     * @throws HandNotDealtException if no deal has established a leader seat yet
     * @throws OutOfTurnException if the leader seat has already moved on — which is what a
     *     second resolution looks like whenever the lead changed hands
     * @throws TrickAlreadyResolvedException if the trick already carries a winner. This is the
     *     other shape a second resolution takes, and the two do not subsume each other: when
     *     the seat that led the trick also won it the leader seat does not move, so the session
     *     compare-and-set is idempotent and lets the replay through, and the trick row's own
     *     {@code winner_play_id IS NULL} predicate is what refuses it. See ADR-020
     */
    void recordResolution(
            UUID sessionId, Trick resolved, int expectedLeaderSeat, OptionalInt nextLeaderSeat, Instant occurredAt);

    /**
     * Reads every trick of a session, in the order they were played.
     *
     * <p>The whole history rather than the current trick, because a score is derived from all of it:
     * one point for each threat a player connected, and one for each trick they took. Deriving it is
     * what keeps the score honest — there is no counter to drift from the play that produced it, and
     * no number a client could assert instead (ADR-030).
     *
     * <p>Nothing is filtered out. A trick still on the table comes back unresolved, alongside the
     * finished ones, because whether a trick is finished is a question the trick answers about
     * itself once it has been rebuilt, and a query that filtered on the stored winner would be a
     * second authority on the same fact. It also matters to the answer: the plays of an unfinished
     * trick have already scored their threats even though nobody has taken it yet.
     *
     * <p>Authorises nobody, exactly as the read above does not. There is no acting player here for an
     * implementation to check against, so a caller that has not already established membership is
     * handing a stranger the whole history of a session they guessed the identifier of (ADR-024).
     *
     * @param sessionId identifier of the session, never {@code null}
     * @return the session's tricks ordered by sequence, empty when no trick has been opened
     * @throws NullPointerException if {@code sessionId} is {@code null}
     */
    List<Trick> findTricks(UUID sessionId);

    /**
     * Deletes all tricks and plays for a session, resetting it for a new game.
     *
     * <p>Called by {@link NewGamePort} before re-dealing. Trick plays are deleted
     * first (FK constraint), then tricks. The session's leader seat is reset separately
     * via {@link SessionRepository#resetToInProgress}.
     *
     * @param sessionId the session whose tricks to clear
     */
    void clearTricksForNewGame(UUID sessionId);
}
