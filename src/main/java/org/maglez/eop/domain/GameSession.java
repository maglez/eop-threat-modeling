package org.maglez.eop.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A game session and the players sitting at it. The aggregate root.
 *
 * <p>Immutable. Every rule that changes a session returns a new instance rather
 * than mutating this one, so a rejected change cannot leave a half-applied
 * session behind and a caller cannot be surprised by a value changing under it.
 *
 * <p>Named {@code GameSession} rather than {@code Session}, and stored in a table
 * called {@code game_session}, because "session" already means an HTTP session in
 * every conversation about this code and is a reserved identifier on some
 * database engines (PRD §5).
 *
 * <p>Pure domain: no Spring, no Jakarta, no persistence annotations. The
 * persistence adapter holds its own separate mapped types and converts.
 *
 * <p>Two numbers here are rules of the game rather than configuration. Three
 * players is the minimum because the deck's play only works with three or more,
 * and six is the maximum because the deck runs out. Neither belongs in a
 * properties file: a deployment that could set them to two and twelve would be a
 * deployment that could break the game.
 */
public final class GameSession {

    /** Most players the deck supports at one table. */
    public static final int MAXIMUM_PLAYERS = 6;

    /** Fewest players the game works with. A rule, not a threshold. */
    public static final int MINIMUM_PLAYERS_TO_START = 3;

    /** How long a session lives before it expires. */
    public static final java.time.Duration SESSION_TTL = java.time.Duration.ofHours(24);

    private final UUID sessionId;

    private final JoinCode joinCode;

    private final SessionStatus status;

    private final List<Player> players;

    private final Instant createdAt;

    private final Instant updatedAt;

    private final Instant expiresAt;

    private GameSession(
            final UUID sessionId,
            final JoinCode joinCode,
            final SessionStatus status,
            final List<Player> players,
            final Instant createdAt,
            final Instant updatedAt,
            final Instant expiresAt) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId is required");
        this.joinCode = Objects.requireNonNull(joinCode, "joinCode is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(players, "players is required");
        final List<Player> ordered = new ArrayList<>(players);
        ordered.sort(Comparator.comparingInt(Player::seatOrder));
        this.players = List.copyOf(ordered);
        if (this.players.size() > MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException("A session holds at most " + MAXIMUM_PLAYERS + " players, was " + this.players.size());
        }
        if (this.players.stream().map(Player::seatOrder).distinct().count() != this.players.size()) {
            throw new IllegalArgumentException("Two players cannot hold the same seat");
        }
    }

    /**
     * Opens a new lobby
     *
     * <p>A session never exists without at least one player: the act of creating
     * one is the act of the facilitator joining it, so there is no window in
     * which an empty session is reachable by a join code.
     *
     * @param sessionId   the new session's identifier
     * @param joinCode    the code to share
     * @param facilitator the creating player, who must hold seat zero
     * @param now         the creation instant
     * @return a session in {@link SessionStatus#LOBBY}
     * @throws IllegalArgumentException if the facilitator is not seat zero or not a facilitator
     */
    public static GameSession openLobby(
            final UUID sessionId, final JoinCode joinCode, final Player facilitator, final Instant now) {
        Objects.requireNonNull(facilitator, "facilitator is required");
        if (facilitator.seatOrder() != 0) {
            throw new IllegalArgumentException("The facilitator holds seat 0, was " + facilitator.seatOrder());
        }
        if (!facilitator.canStartPlay()) {
            throw new IllegalArgumentException("The creating player is the facilitator");
        }
        return new GameSession(sessionId, joinCode, SessionStatus.LOBBY, List.of(facilitator), now, now, now.plus(SESSION_TTL));
    }

    /**
     * Rebuilds a session from stored rows.
     *
     * <p>This is the only path used on reconnect, on refresh and on the first
     * request after a deployment, because the database is the only authority on
     * state (ADR-014). Nothing in this method consults memory, and nothing about
     * the result depends on whether the process has seen this session before.
     *
     * @param sessionId the stored identifier
     * @param joinCode  the stored code
     * @param status    the stored status
     * @param players   the stored players, in any order
     * @param createdAt when the session was created
     * @param updatedAt when the session last changed
     * @param expiresAt when the session becomes eligible for sweeping
     * @return the session as stored
     */
    public static GameSession reconstitute(
            final UUID sessionId,
            final JoinCode joinCode,
            final SessionStatus status,
            final List<Player> players,
            final Instant createdAt,
            final Instant updatedAt,
            final Instant expiresAt) {
        return new GameSession(sessionId, joinCode, status, players, createdAt, updatedAt, expiresAt);
    }

    /**
     * The seat the next player to join will take.
     *
     * <p>Seats are contiguous from zero and nothing removes a player, so this is
     * the current count. Two simultaneous joins will compute the same seat: that
     * race is settled by the {@code uq_player_session_seat} database constraint
     * rejecting the second insert, not by this method (ADR-019). Callers retry.
     *
     * <p>A full table has no next seat, so this refuses rather than handing back
     * a seat number {@link Player} would reject. Returning the count regardless
     * made the seventh join fail as an invalid argument, which the API reported
     * as 400 quoting an internal invariant, instead of the 409 the caller is
     * owed.
     *
     * <p>The capacity rule is stated twice on purpose: here, so a caller asking
     * for a seat is refused before it builds anything; and again in
     * {@link #join(Player, Instant)}, which has to hold the invariant even for a
     * caller that never asks for a seat. Neither check makes the other
     * redundant. An earlier version of this paragraph claimed "one place states
     * it", which the guard in {@code join} has always falsified — do not restore
     * the tidier sentence.
     *
     * @return the next free seat
     * @throws SessionFullException if every seat is already taken
     */
    public int nextSeatOrder() {
        if (players.size() >= MAXIMUM_PLAYERS) {
            throw new SessionFullException(sessionId, MAXIMUM_PLAYERS);
        }
        return players.size();
    }

    /**
     * Adds a player to the lobby.
     *
     * <p>The capacity check below is deliberate defence in depth, not a leftover.
     * {@link #nextSeatOrder()} refuses a seventh seat and the join use case calls
     * it immediately beforehand, so on today's path this branch cannot be
     * reached. Reachability is a property of the current callers, though, not of
     * this method: a future caller that seats a player without first asking for a
     * seat number would reach it, and an entity has to hold its own invariant
     * rather than trust the sequence it happens to be called in.
     *
     * <p>What it does not do is make {@code MAXIMUM_PLAYERS} true of every
     * instance — the private constructor does that, and every path back out of
     * this class returns through it. Delete this check and a seventh player is
     * still refused, but as an {@code IllegalArgumentException} the API reports as
     * 400 quoting an internal invariant. What this check preserves is the 409 the
     * caller is owed, held at this seam instead of delegated to the constructor's
     * coarser refusal — the same lesson {@link #nextSeatOrder()} records above.
     *
     * @param joining the player to seat
     * @param now     the instant of the join
     * @return a new session including the player
     * @throws SessionNotJoinableException if play has already started
     * @throws SessionFullException        if the table is full
     */
    public GameSession join(final Player joining, final Instant now) {
        Objects.requireNonNull(joining, "joining is required");
        if (!status.acceptsNewPlayers()) {
            throw new SessionNotJoinableException(sessionId, status);
        }
        if (players.size() >= MAXIMUM_PLAYERS) {
            throw new SessionFullException(sessionId, MAXIMUM_PLAYERS);
        }
        final List<Player> seated = new ArrayList<>(players);
        seated.add(joining);
        return new GameSession(sessionId, joinCode, status, seated, createdAt, now, expiresAt);
    }

    /**
     * Closes the lobby so play can begin.
     *
     * <p>Starting establishes that the lobby is closed and nothing more. Dealing
     * the deck is EOP-14, deliberately not here: a story that both closed the
     * lobby and dealt would make the transition untestable without the whole
     * card-dealing machinery.
     *
     * @param requestedBy the player asking to start
     * @param now         the instant of the transition
     * @return a new session in {@link SessionStatus#IN_PROGRESS}
     * @throws PlayerNotRecognisedException if the requesting player is not at this table
     * @throws NotFacilitatorException      if the requesting player is not the facilitator
     * @throws SessionNotJoinableException  if the session has already left the lobby
     * @throws TooFewPlayersException       if fewer than {@link #MINIMUM_PLAYERS_TO_START} are seated
     */
    public GameSession start(final UUID requestedBy, final Instant now) {
        // SECURITY: identity checks MUST precede state checks. This ordering is load-bearing
        // and must not be changed without a security review (EOP-23).
        //
        // An unrecognised caller must not be able to infer session state from the error code.
        // If state were checked first, a caller outside the table could distinguish a lobby
        // (409 "session not in lobby") from an in-progress game (403 "not recognised") by
        // probing the start endpoint — leaking whether a session is joinable without holding
        // an identity token. Identity first means every unrecognised caller receives 403
        // regardless of what state the session is in.
        //
        // Guard order:
        //   1. Is the caller at this table?          → 403 PlayerNotRecognisedException
        //   2. Is the caller the facilitator?        → 403 NotFacilitatorException
        //   3. Is the session still in the lobby?    → 409 SessionNotJoinableException
        //   4. Are there enough players to start?    → 409 TooFewPlayersException
        final Player requester = playerById(requestedBy).orElseThrow(() -> new PlayerNotRecognisedException(sessionId));
        if (!requester.canStartPlay()) {
            throw new NotFacilitatorException(sessionId, requestedBy);
        }
        if (status != SessionStatus.LOBBY) {
            throw new SessionNotJoinableException(sessionId, status);
        }
        if (players.size() < MINIMUM_PLAYERS_TO_START) {
            throw new TooFewPlayersException(sessionId, players.size(), MINIMUM_PLAYERS_TO_START);
        }
        return new GameSession(sessionId, joinCode, SessionStatus.IN_PROGRESS, players, createdAt, now, expiresAt);
    }

    /**
     * Ends the session early at the facilitator's request.
     *
     * <p>The automatic path — all tricks played — does not call this method;
     * it goes directly to {@code SessionRepository#recordCompleted} after the
     * last trick resolves. This method is for the facilitator's explicit
     * end-session action, which may arrive before every card has been played.
     * That type is named in prose rather than linked because it lives in the
     * use-case layer, and an entity may not import outwards.
     *
     * @param requestedBy the player asking to end the session
     * @param now         the instant of the transition
     * @return a new session in {@link SessionStatus#COMPLETED}
     * @throws PlayerNotRecognisedException    if the requesting player is not at this table
     * @throws NotFacilitatorException         if the requesting player is not the facilitator
     * @throws SessionNotInProgressException   if the session is not currently in progress
     */
    public GameSession complete(final UUID requestedBy, final Instant now) {
        final Player requester = playerById(requestedBy).orElseThrow(() -> new PlayerNotRecognisedException(sessionId));
        if (!requester.canStartPlay()) {
            throw new NotFacilitatorException(sessionId, requestedBy);
        }
        if (status != SessionStatus.IN_PROGRESS) {
            throw new SessionNotInProgressException(sessionId, status);
        }
        return new GameSession(sessionId, joinCode, SessionStatus.COMPLETED, players, createdAt, now, expiresAt);
    }

    /**
     * Finds the player who holds the token with this digest.
     *
     * @param tokenHash the digest of a presented token
     * @return the matching player, or empty if no player at this table holds it
     */
    public Optional<Player> playerByTokenHash(final IdentityTokenHash tokenHash) {
        if (tokenHash == null) {
            return Optional.empty();
        }
        return players.stream().filter(player -> player.isIdentifiedBy(tokenHash)).findFirst();
    }

    /**
     * Finds a seated player by identifier.
     *
     * @param playerId the identifier to look for
     * @return the matching player, or empty
     */
    public Optional<Player> playerById(final UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return players.stream().filter(player -> player.playerId().equals(playerId)).findFirst();
    }

    /**
     * The session's identifier. Appears in shareable URLs and is not a secret:
     * holding it grants nothing without an identity token.
     *
     * @return the identifier
     */
    public UUID sessionId() {
        return sessionId;
    }

    /**
     * The code that identifies this session to somebody joining it.
     *
     * @return the join code
     */
    public JoinCode joinCode() {
        return joinCode;
    }

    /**
     * Where the session is in its life.
     *
     * @return the status
     */
    public SessionStatus status() {
        return status;
    }

    /**
     * The players at the table, in ascending seat order.
     *
     * @return an unmodifiable list
     */
    public List<Player> players() {
        return players;
    }

    /**
     * When the session was created.
     *
     * @return the creation instant
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * When the session last changed.
     *
     * @return the last-changed instant
     */
    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * When this session expires.
     *
     * <p>Set to {@link #SESSION_TTL} after {@link #createdAt()} when the lobby is
     * opened. The expiry is fixed at creation time and is not extended by activity.
     *
     * @return the expiry instant
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GameSession that)) {
            return false;
        }
        return sessionId.equals(that.sessionId)
                && joinCode.equals(that.joinCode)
                && status == that.status
                && players.equals(that.players)
                && createdAt.equals(that.createdAt)
                && updatedAt.equals(that.updatedAt)
                && expiresAt.equals(that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, joinCode, status, players, createdAt, updatedAt, expiresAt);
    }

    @Override
    public String toString() {
        return "GameSession[sessionId=" + sessionId + ", status=" + status
                + ", players=" + players.size() + ", expiresAt=" + expiresAt + "]";
    }
}
