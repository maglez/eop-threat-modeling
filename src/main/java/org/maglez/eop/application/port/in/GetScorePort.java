package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.ScoreSheet;
import java.util.UUID;

/**
 * Inbound port for the getscore operation.
 *
 * <p>Implemented by {@code GetScoreService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface GetScorePort {

    /**
     * Reads the score of the session, as the Score Card would record it.
     *
     * @param sessionId   identifier of the session
     * @param playerToken the caller's identity token, as presented
     * @return the score of the session as it stands
     * @throws NullPointerException                              if {@code sessionId} is {@code null}
     * @throws org.maglez.eop.domain.SessionNotFoundException    if no session has that identifier
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token is missing, or belongs to no player of that session
     * @throws org.maglez.eop.domain.ScoreNotDerivableException  if the stored game contradicts itself
     
     */
    ScoreSheet execute(UUID sessionId, String playerToken);
}
