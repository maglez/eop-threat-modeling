package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.GameSession;
import java.util.UUID;

/**
 * Inbound port for the endsession operation.
 *
 * <p>Implemented by {@code EndSessionService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface EndSessionPort {

    /**
     * Moves a session from in-progress to completed at the facilitator's request.
     *
     * <p>Every rule applied here — that the caller is seated, that the caller is the
     * facilitator, and that the session is currently in progress — is decided by
     * {@link GameSession}. This method only supplies the clock and persists the outcome.
     *
     * @param sessionId the session being ended
     * @param playerToken the caller's identity token, as received in the request header
     * @return the session as it stands once it is completed
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.domain.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token names no
     *     player seated in that session
     * @throws org.maglez.eop.domain.NotFacilitatorException if the caller is a participant
     * @throws org.maglez.eop.domain.SessionNotInProgressException if the session is not
     *     currently in progress
     
     */
    GameSession execute(UUID sessionId, String playerToken);
}
