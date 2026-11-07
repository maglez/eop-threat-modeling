package org.maglez.eop.outbound.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.domain.JoinCodeUnavailableException;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.SeatAlreadyTakenException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotInProgressException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.port.out.SessionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only class that speaks both JPA and the session domain.
 *
 * <p>Spring Data types, JPA entities and {@code DataIntegrityViolationException}
 * all stop here. The use case layer above sees {@link GameSession}, {@link Player}
 * and the domain exceptions, and nothing else.
 *
 * <p>Two responsibilities are worth naming, because they are the reason this class
 * is not a thin pass-through:
 *
 * <p><strong>Transactions.</strong> Each write method is one transaction. The use
 * case layer must stay free of Spring, so the boundary has to be declared out here,
 * and it has to be coarse enough that a read-modify-write cannot be interleaved:
 * seating a player validates the status and inserts the row in a single atomic step.
 *
 * <p><strong>Translating constraint violations into domain outcomes.</strong> A
 * duplicate seat and a duplicate join code are not infrastructure failures that
 * happen to surface as exceptions; they are the expected result of two requests
 * racing, and the application recovers from both by retrying. Letting the database
 * decide the winner is what makes seat assignment correct under concurrency —
 * checking first and inserting second would only narrow the window, not close it.
 */
@Repository
public class SessionRepositoryAdapter implements SessionRepository {

    private static final String SEAT_CONSTRAINT = "uq_player_session_seat";

    private static final String JOIN_CODE_CONSTRAINT = "uq_game_session_join_code";

    private static final String SESSION_ID_REQUIRED = "sessionId is required";

    private static final String OCCURRED_AT_REQUIRED = "occurredAt is required";

    private final GameSessionJpaRepository sessionRows;

    private final PlayerJpaRepository playerRows;

    SessionRepositoryAdapter(final GameSessionJpaRepository sessionRows, final PlayerJpaRepository playerRows) {
        this.sessionRows = Objects.requireNonNull(sessionRows, "sessionRows is required");
        this.playerRows = Objects.requireNonNull(playerRows, "playerRows is required");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GameSession> findById(final UUID sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        return sessionRows.findById(sessionId).map(this::assemble);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GameSession> findByJoinCode(final JoinCode joinCode) {
        Objects.requireNonNull(joinCode, "joinCode is required");
        return sessionRows.findByJoinCode(joinCode.value()).map(this::assemble);
    }

    @Override
    @Transactional
    public void createLobby(final GameSession session) {
        Objects.requireNonNull(session, "session is required");
        try {
            sessionRows.saveAndFlush(GameSessionJpaEntity.fromDomain(session));
            for (final Player seated : session.players()) {
                playerRows.saveAndFlush(PlayerJpaEntity.fromDomain(session.sessionId(), seated));
            }
        }
        catch (final DataIntegrityViolationException collision) {
            if (mentions(collision, JOIN_CODE_CONSTRAINT)) {
                throw new JoinCodeUnavailableException();
            }
            throw collision;
        }
    }

    @Override
    @Transactional
    public void seatPlayer(final UUID sessionId, final Player player, final Instant occurredAt) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(player, "player is required");
        Objects.requireNonNull(occurredAt, OCCURRED_AT_REQUIRED);

        final var touched = sessionRows.touchWhileInStatus(
                sessionId, SessionStatus.LOBBY, occurredAt.atOffset(ZoneOffset.UTC));
        if (touched == 0) {
            throw noLongerInLobby(sessionId);
        }

        try {
            playerRows.saveAndFlush(PlayerJpaEntity.fromDomain(sessionId, player));
        }
        catch (final DataIntegrityViolationException contested) {
            if (mentions(contested, SEAT_CONSTRAINT)) {
                throw new SeatAlreadyTakenException(sessionId, player.seatOrder());
            }
            throw contested;
        }
    }

    @Override
    @Transactional
    public void recordStarted(final UUID sessionId, final Instant occurredAt) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(occurredAt, OCCURRED_AT_REQUIRED);

        final var advanced = sessionRows.advanceStatus(
                sessionId, SessionStatus.LOBBY, SessionStatus.IN_PROGRESS, occurredAt.atOffset(ZoneOffset.UTC));
        if (advanced == 0) {
            throw noLongerInLobby(sessionId);
        }
    }

    @Override
    @Transactional
    public void recordCompleted(final UUID sessionId, final Instant occurredAt) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(occurredAt, OCCURRED_AT_REQUIRED);

        final var advanced = sessionRows.advanceStatus(
                sessionId, SessionStatus.IN_PROGRESS, SessionStatus.COMPLETED, occurredAt.atOffset(ZoneOffset.UTC));
        if (advanced == 0) {
            throw noLongerInProgress(sessionId);
        }
    }

    @Override
    @Transactional
    public void resetToInProgress(final UUID sessionId, final Instant occurredAt) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(occurredAt, OCCURRED_AT_REQUIRED);

        final var reset = sessionRows.resetToInProgress(sessionId, occurredAt.atOffset(ZoneOffset.UTC));
        if (reset == 0) {
            throw noLongerInProgress(sessionId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findExpiredSessionIds(final Instant before) {
        Objects.requireNonNull(before, "before is required");
        return sessionRows.findExpiredSessionIds(before.atOffset(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public void abandonAndDelete(final UUID sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        // Transition to ABANDONED (no-op if already there), then delete.
        // The delete cascades to player rows via fk_player_game_session ON DELETE CASCADE.
        sessionRows.markAbandoned(sessionId);
        sessionRows.deleteById(sessionId);
    }

    /**
     * Rebuilds an aggregate from its row and its seats.
     *
     * <p>Both reads are database reads. No registry, cache or subscriber list
     * contributes, which is what makes the first request after a deployment
     * indistinguishable from any other (ADR-014).
     */
    private GameSession assemble(final GameSessionJpaEntity row) {
        final var seats = playerRows.findByGameSessionIdOrderBySeatOrderAsc(row.getId())
                .stream()
                .map(PlayerJpaEntity::toDomain)
                .toList();
        return row.toDomain(seats);
    }

    /**
     * Explains a conditional update that changed no rows.
     *
     * <p>There are exactly two explanations, and the caller needs to tell them
     * apart: the session is gone, or it has left the lobby. This costs one extra
     * read, on a path that is already failing.
     */
    private RuntimeException noLongerInLobby(final UUID sessionId) {
        return sessionRows.findById(sessionId)
                .map(row -> (RuntimeException) new SessionNotJoinableException(sessionId, row.getStatus()))
                .orElseGet(() -> new SessionNotFoundException(sessionId));
    }

    /**
     * Explains a conditional update that changed no rows when the required state
     * was {@code IN_PROGRESS}.
     *
     * <p>The session is either gone or already past in-progress. The caller
     * needs to tell them apart: gone is a 404, already-completed is a 409.
     */
    private RuntimeException noLongerInProgress(final UUID sessionId) {
        return sessionRows.findById(sessionId)
                .map(row -> (RuntimeException) new SessionNotInProgressException(sessionId, row.getStatus()))
                .orElseGet(() -> new SessionNotFoundException(sessionId));
    }

    /**
     * Reports whether a constraint violation names a particular constraint.
     *
     * <p>Matching on the constraint name rather than on the exception type is
     * deliberate: the type says only that some uniqueness was violated, and this
     * adapter must not translate an unexpected violation into a retryable one.
     * Anything unrecognised is rethrown, so a new constraint fails loudly instead
     * of arriving as a silent retry loop.
     */
    private static boolean mentions(final Throwable failure, final String constraintName) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            final var message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
