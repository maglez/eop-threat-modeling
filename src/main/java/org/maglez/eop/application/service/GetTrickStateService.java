package org.maglez.eop.application.service;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.PlayerNotInSessionException;

import org.maglez.eop.domain.Trick;
import org.maglez.eop.application.port.in.GetTrickStatePort;
import org.maglez.eop.application.port.in.ReadOwnHandPort;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.in.TrickState;
import org.maglez.eop.application.port.out.HandRepository;
import org.maglez.eop.application.port.out.TrickRepository;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Reports the state of play at a table: the current trick, whose turn it is, and what happens when
 * the trick is done.
 *
 * <p>This exists because a client otherwise cannot find out whose turn it is. The play and resolve
 * operations both answer with a {@link Trick}, and a {@link Trick} is deliberately silent on the seat
 * to play, on whether it is complete and on which seat leads next, because all three depend on the
 * seats that still hold cards — a fact about the session, not about one trick (ADR-023). Without this
 * read the only way to learn it is to attempt a play and be refused, which is a poor way to run a
 * turn.
 *
 * <p>Two repositories are read here rather than in the controller. The answer needs the trick and the
 * hands together, and stitching two aggregates is work for a use case: a web adapter that fetched the
 * second one would be making a rule about how they relate, in the layer least able to state it.
 *
 * <p>Any seated player may read it, and there is nothing to gate. Every part of the answer is already
 * public at the table — cards are played face up, and seats and turn order are visible to everyone
 * sitting at it. No card any seat is holding appears anywhere in the result, which is what separates
 * this from {@link ReadOwnHandPort}. A caller who holds no seat here is still refused, as
 * everywhere else: {@link HandRepository} and {@link TrickRepository} authorise nobody — no method on
 * either takes an acting player — so this layer is the only place a member is told from a stranger who
 * guessed a session identifier (ADR-024).
 *
 * <p>Deliberately no check on session status, for the same reason as {@link ReadOwnHandPort}:
 * looking back over the last trick after play ends is a reasonable thing to want, and nothing is
 * disclosed that was not already on the table. Before the deal there is no state of play at all, which
 * is {@link HandNotDealtException} and a 409 — a state that changes, rather than a missing resource, so
 * an empty answer would be the wrong shape for it.
 *
 * <p>A read, so no clock and no writes. Callers should prefer the event stream and use this to
 * recover, rather than polling it: an event says only that the session moved, and this is what it moved
 * to (ADR-014).
 */
public class GetTrickStateService implements GetTrickStatePort {

    private final ResolvePlayerPort resolvePlayerPort;

    private final HandRepository handRepository;

    private final TrickRepository trickRepository;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerPort resolves the acting player from the identity token
     * @param handRepository reads the hands, which decide which seats still hold cards
     * @param trickRepository reads the trick currently in front of the players
     */
    public GetTrickStateService(
            final ResolvePlayerPort resolvePlayerPort,
            final HandRepository handRepository,
            final TrickRepository trickRepository) {
        this.resolvePlayerPort = Objects.requireNonNull(resolvePlayerPort, "resolvePlayerPort is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
    }

    /**
     * Returns the state of play in the session.
     *
     * @param sessionId the session the caller is playing in
     * @param playerToken the requester's identity token, which may be null or unrecognised
     * @return the current trick if one has been led, whose turn it is, whether the trick is complete,
     *     which seat leads next, and whether every dealt card has been played
     * @throws NullPointerException if sessionId is null
     * @throws SessionNotFoundException if no session has that identifier
     * @throws PlayerNotRecognisedException if the token names nobody at this table
     * @throws HandNotDealtException if the deck has not been dealt yet
     * @throws PlayerNotInSessionException if the caller holds no seat that was dealt a hand
     */
    public TrickState execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerPort.execute(sessionId, playerToken);
        final var actingSeat = resolved.player().seatOrder();

        final var hands = handRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new HandNotDealtException(sessionId));

        if (!hands.hasSeat(actingSeat)) {
            throw new PlayerNotInSessionException(sessionId);
        }

        final var seatsHoldingCards = hands.seatsHoldingCards();
        final var currentTrick = trickRepository.findCurrentTrick(sessionId);

        // Whose turn it is has two sources, and which one answers depends on whether a trick is still
        // being played. While one is open the turn runs from the cards already in it. Once it has been
        // resolved — or before the first card is led — the seat the session records as leading is the
        // one entitled to play, and it is empty exactly when the hand is played out.
        final OptionalInt seatToPlay = currentTrick
                .filter(trick -> trick.winner().isEmpty())
                .map(trick -> trick.seatToPlay(seatsHoldingCards))
                .orElseGet(() -> handRepository.findCurrentLeaderSeat(sessionId));

        final boolean complete = currentTrick
                .map(trick -> trick.isComplete(seatsHoldingCards))
                .orElse(false);

        final OptionalInt nextLeaderSeat = currentTrick
                .filter(trick -> trick.winner().isPresent())
                .map(trick -> trick.nextLeaderSeat(seatsHoldingCards))
                .orElseGet(OptionalInt::empty);

        return new TrickState(currentTrick, seatToPlay, complete, nextLeaderSeat, hands.allEmpty());
    }
}
