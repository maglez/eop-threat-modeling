package org.maglez.eop.application.port.in;

import java.util.Objects;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.Player;

/**
 * A session together with the one player a request was made on behalf of.
 *
 * <p>Every authenticated operation needs both halves: the session to act on and
 * the seated player the token identified. Returning only the player would force
 * callers to read the session a second time, and returning only the session would
 * force them to repeat the token lookup. Pairing them means the identity check
 * happens exactly once per request.
 *
 * @param session the session the token was presented against
 * @param player the seated player that token identifies, guaranteed to be one of
 *     the session's own players
 */
public record ResolvedPlayer(GameSession session, Player player) {

    /**
     * Validates that both halves of the pair are present.
     *
     * @throws NullPointerException if either argument is null
     */
    public ResolvedPlayer {
        Objects.requireNonNull(session, "session is required");
        Objects.requireNonNull(player, "player is required");
    }
}
