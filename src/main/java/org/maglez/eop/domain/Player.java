package org.maglez.eop.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One participant in one session.
 *
 * <p>There is no cross-session identity, so a person who joins two games is two
 * unrelated players. That is a deliberate consequence of having no accounts
 * (ADR-015), not an omission.
 *
 * <p>Carries the digest of its own identity token, because recognising a returning
 * player is a lookup within a session rather than a global one. The digest must
 * never leave the application: no response DTO exposes it, under any flag, to any
 * caller.
 *
 * @param playerId          stable identifier, a UUIDv7 assigned when the player joins (ADR-018)
 * @param displayName       the name this player chose
 * @param seatOrder         seat at the table, assigned once and never recomputed
 * @param role              whether this player may start play
 * @param connectionStatus  advisory guess at whether this player is listening
 * @param identityTokenHash digest of the token that proves this player's identity
 * @param joinedAt          when the player joined, for display only — never a source of seat order
 */
public record Player(
        UUID playerId,
        DisplayName displayName,
        int seatOrder,
        PlayerRole role,
        ConnectionStatus connectionStatus,
        IdentityTokenHash identityTokenHash,
        Instant joinedAt) {

    /**
     * Rejects a player who could not sit at a real table.
     *
     * @throws NullPointerException     if any reference component is null
     * @throws IllegalArgumentException if the seat is outside the table
     */
    public Player {
        Objects.requireNonNull(playerId, "playerId is required");
        Objects.requireNonNull(displayName, "displayName is required");
        Objects.requireNonNull(role, "role is required");
        Objects.requireNonNull(connectionStatus, "connectionStatus is required");
        Objects.requireNonNull(identityTokenHash, "identityTokenHash is required");
        Objects.requireNonNull(joinedAt, "joinedAt is required");
        if (seatOrder < 0 || seatOrder >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException(
                    "A seat is 0 through " + (GameSession.MAXIMUM_PLAYERS - 1) + ", was " + seatOrder);
        }
    }

    /**
     * Whether this player holds the token with the given digest.
     *
     * <p>Delegates to {@link IdentityTokenHash#equals(Object)}, which compares in
     * constant time. Do not replace this with a comparison on
     * {@link IdentityTokenHash#value()} — that is a plain {@link String#equals}
     * and returns on the first differing byte.
     *
     * @param candidate the digest of a presented token
     * @return true if this is the player that token belongs to
     */
    public boolean isIdentifiedBy(final IdentityTokenHash candidate) {
        return identityTokenHash.equals(candidate);
    }

    /**
     * Whether this player may close the lobby and start play.
     *
     * @return true if the player is the facilitator
     */
    public boolean canStartPlay() {
        return role.canStartPlay();
    }
}
