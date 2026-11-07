package org.maglez.eop.application.port.in;

import java.util.UUID;

import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.NoTamperingCardDealtException;
import org.maglez.eop.domain.NotFacilitatorException;
import org.maglez.eop.domain.TooFewPlayersException;

/**
 * Inbound port for the dealhands operation.
 *
 * <p>Implemented by {@code DealHandsService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface DealHandsPort {

    /**
     * Deals the deck to every seated player and records which seat leads the first trick.
     *
     * @param sessionId the session whose players are dealt to
     * @param playerToken the identity token of the player asking to deal
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.domain.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token names nobody at this
     *     table, or no token was given
     * @throws NotFacilitatorException if the caller is seated but is not the facilitator
     * @throws TooFewPlayersException if fewer than {@link GameSession#MINIMUM_PLAYERS_TO_START}
     *     players are seated
     * @throws org.maglez.eop.domain.SessionNotJoinableException if the session was not playable at
     *     the moment of the write, which includes a session that had not yet started
     * @throws org.maglez.eop.domain.HandAlreadyDealtException if this session has already been dealt
     * @throws org.maglez.eop.domain.PlayerNotInSessionException if a seat named in the deal is not
     *     seated in this session
     * @throws org.maglez.eop.domain.NotYourSeatException if a player named in the deal does not hold
     *     the seat the deal gives them
     * @throws NoTamperingCardDealtException if the shuffled deck contained no Tampering card, so no
     *     opening lead can be derived
     
     */
    void execute(UUID sessionId, String playerToken);
}
