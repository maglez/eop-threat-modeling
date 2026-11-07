package org.maglez.eop.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import org.maglez.eop.domain.JoinCode;

import org.maglez.eop.domain.Player;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared FK-chain builders for migration tests that need to insert rows into
 * {@code trick_play} or {@code trick_play_component}.
 *
 * <p>The full FK chain required to insert a {@code trick_play} row is:
 * <pre>
 *   game_session  ← player ← trick_play
 *   game_session  ← trick  ← trick_play
 *   card          ← trick_play
 * </pre>
 * Satisfying this chain requires inserting rows into {@code game_session}, {@code player},
 * {@code card}, and {@code trick} before the play row can be attempted.  That is non-trivial
 * but not disproportionate: the constraints being tested are database-level enforcements, and
 * a test that bypasses the FK chain would be testing a different (weaker) schema than the
 * one that ships.
 *
 * <p>The {@code player} link is the newest of these and the reason this class changed:
 * until {@code fk_trick_play_player_seat} and {@code fk_hand_player_seat} were added
 * (changeset 009, replacing the former single-column {@code fk_trick_play_player} and
 * {@code fk_hand_player}), these fixtures passed a bare {@code UUID.randomUUID()} as
 * {@code player_id}.  That was not merely a shortcut — it meant every constraint test in
 * this package was passing while simultaneously demonstrating that a play could be written
 * by a player who exists in no session at all.  Real {@code player} rows are now inserted,
 * so the fixtures can no longer certify the gap they were written on top of.
 *
 * <p>The composite foreign keys also bind {@code seat_order}: a play or hand whose
 * {@code seat_order} disagrees with the player's own {@code player.seat_order} is rejected
 * by the engine.  {@link #insertTrickPlay} takes seat and player independently, so a caller
 * can construct a seat-inconsistent row without noticing — this is intentional, because the
 * negative tests in {@code TrickPlayForeignKeyTest} need to do exactly that.  Callers that
 * want a valid row should use {@link #insertMinimalTrickPlay} or
 * {@link #insertSeatConsistentTrickPlay}, which create the player and the play at one agreed
 * seat and cannot produce a mismatch.
 *
 * <p>All methods are package-private static utilities.  No Spring context.
 */
final class MigrationTestFixtures {

    private MigrationTestFixtures() {
        // utility class
    }

    /**
     * Inserts a minimal {@code game_session} row and returns its id.
     * Only the NOT NULL columns are populated; nullable columns are omitted.
     * {@code version} has a column default of 0 in the schema but we supply it
     * explicitly to avoid relying on H2's default evaluation order.
     *
     * <p>The join code takes its full width from the row's own UUID rather than
     * padding a fixed prefix, and that width is {@code JoinCode#LENGTH} rather than a
     * literal so this fixture tracks the domain constant instead of having to be found
     * and edited the next time it moves. {@code game_session.join_code} carries
     * {@code uq_game_session_join_code} (003-session-lifecycle.xml:70), so a code
     * built from a fixed prefix plus two hex characters would draw from only 256
     * values and collide across sessions inserted into the same database, where the
     * whole width drawn from the UUID scales as sixteen to the power of the length.
     * No test inserts two sessions per database today, so this is not a live flake —
     * it is removing the trap before someone walks into it.
     *
     * <p>A UUID contributes thirty-two hex characters, which is the ceiling on how far
     * {@code JoinCode#LENGTH} can grow before this derivation needs another source.
     */
    static UUID insertMinimalGameSession(final Connection conn) throws SQLException {
        final UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO game_session (id, join_code, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'LOBBY', NOW(), NOW(), 0)")) {
            ps.setObject(1, id);
            ps.setString(2, id.toString().replace("-", "").substring(0, JoinCode.LENGTH).toUpperCase());
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * Inserts a minimal {@code player} row in the given session at the given seat and
     * returns its id.
     *
     * <p>Two uniqueness constraints on {@code player} shape this method
     * (003-session-lifecycle.xml): {@code uq_player_session_seat} on
     * {@code (game_session_id, seat_order)} means every player this method creates in one
     * session needs a distinct {@code seatOrder}, and
     * {@code uq_player_identity_token_hash} means every player needs a distinct hash.  The
     * hash is built from two random UUIDs so it is exactly the 64 hex characters the column
     * is sized for and is unique per row without a counter.
     *
     * <p>{@code seatOrder} here is the player's seat at the table, stored in
     * {@code player.seat_order}.  The composite foreign keys {@code fk_trick_play_player_seat}
     * and {@code fk_hand_player_seat} (changeset 009) bind a play's or hand's
     * {@code seat_order} to this value: a row claiming a different seat than the player
     * holds is rejected by the engine.
     */
    static UUID insertMinimalPlayer(
            final Connection conn,
            final UUID sessionId,
            final int seatOrder) throws SQLException {
        final UUID id = UUID.randomUUID();
        final String tokenHash =
                (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player (id, game_session_id, display_name, seat_order, player_role, "
                        + "connection_status, identity_token_hash, joined_at) "
                        + "VALUES (?, ?, ?, ?, 'PARTICIPANT', 'CONNECTED', ?, NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, sessionId);
            ps.setString(3, "Player " + seatOrder);
            ps.setInt(4, seatOrder);
            ps.setString(5, tokenHash);
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * Returns the {@code game_session_id} of the given trick.
     *
     * <p>Exists so that a caller holding only a trick id can create a {@code player} in the
     * same session without the fixture signatures having to thread the session id through
     * every call.
     */
    static UUID sessionOfTrick(final Connection conn, final UUID trickId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT game_session_id FROM trick WHERE id = ?")) {
            ps.setObject(1, trickId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("trick %s must exist before a player can be created for its session", trickId)
                        .isTrue();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /**
     * Inserts a {@code player} in the session that owns the given trick and returns its id.
     * Convenience wrapper over {@link #sessionOfTrick} plus {@link #insertMinimalPlayer}.
     */
    static UUID insertPlayerForTrick(
            final Connection conn,
            final UUID trickId,
            final int seatOrder) throws SQLException {
        return insertMinimalPlayer(conn, sessionOfTrick(conn, trickId), seatOrder);
    }

    /**
     * Inserts a {@code player} directly in the given session at the given seat and returns
     * its id.
     *
     * <p>This is a thin alias for {@link #insertMinimalPlayer} with an explicit session id.
     * It exists to make the cross-session scenario in
     * {@code TrickPlayUniqueConstraintTest.secondPlayAtSameSeatByCrossSessionPlayerIsRejected}
     * readable: a caller that wants a player in session B to play into session A's trick
     * should call this method with session B's id, not {@link #insertPlayerForTrick} which
     * always resolves the session from the trick.
     *
     * <p>The existing signature of {@link #insertPlayerForTrick} is unchanged.
     */
    static UUID insertPlayerInSession(
            final Connection conn,
            final UUID sessionId,
            final int seatOrder) throws SQLException {
        return insertMinimalPlayer(conn, sessionId, seatOrder);
    }

    /**
     * Inserts a minimal {@code hand} row and returns its id.
     *
     * <p>{@code hand} has no {@code created_at} column: {@code Hand} holds only
     * {@code handId}, {@code playerId} and {@code cards}, so there is no timestamp any
     * write path could populate.
     */
    static UUID insertMinimalHand(
            final Connection conn,
            final UUID sessionId,
            final UUID playerId,
            final int seatOrder) throws SQLException {
        final UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO hand (id, game_session_id, player_id, seat_order) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, sessionId);
            ps.setObject(3, playerId);
            ps.setInt(4, seatOrder);
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * Inserts a {@code hand_card} row.
     *
     * <p>The table has no ordinal and no surrogate key: {@code (hand_id, card_id)} is both
     * the identity and the no-duplicate-card invariant, because {@code Hand} canonicalises
     * its cards by suit then rank in its constructor and so carries no meaningful order.
     */
    static void insertHandCard(
            final Connection conn,
            final UUID handId,
            final UUID cardId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO hand_card (hand_id, card_id) VALUES (?, ?)")) {
            ps.setObject(1, handId);
            ps.setObject(2, cardId);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the id of any existing {@code card} row.
     * The card table is seeded by migrations 001/002; we never insert a duplicate.
     */    static UUID anyExistingCard(final Connection conn) throws SQLException {
        try (var rs = conn.createStatement().executeQuery("SELECT id FROM card LIMIT 1")) {
            if (rs.next()) {
                return (UUID) rs.getObject(1);
            }
        }
        throw new IllegalStateException(
                "No card rows found — migrations 001/002 must have seeded the card catalogue");
    }

    /**
     * Returns the id of a second existing {@code card} row (different from the first).
     * Used when a test needs two distinct card ids.
     */
    static UUID secondExistingCard(final Connection conn) throws SQLException {
        try (var rs = conn.createStatement().executeQuery("SELECT id FROM card LIMIT 1 OFFSET 1")) {
            if (rs.next()) {
                return (UUID) rs.getObject(1);
            }
        }
        throw new IllegalStateException(
                "Fewer than two card rows found — migrations 001/002 must have seeded at least two cards");
    }

    /**
     * Inserts a minimal {@code trick} row and returns its id.
     * Creates its own {@code game_session} parent row.
     */
    static UUID insertMinimalTrick(final Connection conn) throws SQLException {
        final UUID sessionId = insertMinimalGameSession(conn);
        return insertMinimalTrickInSession(conn, sessionId);
    }

    /**
     * Inserts a minimal {@code trick} row in the given session and returns its id.
     */
    static UUID insertMinimalTrickInSession(final Connection conn, final UUID sessionId) throws SQLException {
        final UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trick (id, game_session_id, sequence, leader_seat) VALUES (?, ?, 1, 0)")) {
            ps.setObject(1, id);
            ps.setObject(2, sessionId);
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * Inserts a minimal {@code trick_play} row with the given seat and card, and returns its id.
     *
     * <p><strong>Warning — seat-inconsistency trap.</strong> This method takes
     * {@code seatOrder} and {@code playerId} independently, so a caller can pass a seat that
     * disagrees with the player's own {@code player.seat_order}.  The composite foreign key
     * {@code fk_trick_play_player_seat} (changeset 009) will then reject the insert with SQL
     * state {@code 23506}.  This is intentional: the negative tests in
     * {@code TrickPlayForeignKeyTest} need to construct exactly such a row to prove the
     * constraint fires.  Callers that want a valid row should use
     * {@link #insertMinimalTrickPlay} or {@link #insertSeatConsistentTrickPlay} instead.
     */
    static UUID insertTrickPlay(
            final Connection conn,
            final UUID trickId,
            final UUID cardId,
            final int seatOrder,
            final UUID playerId) throws SQLException {
        final UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trick_play (id, trick_id, player_id, seat_order, card_id, threat_linked, played_at) "
                        + "VALUES (?, ?, ?, ?, ?, false, NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, trickId);
            ps.setObject(3, playerId);
            ps.setInt(4, seatOrder);
            ps.setObject(5, cardId);
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * Inserts a minimal {@code trick_play} row at seat 0 by a freshly created player in the
     * trick's own session, and returns its id.  Convenience overload for tests that need one
     * play and do not care which seat or player it belongs to.
     *
     * <p>This used to pass {@code UUID.randomUUID()} as {@code player_id}.  It cannot any
     * more: {@code fk_trick_play_player_seat} rejects a player that does not exist, which is
     * the point of adding it.
     */
    static UUID insertMinimalTrickPlay(
            final Connection conn,
            final UUID trickId,
            final UUID cardId) throws SQLException {
        return insertTrickPlay(conn, trickId, cardId, 0, insertPlayerForTrick(conn, trickId, 0));
    }

    /**
     * Creates a fresh player at the given seat in the trick's session, then inserts a
     * {@code trick_play} row for that player at that same seat, and returns the play id.
     *
     * <p>This helper cannot produce a seat-inconsistent row: the player is created at
     * {@code seatOrder} and the play is written at {@code seatOrder}, so
     * {@code fk_trick_play_player_seat} is always satisfied.  Use this method from tests
     * that want a valid row and do not need to control which player or seat is used.
     *
     * <p>Contrast with {@link #insertTrickPlay}, which takes seat and player independently
     * and can produce a row the schema will reject — that is intentional so the negative
     * tests in {@code TrickPlayForeignKeyTest} can exist.
     */
    static UUID insertSeatConsistentTrickPlay(
            final Connection conn,
            final UUID trickId,
            final UUID cardId,
            final int seatOrder) throws SQLException {
        final UUID playerId = insertPlayerForTrick(conn, trickId, seatOrder);
        return insertTrickPlay(conn, trickId, cardId, seatOrder, playerId);
    }

    /**
     * Inserts a {@code trick_play_component} row.
     * Throws {@link java.sql.SQLException} if the database rejects it.
     */
    static void insertTrickPlayComponent(
            final Connection conn,
            final UUID trickPlayId,
            final int ordinal,
            final String componentName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trick_play_component (trick_play_id, ordinal, component_name) VALUES (?, ?, ?)")) {
            ps.setObject(1, trickPlayId);
            ps.setInt(2, ordinal);
            ps.setString(3, componentName);
            ps.executeUpdate();
        }
    }

    /**
     * Counts {@code trick_play} rows matching the given {@code trick_id} and {@code seat_order}.
     */
    static int countTrickPlaysBySeat(
            final Connection conn,
            final UUID trickId,
            final int seatOrder) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM trick_play WHERE trick_id = ? AND seat_order = ?")) {
            ps.setObject(1, trickId);
            ps.setInt(2, seatOrder);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }
}
