package org.maglez.eop.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Test data builder for {@link Player}.
 *
 * <p>Present so that a test which cares about one field — nearly always the seat
 * or the role — does not have to state the other six. Every default here is
 * valid, so a test that fails is failing on the thing it set.
 *
 * <p>The identity token digest defaults to the hash of a distinctive plaintext
 * rather than a hand-written 64-character literal, so a test that accidentally
 * asserts on the digest is obviously doing so.
 */
public final class PlayerBuilder {

    /** Plaintext behind the default digest, exposed so a test can present it. */
    public static final String DEFAULT_TOKEN = "builder-default-player-token";

    private UUID playerId = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private DisplayName displayName = new DisplayName("Ada");
    private int seatOrder;
    private PlayerRole role = PlayerRole.FACILITATOR;
    private ConnectionStatus connectionStatus = ConnectionStatus.CONNECTED;
    private IdentityTokenHash identityTokenHash = IdentityTokenHash.of(DEFAULT_TOKEN);
    private Instant joinedAt = Instant.parse("2026-01-01T10:00:00Z");

    private PlayerBuilder() {
    }

    /**
     * Starts a builder holding valid defaults: the facilitator in seat 0.
     *
     * @return a new builder
     */
    public static PlayerBuilder aPlayer() {
        return new PlayerBuilder();
    }

    /**
     * Starts a builder for a participant, the role every player but one holds.
     *
     * <p>Participant identifiers are derived from the seat in a range of their
     * own, deliberately away from the facilitator's, so that a test asking
     * "which player is this?" cannot pass by accident on a collision.
     *
     * @param seat the seat this participant occupies
     * @return a new builder
     */
    public static PlayerBuilder aParticipant(final int seat) {
        return new PlayerBuilder()
                .withPlayerId(UUID.fromString("00000000-0000-7000-8000-0000000000a" + seat))
                .withDisplayName(new DisplayName("Player " + seat))
                .withSeatOrder(seat)
                .withRole(PlayerRole.PARTICIPANT)
                .withIdentityTokenHash(IdentityTokenHash.of(DEFAULT_TOKEN + "-" + seat));
    }

    /**
     * @param value the identifier to use
     * @return this builder
     */
    public PlayerBuilder withPlayerId(final UUID value) {
        this.playerId = value;
        return this;
    }

    /**
     * @param value the display name to use
     * @return this builder
     */
    public PlayerBuilder withDisplayName(final DisplayName value) {
        this.displayName = value;
        return this;
    }

    /**
     * @param value the seat to use
     * @return this builder
     */
    public PlayerBuilder withSeatOrder(final int value) {
        this.seatOrder = value;
        return this;
    }

    /**
     * @param value the role to use
     * @return this builder
     */
    public PlayerBuilder withRole(final PlayerRole value) {
        this.role = value;
        return this;
    }

    /**
     * @param value the connection status to use
     * @return this builder
     */
    public PlayerBuilder withConnectionStatus(final ConnectionStatus value) {
        this.connectionStatus = value;
        return this;
    }

    /**
     * @param value the token digest to use
     * @return this builder
     */
    public PlayerBuilder withIdentityTokenHash(final IdentityTokenHash value) {
        this.identityTokenHash = value;
        return this;
    }

    /**
     * Sets the digest to the hash of the given plaintext token.
     *
     * @param plaintext the token a test will later present
     * @return this builder
     */
    public PlayerBuilder withToken(final String plaintext) {
        this.identityTokenHash = IdentityTokenHash.of(plaintext);
        return this;
    }

    /**
     * @param value the join instant to use
     * @return this builder
     */
    public PlayerBuilder withJoinedAt(final Instant value) {
        this.joinedAt = value;
        return this;
    }

    /**
     * @return the player described by this builder
     */
    public Player build() {
        return new Player(playerId, displayName, seatOrder, role, connectionStatus, identityTokenHash, joinedAt);
    }
}
