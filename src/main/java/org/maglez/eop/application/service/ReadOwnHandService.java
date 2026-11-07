package org.maglez.eop.application.service;

import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.Hand;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.PlayerNotInSessionException;

import org.maglez.eop.application.port.in.ReadOwnHandPort;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.out.HandRepository;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Reads back the cards the requester is holding.
 *
 * <p>The only hand this can return is the caller's own, and that is a property of the code rather
 * than of the request: the seat is taken from the resolved identity token and nothing in the
 * signature can name another player. There is no sibling use case for another player's hand or for
 * every hand at once, because a game of Elevation of Privilege is unplayable if a hand can be read
 * by anyone but its holder, and the safest way to guarantee that is for the operation not to exist.
 *
 * <p>Authorising the requester is the first statement, as it is in every use case that touches this
 * data. {@link HandRepository} authorises nobody — no method on it takes an acting player — so this
 * layer is the only place the caller can be told apart from a stranger who guessed a session
 * identifier (ADR-024).
 *
 * <p>Deliberately no check on session status. A player may read their own hand whether the session is
 * in progress, completed or abandoned: the cards were dealt to them, they have seen them already,
 * and refusing the read once play ends would hide a player's own hand from them at exactly the moment
 * they want to look back over it. Nothing is disclosed that the caller did not already hold. Before
 * the deal there is no hand at all, which is {@link HandNotDealtException} and a 409 — a state that
 * changes, not a missing resource.
 *
 * <p>A read, so no clock and no writes: nothing here records that a player looked at their cards.
 */
public class ReadOwnHandService implements ReadOwnHandPort {

    private final ResolvePlayerPort resolvePlayerPort;

    private final HandRepository handRepository;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerPort resolves the acting player from the identity token
     * @param handRepository reads the hands dealt in the session
     */
    public ReadOwnHandService(final ResolvePlayerPort resolvePlayerPort, final HandRepository handRepository) {
        this.resolvePlayerPort = Objects.requireNonNull(resolvePlayerPort, "resolvePlayerPort is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
    }

    /**
     * Returns the hand held by the player the token identifies.
     *
     * @param sessionId the session the caller is playing in
     * @param playerToken the requester's identity token, which may be null or unrecognised
     * @return the caller's own hand, which may be empty once they have played their last card
     * @throws NullPointerException if sessionId is null
     * @throws SessionNotFoundException if no session has that identifier
     * @throws PlayerNotRecognisedException if the token names nobody at this table
     * @throws HandNotDealtException if the deck has not been dealt yet
     * @throws PlayerNotInSessionException if the caller holds no seat that was dealt a hand
     */
    public Hand execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerPort.execute(sessionId, playerToken);
        final var actingSeat = resolved.player().seatOrder();

        final var hands = handRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new HandNotDealtException(sessionId));

        if (!hands.hasSeat(actingSeat)) {
            throw new PlayerNotInSessionException(sessionId);
        }

        return hands.handOf(actingSeat);
    }
}
