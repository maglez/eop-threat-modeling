package org.maglez.eop.application.port.in;

import java.util.UUID;
import org.maglez.eop.domain.NoTamperingCardDealtException;
import org.maglez.eop.domain.NotFacilitatorException;
import org.maglez.eop.domain.TooFewPlayersException;

/**
 * Inbound port for the newgame operation.
 *
 * <p>Implemented by {@code NewGameService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface NewGamePort {

    /**
     * Resets the session and deals a new game.
     *
     * @param sessionId   the completed session to reset
     * @param playerToken the identity token of the player requesting the new game
     * @throws NullPointerException                              if sessionId is null
     * @throws org.maglez.eop.domain.SessionNotFoundException   if no such session exists
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token names nobody at this table
     * @throws NotFacilitatorException                           if the caller is not the facilitator
     * @throws org.maglez.eop.domain.GameNotCompletedException  if the session is not completed
     * @throws TooFewPlayersException                            if fewer than the minimum players are seated
     * @throws NoTamperingCardDealtException                     if the shuffled deck contained no Tampering card
     
     */
    void execute(UUID sessionId, String playerToken);
}
