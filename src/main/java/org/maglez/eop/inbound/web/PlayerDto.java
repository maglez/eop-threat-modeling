package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.maglez.eop.domain.Player;

/**
 * A seated player as it crosses the HTTP boundary.
 *
 * <p>What is absent matters more than what is present. The domain
 * {@link Player} carries the digest of that player's credential, and no field here
 * corresponds to it: the digest is never serialised, to any caller, under any flag.
 * That is not an oversight to be tidied up later by adding a field for symmetry.
 *
 * @param playerId         stable identifier for this player within the session
 * @param displayName      the name the player typed, unverified and not unique
 * @param seatOrder        position at the table, assigned once at join
 * @param role             whether this player may start play
 * @param connectionStatus advisory presence hint, never an input to a game rule
 */
@Schema(name = "Player", description = "A player seated at a session's table.")
public record PlayerDto(
        String playerId,
        String displayName,
        int seatOrder,
        String role,
        String connectionStatus) {

    /**
     * Converts a domain player into its transport form.
     *
     * @param player the domain player
     * @return the transport object
     */
    public static PlayerDto from(final Player player) {
        return new PlayerDto(
                player.playerId().toString(),
                player.displayName().value(),
                player.seatOrder(),
                player.role().name(),
                player.connectionStatus().name());
    }
}
