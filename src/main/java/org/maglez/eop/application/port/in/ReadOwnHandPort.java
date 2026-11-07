package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.Hand;
import java.util.UUID;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Inbound port for the readownhand operation.
 *
 * <p>Implemented by {@code ReadOwnHandService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface ReadOwnHandPort {

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
    Hand execute(UUID sessionId, String playerToken);
}
