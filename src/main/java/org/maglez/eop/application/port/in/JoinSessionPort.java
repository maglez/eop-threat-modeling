package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.DisplayName;
import org.maglez.eop.application.TooManyJoinAttemptsException;
import org.maglez.eop.domain.SeatAlreadyTakenException;
import org.maglez.eop.domain.SessionFullException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.UnknownJoinCodeException;

/**
 * Inbound port for the joinsession operation.
 *
 * <p>Implemented by {@code JoinSessionService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface JoinSessionPort {

    /**
     * Adds a participant to the lobby identified by a join code.
     *
     * <p>A code that could never be valid and a code that simply matches nothing both
     * end here as the same exception, recorded as the same kind of failure. Telling
     * those two apart would turn the endpoint into an oracle that confirms which codes
     * are real (ADR-019).
     *
     * <p>A refusal that arrives <em>after</em> the code matched is charged to the
     * throttle for the same reason. A full table and a game already under way are both
     * proof that the code is live, so leaving those paths free would let a caller
     * confirm real codes at full request rate while the limiter — the primary control
     * behind a forty-bit code space — never fires. Losing a race for a seat is not
     * charged: that is our own contention, not a failed attempt by the caller.
     *
     * <p>The identity token and the player identifier are generated once, before the
     * retry loop, so that losing a race for a seat does not change who the player is.
     *
     * @param rawJoinCode the code as the player typed it, in any case, possibly with
     *     characters that Crockford base32 folds
     * @param displayName the name the player chose, already validated
     * @param clientAddress the caller's address, used only to attribute failed attempts
     * @return the session as it stands after the join, with the player's credential
     * @throws NullPointerException if displayName is null
     * @throws TooManyJoinAttemptsException if this caller has failed too often; this
     *     supersedes any domain exception — a throttled caller receives 429 regardless
     *     of whether the session is full or not joinable
     * @throws UnknownJoinCodeException if the code matches no session
     * @throws SessionNotJoinableException if play has started (only when not throttled)
     * @throws SessionFullException if the table is full (only when not throttled)
     * @throws SeatAlreadyTakenException if every seat claim lost its race
     
     */
    SessionAdmission execute(String rawJoinCode, DisplayName displayName, String clientAddress);
}
