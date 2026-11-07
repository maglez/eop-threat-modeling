package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.DisplayName;
import org.maglez.eop.domain.JoinCodeUnavailableException;

/**
 * Inbound port for the createsession operation.
 *
 * <p>Implemented by {@code CreateSessionService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface CreateSessionPort {

    /**
     * Opens a lobby for a facilitator.
     *
     * <p>The identity token and the player identifier are generated once, before
     * the retry loop, so that a redrawn join code does not also change who the
     * facilitator is.
     *
     * @param displayName the name the facilitator chose, already validated
     * @return the created session together with the facilitator's credential
     * @throws NullPointerException if displayName is null
     * @throws JoinCodeUnavailableException if a free join code could not be found
     *     within the attempt budget
     
     */
    SessionAdmission execute(DisplayName displayName);
}
