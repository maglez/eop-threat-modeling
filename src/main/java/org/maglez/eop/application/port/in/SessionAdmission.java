package org.maglez.eop.application.port.in;

import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.GameSession;

/**
 * The result of being admitted to a session, whether by creating it or joining it.
 *
 * <p>Creating and joining differ in how they find the session and in the status
 * code they return, but what the caller needs back is identical: who it now is,
 * the credential proving it, and the state of the table it has joined. One type
 * carries both, because two types holding the same three fields drift apart.
 *
 * <p>The plaintext token appears here and in the response built from here, and
 * nowhere else, ever. It is never logged, never stored, and never returned again;
 * only its digest reaches the database (ADR-015). A client that loses it has no way
 * to recover it and rejoins as a new player.
 *
 * @param session the session as it stands immediately after the admission
 * @param playerId the identifier of the admitted player, supplied so the client
 *     does not have to infer its own identity by matching on a display name that
 *     is not unique
 * @param playerToken the plaintext identity token, transmitted this once
 */
public record SessionAdmission(GameSession session, UUID playerId, String playerToken) {

    /**
     * Validates that every part of an admission is present.
     *
     * @throws NullPointerException if any argument is null
     */
    public SessionAdmission {
        Objects.requireNonNull(session, "session is required");
        Objects.requireNonNull(playerId, "playerId is required");
        Objects.requireNonNull(playerToken, "playerToken is required");
    }
}
