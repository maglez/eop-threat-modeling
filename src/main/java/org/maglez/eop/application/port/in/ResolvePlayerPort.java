package org.maglez.eop.application.port.in;

import java.util.UUID;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Inbound port for the resolveplayer operation.
 *
 * <p>Implemented by {@code ResolvePlayerService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface ResolvePlayerPort {

    /**
     * Resolves a token to the player it identifies within a session.
     *
     * <p>A missing token and an unrecognised token produce the same exception. The
     * caller presented no proof of identity in both cases, and distinguishing them
     * would report back whether a token was the right shape.
     *
     * @param sessionId the session the caller is acting on
     * @param playerToken the plaintext identity token presented with the request,
     *     which may be null or blank when the caller sent no header at all
     * @return the session paired with the player the token identifies
     * @throws NullPointerException if sessionId is null
     * @throws SessionNotFoundException if no session has that identifier
     * @throws PlayerNotRecognisedException if no token was presented, or the token
     *     presented does not belong to a player in that session
     
     */
    ResolvedPlayer execute(UUID sessionId, String playerToken);
}
