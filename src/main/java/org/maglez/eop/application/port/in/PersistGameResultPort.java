package org.maglez.eop.application.port.in;

import java.util.UUID;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Inbound port for the persistgameresult operation.
 *
 * <p>Implemented by {@code PersistGameResultService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface PersistGameResultPort {

    /**
     * Derives and persists the final game result for a completed session.
     *
     * <p>Both {@code startedAt} and {@code finalisedAt} are set to the current clock instant.
     * The session row does not carry a {@code startedAt} timestamp (only {@code updatedAt},
     * which is overwritten on every transition), so the two timestamps are equal here. They
     * are informational only and do not affect the score.
     *
     * @param sessionId  the session that just completed
     * @throws SessionNotFoundException if the session no longer exists
     
     */
    void execute(UUID sessionId);
}
