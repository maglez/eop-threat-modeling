package org.maglez.eop.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.maglez.eop.domain.Hands;

import org.maglez.eop.application.port.in.NewGamePort;
import org.maglez.eop.domain.HandAlreadyDealtException;
import org.maglez.eop.domain.NotYourSeatException;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotJoinableException;

/**
 * Port through which the application reads and writes the hands dealt in a session.
 *
 * <p>A session identifier is the first parameter of every method because {@link Hands}
 * does not carry one. It holds a map of seat to hand and nothing else, so the session a
 * deal belongs to can only reach storage through the signature. That is deliberate:
 * putting a session identifier inside the aggregate would give two places to look for
 * the same fact.
 *
 * <p>The read returns the hands of the <em>whole session</em>, never one seat's hand,
 * and that is a correctness requirement rather than a convenience. {@code Hands}
 * enforces four invariants across seats when it is rebuilt — distinct players, distinct
 * hand identifiers, no empty deal, and no card held at two seats at once. Reconstituting
 * one seat at a time would satisfy every one of them vacuously. The last of those is the
 * one that matters most, because ADR-023 records that <em>no database constraint stands
 * behind it</em>: {@code pk_hand_card} stops a card appearing twice in one hand and
 * {@code uq_trick_play_trick_card} stops it being played twice into one trick, but
 * nothing stops the same card being dealt to two hands of the same session. The
 * {@code Hands} invariant is the only guard that exists, so a partial read would
 * silently switch off the only enforcement of a rule the ADR calls load-bearing.
 *
 * <p>The write methods are narrow and conditional, for the reasons
 * {@link SessionRepository} sets out. Each one names the state the caller observed, and
 * a write that would land on a session someone else has already moved on is refused
 * rather than applied. No method returns a row count: how many rows a conditional
 * statement changed is the storage layer's private protocol, and a port that leaked it
 * would oblige every caller to know what zero means.
 *
 * <p><strong>Authorising the requester is the caller's obligation, not this port's.</strong>
 * Nothing here takes an acting player, so no implementation can check one, and the read
 * cannot check one even in principle. {@code recordDeal} does assert that each player a
 * dealt hand names is seated at the seat that hand claims, but that is a check on the
 * rows being written, not on whoever asked for them to be written. Establish that the
 * requester belongs in the session before calling either method: the refusals below
 * distinguish a session that does not exist from one that exists, from one already dealt,
 * and those are the right answers to give a member and an oracle to give a stranger.
 */
public interface HandRepository {

    /**
     * Reads every hand dealt in a session.
     *
     * <p>An empty result means the deal has not happened yet. It cannot mean "dealt,
     * but to nobody": {@code Hands} rejects an empty map outright, so that state has no
     * representation and {@link Optional#empty()} is the only encoding available.
     *
     * @param sessionId the session whose hands to read
     * @return the hands of that session, or empty if no hand has been dealt in it
     */
    Optional<Hands> findBySessionId(UUID sessionId);

    /**
     * Reads the seat that leads the trick currently in progress.
     *
     * <p>Empty means no leader has been recorded, which is the state of every session
     * that has not been dealt. A caller uses this as the witness for its next
     * conditional write, having already established through {@link SessionRepository}
     * that the session exists.
     *
     * @param sessionId the session to read
     * @return the current leader's seat, or empty if the session has no leader recorded
     */
    OptionalInt findCurrentLeaderSeat(UUID sessionId);

    /**
     * Records a deal: every hand, every card in it, and the seat that leads the first
     * trick.
     *
     * <p>All of it is one transaction. A deal that stored some hands but not others
     * would be a game in which some players hold cards nobody can account for, and the
     * deck completeness rule — every card dealt, no draw pile — would be silently false
     * for the rest of the session.
     *
     * <p>This is also the deal-once gate. The leader seat is written only where none is
     * recorded, so two facilitators starting the same session in the same instant
     * produce one deal and one refusal rather than two overlapping deals.
     *
     * @param sessionId the session being dealt
     * @param hands the hands to store, keyed by seat
     * @param openingLeaderSeat the seat that leads the first trick, which the caller
     *     derived from the dealt cards rather than from a stored rank
     * @param occurredAt the instant recorded as the session's last update
     * @throws SessionNotFoundException if the session no longer exists
     * @throws SessionNotJoinableException if the session is not in play, which includes
     *     the case where it had not started at the moment of the write
     * @throws HandAlreadyDealtException if the session has already been dealt, which is
     *     how two simultaneous deals are resolved
     * @throws PlayerNotInSessionException if a hand names a player who does not sit in
     *     that session
     * @throws NotYourSeatException if a hand names a seat its player does not occupy
     */
    void recordDeal(UUID sessionId, Hands hands, int openingLeaderSeat, Instant occurredAt);

    /**
     * Deletes all hands and hand-cards for a session, resetting it for a new deal.
     *
     * <p>Called by {@link NewGamePort} before re-dealing the same deck to the same
     * seats. The leader seat is also cleared so that {@link #recordDeal} can write a
     * fresh opening lead.
     *
     * @param sessionId the session whose hands to clear
     */
    void clearHandsForNewGame(UUID sessionId);
}
