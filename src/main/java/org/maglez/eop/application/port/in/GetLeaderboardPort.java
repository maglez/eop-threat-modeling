package org.maglez.eop.application.port.in;

import java.util.UUID;
import org.maglez.eop.application.GameResultNotRecordedException;
import org.maglez.eop.domain.GameNotCompletedException;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Inbound port for the getleaderboard operation.
 *
 * <p>Implemented by {@code GetLeaderboardService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface GetLeaderboardPort {

    /**
     * Reads the leaderboard for a completed session.
     *
     * @param sessionId   the session to read the leaderboard for
     * @param playerToken the caller's identity token, as presented
     * @return the leaderboard result carrying the resolved session status and the derived score sheet
     * @throws NullPointerException              if sessionId is null
     * @throws SessionNotFoundException          if no session has that identifier
     * @throws PlayerNotRecognisedException      if the token names nobody at this table
     * @throws GameNotCompletedException         if the session is not yet completed
     * @throws GameResultNotRecordedException    if the session is completed but no result was recorded
     
     */
    LeaderboardResult execute(UUID sessionId, String playerToken);
}
