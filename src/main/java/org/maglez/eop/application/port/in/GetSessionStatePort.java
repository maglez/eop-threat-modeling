package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.GameSession;
import java.util.UUID;

/**
 * Inbound port for the getsessionstate operation.
 *
 * <p>Implemented by {@code GetSessionStateService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface GetSessionStatePort {

    /**
     * Reads the session a player belongs to.
     *
     * <p>Composed over {@link ResolvePlayerPort} rather than repeating the
     * resolve-then-read idiom, so there is exactly one place where possession of a
     * token becomes a named player and exactly one definition of what an unrecognised
     * credential means.
     *
     * @param sessionId the session being read
     * @param playerToken the caller's identity token, as received in the request header
     * @return the session as stored
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.domain.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token names no
     *     player seated in that session
     
     */
    GameSession execute(UUID sessionId, String playerToken);
}
