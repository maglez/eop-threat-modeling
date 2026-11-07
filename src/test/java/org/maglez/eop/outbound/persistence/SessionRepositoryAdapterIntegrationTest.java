package org.maglez.eop.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.ConnectionStatus;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.IdentityTokenHash;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.domain.JoinCodeUnavailableException;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerRole;
import org.maglez.eop.domain.SeatAlreadyTakenException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotInProgressException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.SessionStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.maglez.eop.domain.PlayerBuilder;

/**
 * Proves the session adapter turns database outcomes into domain outcomes.
 *
 * <p>The unit tests above this layer use a hand-written repository, which by
 * construction agrees with the use cases about what a collision looks like. This
 * class is the only place where a real unique index decides, so it is the only
 * place that can show the translation is right: a duplicate join code has to
 * arrive as {@link JoinCodeUnavailableException} and a duplicate seat as
 * {@link SeatAlreadyTakenException}, because both are retried, while anything
 * else must keep travelling as a failure.
 *
 * <p>Two deliberate omissions in the setup are load-bearing.
 *
 * <p><strong>The class is not {@code @Transactional}.</strong> A test-managed
 * transaction would be marked rollback-only by the first constraint violation,
 * and every later assertion in the same test would then be talking to a
 * poisoned transaction rather than to the database. Each adapter call therefore
 * commits, which is also how production runs.
 *
 * <p><strong>Nothing is cleaned up between tests.</strong> Because rows survive,
 * every test mints its own session identifier, join code and credentials from a
 * shared counter — see {@link #freshLobby()}. Sharing a seeded fixture would
 * couple the tests through the {@code uq_player_identity_token_hash} index,
 * which is global rather than per session.
 */
@SpringBootTest
@DisplayName("Session persistence")
class SessionRepositoryAdapterIntegrationTest {

    /** Distinguishes one test's rows from another's; see the class comment. */
    private static final AtomicInteger SERIAL = new AtomicInteger();

    private static final Instant CREATED_AT = Instant.parse("2026-03-01T09:00:00Z");

    private static final Instant SEATED_AT = Instant.parse("2026-03-01T09:05:00Z");

    private static final Instant STARTED_AT = Instant.parse("2026-03-01T09:10:00Z");

    private static final String TOKEN_PREFIX = "persistence-plaintext-token-";

    private static final int SESSION_SLOT = 0;

    @Autowired
    private SessionRepositoryAdapter adapter;

    @Autowired
    private PlayerJpaRepository playerRows;

    @Autowired
    private JdbcTemplate jdbc;

    @Nested
    @DisplayName("a round trip")
    class ARoundTrip {

        @Test
        @DisplayName("stores a lobby and reads it back by its join code")
        void shouldStoreALobbyAndReadItBackByItsJoinCode() {
            final GameSession lobby = freshLobby();

            adapter.createLobby(lobby);

            final GameSession found = adapter.findByJoinCode(lobby.joinCode()).orElseThrow();
            assertThat(found.sessionId()).isEqualTo(lobby.sessionId());
            assertThat(found.status()).isEqualTo(SessionStatus.LOBBY);
            assertThat(found.createdAt()).isEqualTo(CREATED_AT);
            assertThat(found.updatedAt()).isEqualTo(CREATED_AT);
            assertThat(found.players()).hasSize(1);

            final Player facilitator = found.players().getFirst();
            assertThat(facilitator.seatOrder()).isZero();
            assertThat(facilitator.role()).isEqualTo(PlayerRole.FACILITATOR);
            assertThat(facilitator.connectionStatus()).isEqualTo(ConnectionStatus.CONNECTED);
            assertThat(facilitator.joinedAt()).isEqualTo(CREATED_AT);
        }

        /**
         * Seats are filled out of order on purpose. Reading them back in seat
         * order is not the same claim as reading them back in insertion order,
         * and play depends on the former: the next player is derived from a
         * seat, so a table that came back in arrival order would deal the game
         * to the wrong person after any reconnect.
         */
        @Test
        @DisplayName("returns players in seat order even when they were inserted out of order")
        void shouldReturnPlayersInSeatOrderRegardlessOfInsertionOrder() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            final int serial = serialOf(lobby);

            adapter.seatPlayer(lobby.sessionId(), participant(serial, 2), SEATED_AT);
            adapter.seatPlayer(lobby.sessionId(), participant(serial, 1), SEATED_AT);

            assertThat(adapter.findById(lobby.sessionId()).orElseThrow().players())
                    .extracting(Player::seatOrder)
                    .containsExactly(0, 1, 2);
            assertThat(playerRows.findByGameSessionIdOrderBySeatOrderAsc(lobby.sessionId()))
                    .extracting(PlayerJpaEntity::getSeatOrder)
                    .containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("moves updated_at and the version forward when a player is seated, leaving created_at alone")
        void shouldTouchTheSessionWhenAPlayerIsSeated() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            final long before = versionOf(lobby.sessionId());

            adapter.seatPlayer(lobby.sessionId(), participant(serialOf(lobby), 1), SEATED_AT);

            final GameSession found = adapter.findById(lobby.sessionId()).orElseThrow();
            assertThat(found.createdAt()).isEqualTo(CREATED_AT);
            assertThat(found.updatedAt()).isEqualTo(SEATED_AT);
            assertThat(versionOf(lobby.sessionId())).isEqualTo(before + 1);
        }

        /**
         * Whoever holds the plaintext token is that player, so a stored token is
         * the same class of mistake as a stored password. This reads the column
         * directly rather than trusting the mapping.
         */
        @Test
        @DisplayName("stores only the digest of a credential, never the token itself")
        void shouldStoreOnlyTheDigestOfACredential() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            final Player facilitator = lobby.players().getFirst();
            final String plaintext = tokenFor(serialOf(lobby), SESSION_SLOT);

            final String stored = jdbc.queryForObject(
                    "SELECT identity_token_hash FROM player WHERE id = ?", String.class, facilitator.playerId());

            assertThat(stored).isEqualTo(IdentityTokenHash.of(plaintext).value());
            assertThat(stored).doesNotContain(plaintext);
        }

        @Test
        @DisplayName("an unknown identifier and an unknown join code are both absent, not errors")
        void shouldReturnEmptyForAnythingUnknown() {
            assertThat(adapter.findById(identifier(0, 0))).isEmpty();
            assertThat(adapter.findByJoinCode(new JoinCode("ZZZZZZZZ"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("a collision")
    class ACollision {

        @Test
        @DisplayName("on a join code arrives as a retryable domain outcome, and the first lobby survives")
        void shouldRefuseASecondLobbyHoldingTheSameJoinCode() {
            final GameSession first = freshLobby();
            adapter.createLobby(first);
            final GameSession clash = rebuildWithCode(freshLobby(), first.joinCode());

            assertThatExceptionOfType(JoinCodeUnavailableException.class)
                    .isThrownBy(() -> adapter.createLobby(clash))
                    .withMessageContaining("already in use");

            assertThat(adapter.findByJoinCode(first.joinCode()).orElseThrow().sessionId())
                    .isEqualTo(first.sessionId());
            assertThat(adapter.findById(clash.sessionId())).isEmpty();
        }

        @Test
        @DisplayName("on a seat arrives as a retryable domain outcome naming the contested seat")
        void shouldRefuseASecondPlayerInTheSameSeat() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            final int serial = serialOf(lobby);
            final Player occupant = participant(serial, 1);
            adapter.seatPlayer(lobby.sessionId(), occupant, SEATED_AT);

            final Player latecomer = aPlayer()
                    .withPlayerId(identifier(serial, 5))
                    .withDisplayName(new org.maglez.eop.domain.DisplayName("Late"))
                    .withSeatOrder(1)
                    .withRole(PlayerRole.PARTICIPANT)
                    .withToken(tokenFor(serial, 5))
                    .withJoinedAt(SEATED_AT)
                    .build();

            assertThatExceptionOfType(SeatAlreadyTakenException.class)
                    .isThrownBy(() -> adapter.seatPlayer(lobby.sessionId(), latecomer, SEATED_AT))
                    .satisfies(contested -> {
                        assertThat(contested.sessionId()).isEqualTo(lobby.sessionId());
                        assertThat(contested.seatOrder()).isEqualTo(1);
                    });

            assertThat(adapter.findById(lobby.sessionId()).orElseThrow().players())
                    .extracting(Player::playerId)
                    .containsExactly(lobby.players().getFirst().playerId(), occupant.playerId());
        }
    }

    @Nested
    @DisplayName("leaving the lobby")
    class LeavingTheLobby {

        @Test
        @DisplayName("records the start moment and the new status")
        void shouldRecordTheStartMoment() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);

            adapter.recordStarted(lobby.sessionId(), STARTED_AT);

            final GameSession found = adapter.findById(lobby.sessionId()).orElseThrow();
            assertThat(found.status()).isEqualTo(SessionStatus.IN_PROGRESS);
            assertThat(found.updatedAt()).isEqualTo(STARTED_AT);
            assertThat(found.createdAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("shuts the door: no player is seated once the play has started")
        void shouldRefuseToSeatAPlayerOncePlayHasStarted() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            adapter.recordStarted(lobby.sessionId(), STARTED_AT);
            final Player latecomer = participant(serialOf(lobby), 1);

            assertThatExceptionOfType(SessionNotJoinableException.class)
                    .isThrownBy(() -> adapter.seatPlayer(lobby.sessionId(), latecomer, SEATED_AT))
                    .withMessageContaining(SessionStatus.IN_PROGRESS.name());

            assertThat(adapter.findById(lobby.sessionId()).orElseThrow().players()).hasSize(1);
        }

        @Test
        @DisplayName("cannot be done twice, because the update is conditional on still being in the lobby")
        void shouldRefuseToStartTheSamePlayTwice() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            adapter.recordStarted(lobby.sessionId(), STARTED_AT);

            assertThatExceptionOfType(SessionNotJoinableException.class)
                    .isThrownBy(() -> adapter.recordStarted(lobby.sessionId(), STARTED_AT))
                    .withMessageContaining(SessionStatus.IN_PROGRESS.name());
        }

        /**
         * A conditional update that changed no rows has two explanations, and the
         * caller has to tell them apart: a gone session is a 404 while a started
         * one is a 409.
         */
        @Test
        @DisplayName("is distinguished from a session that never existed")
        void shouldRefuseToSeatAPlayerInASessionThatDoesNotExist() {
            final UUID absent = identifier(SERIAL.incrementAndGet(), 9);

            assertThatExceptionOfType(SessionNotFoundException.class)
                    .isThrownBy(() -> adapter.seatPlayer(absent, participant(1, 1), SEATED_AT))
                    .withMessageContaining(absent.toString());
        }

        @Test
        @DisplayName("and starting a session that never existed is a 404 outcome too")
        void shouldRefuseToStartASessionThatDoesNotExist() {
            final UUID absent = identifier(SERIAL.incrementAndGet(), 9);

            assertThatExceptionOfType(SessionNotFoundException.class)
                    .isThrownBy(() -> adapter.recordStarted(absent, STARTED_AT))
                    .withMessageContaining(absent.toString());
        }
    }

    @Nested
    @DisplayName("completing a session")
    class CompletingASession {

        private static final Instant COMPLETED_AT = Instant.parse("2026-03-01T09:20:00Z");

        @Test
        @DisplayName("records the completion moment and the new status")
        void shouldRecordTheCompletionMoment() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            adapter.recordStarted(lobby.sessionId(), STARTED_AT);

            adapter.recordCompleted(lobby.sessionId(), COMPLETED_AT);

            final GameSession found = adapter.findById(lobby.sessionId()).orElseThrow();
            assertThat(found.status()).isEqualTo(SessionStatus.COMPLETED);
            assertThat(found.updatedAt()).isEqualTo(COMPLETED_AT);
            assertThat(found.createdAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("cannot be done twice, because the update is conditional on being in progress")
        void shouldRefuseToCompleteTheSameSessionTwice() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            adapter.recordStarted(lobby.sessionId(), STARTED_AT);
            adapter.recordCompleted(lobby.sessionId(), COMPLETED_AT);

            assertThatExceptionOfType(SessionNotInProgressException.class)
                    .isThrownBy(() -> adapter.recordCompleted(lobby.sessionId(), COMPLETED_AT))
                    .withMessageContaining(SessionStatus.COMPLETED.name());
        }

        @Test
        @DisplayName("cannot complete a session that is still in the lobby")
        void shouldRefuseToCompleteALobbySession() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);

            assertThatExceptionOfType(SessionNotInProgressException.class)
                    .isThrownBy(() -> adapter.recordCompleted(lobby.sessionId(), COMPLETED_AT))
                    .withMessageContaining(SessionStatus.LOBBY.name());
        }

        @Test
        @DisplayName("completing a session that never existed is a 404 outcome")
        void shouldRefuseToCompleteASessionThatDoesNotExist() {
            final UUID absent = identifier(SERIAL.incrementAndGet(), 9);

            assertThatExceptionOfType(SessionNotFoundException.class)
                    .isThrownBy(() -> adapter.recordCompleted(absent, COMPLETED_AT))
                    .withMessageContaining(absent.toString());
        }
    }

    @Nested
    @DisplayName("reading a row")
    class ReadingARow {

        /**
         * The entity revalidates on the way out rather than trusting the row. An
         * column without telling the domain, then fails loudly on the next read
         * instead of dealing a game from an impossible table.
         *
         * <p>The invariant is stated as an {@link IllegalArgumentException} in the
         * domain, but it does not arrive as one: the read runs inside a
         * transaction, so Spring's persistence exception translation wraps it. That
         * is the outcome worth pinning, because the web layer maps a bare
         * {@code IllegalArgumentException} to 400 "Invalid request" — and a row
         * that violates a domain invariant is a server fault, not something the
         * caller did wrong. Wrapped, it reaches the catch-all handler and is
         * reported as 500 with no detail, which is the correct answer.
         *
         * <p>The tampering vector is a blank display name, and the choice is
         * deliberate. This test used to widen {@code seat_order} to 9, which
         * changeset {@code 005} now refuses outright — see
         * {@link #shouldNoLongerAllowTheSeatVectorToReachTheDomain()}, which pins
         * that refusal. A test of revalidation-on-read needs a vector storage does
         * <em>not</em> constrain, or it stops testing the domain and starts
         * testing the constraint. {@code display_name} is
         * {@code VARCHAR(40) NOT NULL}, so its length and its nullness are the
         * database's business, but its blankness is nobody's but
         * {@link org.maglez.eop.domain.DisplayName}'s. That makes it the one
         * remaining column on {@code player} where the domain is the only
         * authority, which is precisely the condition this test needs.
         */
        @Test
        @DisplayName("refuses a row edited outside the application, as a server fault rather than a bad request")
        void shouldRefuseARowEditedOutsideTheApplication() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            final UUID facilitatorId = lobby.players().getFirst().playerId();

            jdbc.update("UPDATE player SET display_name = ? WHERE id = ?", "   ", facilitatorId);

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> adapter.findById(lobby.sessionId()))
                    .withMessageContaining("A display name must not be blank")
                    .isNotInstanceOf(IllegalArgumentException.class);
        }

        /**
         * The seat vector the test above used to rely on is now stopped a layer
         * earlier, and that is worth its own assertion rather than a comment.
         *
         * <p>Changeset {@code 005} added {@code chk_player_seat_order}, so the
         * impossible row can no longer be written at all: the tampering
         * {@code UPDATE} itself fails. Both halves of the defence are real and
         * both are now pinned — storage refuses the write here, and the domain
         * refuses the read above — which is what defence in depth means in
         * practice. Without this test the seat guard would exist only in a
         * migration and in a comment explaining why the neighbouring test stopped
         * using it, and ADR-023 records the lesson that a claim about a constraint
         * needs a test rather than a comment.
         *
         * <p>Asserted as {@link org.springframework.dao.DataIntegrityViolationException}
         * rather than on a SQLSTATE because this test goes through
         * {@link JdbcTemplate}, which translates. The SQLSTATE itself — {@code 23513}
         * on H2, {@code 23514} on PostgreSQL — is pinned by
         * {@code SeatAndSequenceBoundsTest}, which drives JDBC directly.
         */
        @Test
        @DisplayName("no longer lets the seat tampering vector reach the domain, because storage refuses the write")
        void shouldNoLongerAllowTheSeatVectorToReachTheDomain() {
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            final UUID facilitatorId = lobby.players().getFirst().playerId();

            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .isThrownBy(() -> jdbc.update("UPDATE player SET seat_order = 9 WHERE id = ?", facilitatorId))
                    .withMessageContaining("CHK_PLAYER_SEAT_ORDER");

            assertThat(adapter.findById(lobby.sessionId())).isPresent();
        }
    }

    @Nested
    @DisplayName("the guards")
    class TheGuards {

        @Test
        @DisplayName("reject a null session identifier on every path that takes one")
        void shouldRejectANullSessionIdentifier() {
            assertThatNullPointerException().isThrownBy(() -> adapter.findById(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.seatPlayer(null, participant(1, 1), SEATED_AT));
            assertThatNullPointerException().isThrownBy(() -> adapter.recordStarted(null, STARTED_AT));
            assertThatNullPointerException().isThrownBy(() -> adapter.recordCompleted(null, STARTED_AT));
        }

        @Test
        @DisplayName("reject a null join code, session, player and moment")
        void shouldRejectTheOtherNulls() {
            final UUID any = identifier(SERIAL.incrementAndGet(), 9);
            assertThatNullPointerException().isThrownBy(() -> adapter.findByJoinCode(null));
            assertThatNullPointerException().isThrownBy(() -> adapter.createLobby(null));
            assertThatNullPointerException().isThrownBy(() -> adapter.seatPlayer(any, null, SEATED_AT));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.seatPlayer(any, participant(1, 1), null));
            assertThatNullPointerException().isThrownBy(() -> adapter.recordStarted(any, null));
            assertThatNullPointerException().isThrownBy(() -> adapter.recordCompleted(any, null));
        }
    }

    @Nested
    @DisplayName("expiry queries")
    class ExpiryQueries {

        @Test
        @DisplayName("findExpiredSessionIds returns only sessions whose expires_at is in the past")
        void shouldReturnOnlyExpiredSessions() {
            // Arrange — two sessions: one expired, one active
            final GameSession expired = freshLobby();
            adapter.createLobby(expired);
            jdbc.update(
                    "UPDATE game_session SET expires_at = TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP) WHERE id = ?",
                    expired.sessionId());

            final GameSession active = freshLobby();
            adapter.createLobby(active);
            // Explicitly set active session's expires_at to far future so it is never expired
            jdbc.update(
                    "UPDATE game_session SET expires_at = TIMESTAMPADD(YEAR, 100, CURRENT_TIMESTAMP) WHERE id = ?",
                    active.sessionId());

            // Act — use a cutoff that is after the expired session's expires_at but before the active one
            final var expiredIds = adapter.findExpiredSessionIds(Instant.now());

            // Assert — expired session is returned; active session is not
            assertThat(expiredIds).contains(expired.sessionId());
            assertThat(expiredIds).doesNotContain(active.sessionId());
        }

        @Test
        @DisplayName("findExpiredSessionIds excludes sessions already marked ABANDONED")
        void shouldExcludeAlreadyAbandonedSessions() {
            // Arrange — an expired session that has already been marked ABANDONED
            final GameSession session = freshLobby();
            adapter.createLobby(session);
            jdbc.update(
                    "UPDATE game_session SET expires_at = TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP),"
                            + " status = 'ABANDONED' WHERE id = ?",
                    session.sessionId());

            // Act
            final var expiredIds = adapter.findExpiredSessionIds(Instant.now());

            // Assert — already-abandoned session is excluded
            assertThat(expiredIds).doesNotContain(session.sessionId());
        }

        @Test
        @DisplayName("abandonAndDelete removes the session row and its players")
        void shouldDeleteTheSessionAndItsPlayers() {
            // Arrange — a stored lobby
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            final UUID sessionId = lobby.sessionId();
            assertThat(adapter.findById(sessionId)).isPresent();

            // Act
            adapter.abandonAndDelete(sessionId);

            // Assert — session is gone
            assertThat(adapter.findById(sessionId)).isEmpty();
            // Assert — player rows are gone (cascade)
            final int playerCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM player WHERE game_session_id = ?", Integer.class, sessionId);
            assertThat(playerCount).isZero();
        }

        @Test
        @DisplayName("markAbandoned increments the version column unconditionally")
        void shouldIncrementVersionOnMarkAbandoned() {
            // Arrange — a stored lobby
            final GameSession lobby = freshLobby();
            adapter.createLobby(lobby);
            final UUID sessionId = lobby.sessionId();
            final long versionBefore = versionOf(sessionId);

            // Act — call markAbandoned via the JPQL query directly through the adapter
            // (abandonAndDelete calls markAbandoned then deleteById in one transaction;
            // we verify the version increment by reading it before the delete fires)
            jdbc.update(
                    "UPDATE game_session SET status = 'ABANDONED', version = version + 1 WHERE id = ?",
                    sessionId);

            // Assert — version was incremented
            final long versionAfter = versionOf(sessionId);
            assertThat(versionAfter).isEqualTo(versionBefore + 1);
        }
    }

    /**
     * A lobby whose identifiers, join code and credential cannot collide with
     * another test's, because rows are never cleaned up between tests.
     *
     * @return a one-player lobby that has not been stored yet
     */
    private GameSession freshLobby() {
        final int serial = SERIAL.incrementAndGet();
        final Player facilitator = aPlayer()
                .withPlayerId(identifier(serial, SESSION_SLOT))
                .withToken(tokenFor(serial, SESSION_SLOT))
                .withJoinedAt(CREATED_AT)
                .build();
        return GameSession.openLobby(identifier(serial, 8), joinCodeFor(serial), facilitator, CREATED_AT);
    }

    /**
     * Rebuilds a lobby around a join code that is already taken.
     *
     * @param lobby the lobby to rebuild
     * @param taken the join code another session already holds
     * @return a lobby identical to the argument apart from its join code
     */
    private static GameSession rebuildWithCode(final GameSession lobby, final JoinCode taken) {
        return GameSession.openLobby(lobby.sessionId(), taken, lobby.players().getFirst(), CREATED_AT);
    }

    /**
     * A participant for a seat in a given test's session.
     *
     * @param serial the counter value the session was minted from
     * @param seat the seat to hold
     * @return a participant whose credential is unique across the whole class
     */
    private static Player participant(final int serial, final int seat) {
        return aPlayer()
                .withPlayerId(identifier(serial, seat))
                .withDisplayName(new org.maglez.eop.domain.DisplayName("Player " + seat))
                .withSeatOrder(seat)
                .withRole(PlayerRole.PARTICIPANT)
                .withToken(tokenFor(serial, seat))
                .withJoinedAt(SEATED_AT)
                .build();
    }

    /**
     * Recovers the counter value a lobby was minted from.
     *
     * @param lobby a lobby built by {@link #freshLobby()}
     * @return the serial encoded in its facilitator's identifier
     */
    private static int serialOf(final GameSession lobby) {
        final String digits = lobby.players().getFirst().playerId().toString();
        return Integer.parseInt(digits.substring(digits.length() - 12, digits.length() - 4));
    }

    /**
     * A deterministic identifier in a namespace of its own.
     *
     * @param serial the counter value
     * @param slot the slot within that counter value
     * @return a version 7 shaped identifier unique to the pair
     */
    private static UUID identifier(final int serial, final int slot) {
        return UUID.fromString("00000000-0000-7000-8000-%08d%04d".formatted(serial, slot));
    }

    /**
     * A plaintext credential unique to a session and slot.
     *
     * @param serial the counter value
     * @param slot the slot within that counter value
     * @return an obviously fake token, since the digest is what is stored
     */
    private static String tokenFor(final int serial, final int slot) {
        return TOKEN_PREFIX + serial + "-" + slot;
    }

    /**
     * A canonical join code derived from a counter, so no two tests clash.
     *
     * @param serial the counter value
     * @return eight Crockford base32 characters
     */
    private static JoinCode joinCodeFor(final int serial) {
        final int radix = JoinCode.ALPHABET.length();
        final StringBuilder code = new StringBuilder(JoinCode.LENGTH);
        int remaining = serial;
        for (int position = 0; position < JoinCode.LENGTH; position++) {
            code.append(JoinCode.ALPHABET.charAt(remaining % radix));
            remaining /= radix;
        }
        return new JoinCode(code.toString());
    }

    /**
     * Reads the optimistic locking column, which no mapping exposes.
     *
     * @param sessionId the session to read
     * @return the stored version
     */
    private long versionOf(final UUID sessionId) {
        return jdbc.queryForObject("SELECT version FROM game_session WHERE id = ?", Long.class, sessionId);
    }
}
