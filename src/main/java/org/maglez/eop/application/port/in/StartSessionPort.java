package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.GameSession;
import java.util.UUID;

/**
 * Inbound port for the startsession operation.
 *
 * <p>Implemented by {@code StartSessionService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface StartSessionPort {

    /**
     * Moves a session from lobby into play at its facilitator's request.
     *
     * <p>Every rule applied here — that the caller is seated, that the caller is the
     * facilitator, that the lobby has not already been closed, and that three players
     * are present — is decided by {@link GameSession}. This method only supplies the
     * clock and persists the outcome.
     *
     * @param sessionId the session being started
     * @param playerToken the caller's identity token, as received in the request header
     * @return the session as it stands once play has begun
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.domain.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token names no
     *     player seated in that session
     * @throws org.maglez.eop.domain.NotFacilitatorException if the caller is a participant
     * @throws org.maglez.eop.domain.SessionNotJoinableException if the lobby is already closed
     * @throws org.maglez.eop.domain.TooFewPlayersException if fewer than three players are seated
     
     */
    GameSession execute(UUID sessionId, String playerToken);
}
