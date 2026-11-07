package org.maglez.eop.application.port.in;

import java.util.UUID;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Inbound port for the gettrickstate operation.
 *
 * <p>Implemented by {@code GetTrickStateService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface GetTrickStatePort {

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
    TrickState execute(UUID sessionId, String playerToken);
}
