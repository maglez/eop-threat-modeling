package org.maglez.eop.application.port.out;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.domain.JoinCodeUnavailableException;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.SeatAlreadyTakenException;
import org.maglez.eop.domain.SessionNotInProgressException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.port.in.ResolveTrickPort;

/**
 * In-memory stand-in for the session port.
 *
 * <p>Hand written rather than mocked, for the same reason as
 * {@link InMemoryCardRepository}: the interesting behaviour of the session use
 * cases is what they do when a write is <em>rejected</em>, and a fake that really
 * stores sessions can rehearse that honestly. A mock would only replay whatever
 * the test told it to, which proves nothing about the retry actually re-reading
 * the session and choosing a different seat.
 *
 * <p>The rejections mirror the real adapter: a duplicate join code raises
 * {@link JoinCodeUnavailableException}, a session that has left the lobby raises
 * {@link SessionNotJoinableException}, and a missing one raises
 * {@link SessionNotFoundException}. Contested seats have to be armed explicitly
 * because nothing in a single-threaded test would otherwise produce that race.
 */
public final class InMemorySessionRepository implements SessionRepository {

    private final Map<UUID, GameSession> sessions = new LinkedHashMap<>();
    private final List<String> interactions;

    private int joinCodeRejections;
    private Player seatRival;
    private boolean everySeatRefused;
    private int createLobbyCalls;
    private int seatPlayerCalls;
    private int recordStartedCalls;
    private int recordCompletedCalls;

    public InMemorySessionRepository(final GameSession... seed) {
        this(new ArrayList<>(), seed);
    }

    /**
     * Creates the fake sharing an interaction log with another fake, so that a test
     * can assert the order of calls made to two different ports.
     *
     * @param interactions the log to append port names to
     * @param seed sessions already present before the use case runs
     */
    public InMemorySessionRepository(final List<String> interactions, final GameSession... seed) {
        this.interactions = interactions;
        for (final GameSession session : seed) {
            sessions.put(session.sessionId(), session);
        }
    }

    @Override
    public Optional<GameSession> findById(final UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public Optional<GameSession> findByJoinCode(final JoinCode joinCode) {
        return sessions.values().stream()
                .filter(session -> session.joinCode().equals(joinCode))
                .findFirst();
    }

    @Override
    public void createLobby(final GameSession session) {
        createLobbyCalls++;
        interactions.add("createLobby");
        if (joinCodeRejections > 0) {
            joinCodeRejections--;
            throw new JoinCodeUnavailableException();
        }
        if (findByJoinCode(session.joinCode()).isPresent()) {
            throw new JoinCodeUnavailableException();
        }
        sessions.put(session.sessionId(), session);
    }

    @Override
    public void seatPlayer(final UUID sessionId, final Player player, final Instant occurredAt) {
        seatPlayerCalls++;
        interactions.add("seatPlayer");
        final var current = requireLobby(sessionId);
        if (everySeatRefused) {
            throw new SeatAlreadyTakenException(sessionId, player.seatOrder());
        }
        if (seatRival != null) {
            final var rival = seatRival;
            seatRival = null;
            sessions.put(sessionId, withPlayer(current, rival, occurredAt));
            throw new SeatAlreadyTakenException(sessionId, player.seatOrder());
        }
        sessions.put(sessionId, withPlayer(current, player, occurredAt));
    }

    @Override
    public void recordStarted(final UUID sessionId, final Instant occurredAt) {
        recordStartedCalls++;
        interactions.add("recordStarted");
        final var current = requireLobby(sessionId);
        sessions.put(sessionId, GameSession.reconstitute(
                current.sessionId(),
                current.joinCode(),
                SessionStatus.IN_PROGRESS,
                current.players(),
                current.createdAt(),
                occurredAt,
                current.expiresAt()));
    }

    @Override
    public void recordCompleted(final UUID sessionId, final Instant occurredAt) {
        recordCompletedCalls++;
        interactions.add("recordCompleted");
        final var current = requireInProgress(sessionId);
        sessions.put(sessionId, GameSession.reconstitute(
                current.sessionId(),
                current.joinCode(),
                SessionStatus.COMPLETED,
                current.players(),
                current.createdAt(),
                occurredAt,
                current.expiresAt()));
    }

    @Override
    public void resetToInProgress(final UUID sessionId, final Instant occurredAt) {
        interactions.add("resetToInProgress");
        final var current = sessions.get(sessionId);
        if (current == null) {
            throw new SessionNotFoundException(sessionId);
        }
        if (current.status() != SessionStatus.COMPLETED) {
            throw new SessionNotInProgressException(sessionId, current.status());
        }
        sessions.put(sessionId, GameSession.reconstitute(
                current.sessionId(),
                current.joinCode(),
                SessionStatus.IN_PROGRESS,
                current.players(),
                current.createdAt(),
                occurredAt,
                current.expiresAt()));
    }

    /**
     * Arms the next writes to be rejected as duplicate join codes.
     *
     * @param count how many consecutive attempts are refused
     */
    public void rejectNextJoinCodes(final int count) {
        this.joinCodeRejections = count;
    }

    /**
     * Arms one lost seat race: the next seat claim is refused and the rival is
     * seated instead, so that a re-read sees the seat genuinely taken.
     *
     * @param rival the player who wins the race
     */
    public void loseNextSeatRaceTo(final Player rival) {
        this.seatRival = rival;
    }

    /**
     * Refuses every seat claim, which is not a state the real database can reach but
     * is the only way to walk the attempt budget to its end.
     */
    public void refuseEverySeat() {
        this.everySeatRefused = true;
    }

    /**
     * Directly marks the session as COMPLETED without going through the CAS check.
     *
     * <p>Use this to simulate a concurrent facilitator call winning the race before
     * the auto-complete branch in {@link ResolveTrickPort} reaches
     * {@code recordCompleted}. The next call to {@code recordCompleted} for this
     * session will then throw {@link SessionNotInProgressException}, which the use
     * case must swallow.
     *
     * @param sessionId the session to force-complete
     * @param occurredAt the timestamp to record as the completion moment
     */
    public void forceComplete(final UUID sessionId, final Instant occurredAt) {
        final var current = sessions.get(sessionId);
        if (current == null) {
            throw new SessionNotFoundException(sessionId);
        }
        sessions.put(sessionId, GameSession.reconstitute(
                current.sessionId(),
                current.joinCode(),
                SessionStatus.COMPLETED,
                current.players(),
                current.createdAt(),
                occurredAt,
                current.expiresAt()));
    }

    /**
     * @return how many times a lobby insert was attempted
     */
    public int createLobbyCalls() {
        return createLobbyCalls;
    }

    /**
     * @return how many times a seat was claimed
     */
    public int seatPlayerCalls() {
        return seatPlayerCalls;
    }

    /**
     * @return how many times a start was recorded
     */
    public int recordStartedCalls() {
        return recordStartedCalls;
    }

    /**
     * @return how many times a completion was recorded
     */
    public int recordCompletedCalls() {
        return recordCompletedCalls;
    }

    /**
     * @return the names of the port methods called, in order
     */
    public List<String> interactions() {
        return List.copyOf(interactions);
    }

    private GameSession requireLobby(final UUID sessionId) {
        final var current = sessions.get(sessionId);
        if (current == null) {
            throw new SessionNotFoundException(sessionId);
        }
        if (current.status() != SessionStatus.LOBBY) {
            throw new SessionNotJoinableException(sessionId, current.status());
        }
        return current;
    }

    private GameSession requireInProgress(final UUID sessionId) {
        final var current = sessions.get(sessionId);
        if (current == null) {
            throw new SessionNotFoundException(sessionId);
        }
        if (current.status() != SessionStatus.IN_PROGRESS) {
            throw new SessionNotInProgressException(sessionId, current.status());
        }
        return current;
    }

    private static GameSession withPlayer(final GameSession current, final Player player, final Instant occurredAt) {
        final List<Player> seated = new ArrayList<>(current.players());
        seated.add(player);
        return GameSession.reconstitute(
                current.sessionId(),
                current.joinCode(),
                current.status(),
                seated,
                current.createdAt(),
                occurredAt,
                current.expiresAt());
    }

    @Override
    public List<UUID> findExpiredSessionIds(final Instant before) {
        return sessions.values().stream()
                .filter(s -> s.expiresAt().isBefore(before))
                .map(GameSession::sessionId)
                .toList();
    }

    @Override
    public void abandonAndDelete(final UUID sessionId) {
        sessions.remove(sessionId);
    }
}
