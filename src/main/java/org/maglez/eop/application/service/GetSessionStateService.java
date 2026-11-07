package org.maglez.eop.application.service;

import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.application.port.in.GetSessionStatePort;
import org.maglez.eop.application.port.in.ResolvePlayerPort;

/**
 * Reports the current state of a session to a player who belongs to it.
 *
 * <p>This is the reconnect path, and it is also the first-load path. A player who
 * refreshed the page, whose connection dropped, or who arrived after a deployment
 * restarted the container all ask the same question and get the same answer, because
 * the answer is assembled from database rows and from nothing else. No in-memory
 * registry, cache or subscriber list contributes to it, and no event history is
 * replayed — reconnection is a re-read, never a replay (ADR-014).
 *
 * <p>Keeping recovery and first load on one code path means the recovery path is
 * exercised by every ordinary page load rather than only when something has gone
 * wrong.
 */
public class GetSessionStateService implements GetSessionStatePort {

    private final ResolvePlayerPort resolvePlayerPort;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerPort the use case that turns a token into a seated player
     */
    public GetSessionStateService(final ResolvePlayerPort resolvePlayerPort) {
        this.resolvePlayerPort = Objects.requireNonNull(resolvePlayerPort, "resolvePlayerPort is required");
    }

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
    public GameSession execute(final UUID sessionId, final String playerToken) {
        return resolvePlayerPort.execute(sessionId, playerToken).session();
    }
}
