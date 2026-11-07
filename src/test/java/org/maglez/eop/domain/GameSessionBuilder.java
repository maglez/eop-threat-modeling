package org.maglez.eop.domain;

import static org.maglez.eop.domain.PlayerBuilder.aParticipant;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test data builder for {@link GameSession}.
 *
 * <p>Present so that a test which cares about one thing — usually the status or
 * the number of players seated — does not have to assemble a facilitator and a
 * join code first. Every default here is valid.
 *
 * <p>Builds through {@link GameSession#reconstitute} rather than
 * {@link GameSession#openLobby} because a test frequently needs a session in a
 * status that no sequence of legal calls could reach quickly, such as one that
 * is already {@code IN_PROGRESS} with five players. Tests of the transitions
 * themselves start from {@code openLobby} directly.
 */
public final class GameSessionBuilder {

    private UUID sessionId = UUID.fromString("00000000-0000-7000-8000-0000000000ff");
    private JoinCode joinCode = new JoinCode("ABC23456");
    private SessionStatus status = SessionStatus.LOBBY;
    private List<Player> players = List.of(aPlayer().build());
    private Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
    private Instant updatedAt = Instant.parse("2026-01-01T10:00:00Z");
    private Instant expiresAt = Instant.parse("2099-12-31T23:59:59Z");

    private GameSessionBuilder() {
    }

    /**
     * Starts a builder holding valid defaults: a lobby with only its facilitator.
     *
     * @return a new builder
     */
    public static GameSessionBuilder aSession() {
        return new GameSessionBuilder();
    }

    /**
     * @param value the identifier to use
     * @return this builder
     */
    public GameSessionBuilder withSessionId(final UUID value) {
        this.sessionId = value;
        return this;
    }

    /**
     * @param value the join code to use
     * @return this builder
     */
    public GameSessionBuilder withJoinCode(final JoinCode value) {
        this.joinCode = value;
        return this;
    }

    /**
     * @param value the status to use
     * @return this builder
     */
    public GameSessionBuilder withStatus(final SessionStatus value) {
        this.status = value;
        return this;
    }

    /**
     * @param value the players to seat, in any order
     * @return this builder
     */
    public GameSessionBuilder withPlayers(final List<Player> value) {
        this.players = value;
        return this;
    }

    /**
     * Seats the facilitator plus enough participants to reach the given count.
     *
     * <p>Convenience for the many tests whose only interest in the roster is how
     * long it is: whether the table is full, or one short of the minimum to start.
     *
     * @param count how many players to seat in total, at least one
     * @return this builder
     */
    public GameSessionBuilder withPlayerCount(final int count) {
        final List<Player> seated = new ArrayList<>();
        seated.add(aPlayer().build());
        for (int seat = 1; seat < count; seat++) {
            seated.add(aParticipant(seat).build());
        }
        this.players = List.copyOf(seated);
        return this;
    }

    /**
     * @param value the creation instant to use
     * @return this builder
     */
    public GameSessionBuilder withCreatedAt(final Instant value) {
        this.createdAt = value;
        return this;
    }

    /**
     * @param value the last-changed instant to use
     * @return this builder
     */
    public GameSessionBuilder withUpdatedAt(final Instant value) {
        this.updatedAt = value;
        return this;
    }

    /**
     * @param value the expiry instant to use
     * @return this builder
     */
    public GameSessionBuilder withExpiresAt(final Instant value) {
        this.expiresAt = value;
        return this;
    }

    /**
     * @return the session described by this builder
     */
    public GameSession build() {
        return GameSession.reconstitute(sessionId, joinCode, status, players, createdAt, updatedAt, expiresAt);
    }
}
