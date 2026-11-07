package org.maglez.eop.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.GameSession;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the three range CHECK constraints added by
 * {@code 005-seat-and-sequence-bounds.xml} are enforced by the database engine.
 *
 * <p>This class exists because of a measurement, not a hypothesis. While reviewing Slice B,
 * @security-auditor inserted {@code player.seat_order = -7}, {@code trick.sequence = -5} and
 * {@code game_session.current_leader_seat = 9999} against the merged schema and the engine
 * <strong>accepted all three</strong>, while the domain refuses all three
 * ({@link GameSession#MAXIMUM_PLAYERS} caps the table at six seats; {@code Trick} and
 * {@code TrickPlay} reject seats outside 0..5). ADR-023:1180-1206 records that gap and
 * assigns it to Slice C. The three tests below re-run those exact three inserts and assert
 * they now fail, so the fix has evidence attached rather than an assertion in a changeset
 * comment.
 *
 * <p><strong>Every constraint is tested in both directions, and that is the point.</strong>
 * A test that only proves rejection would pass just as happily against a CHECK that rejected
 * every value, including legal ones — it would certify a schema that cannot seat a player at
 * all. So each constraint gets a companion test inserting the boundary values that must
 * still be accepted: seats 0 and 5, sequence 1, and {@code NULL} plus 0 and 5 for
 * {@code current_leader_seat}. The rejection tests are meaningful only because the
 * acceptance tests sit beside them.
 *
 * <p>The bounds are also pinned to the domain constant by
 * {@link #checkConstraintsArePinnedToMaximumPlayers()}, following the precedent set by
 * {@code TrickPlayComponentOrdinalConstraintTest}. Liquibase cannot read a Java constant, so
 * the literal {@code 5} in the changeset is a hand-copied counterpart of
 * {@code MAXIMUM_PLAYERS - 1} and carries the asymmetric-drift risk that changeset 006
 * documented for the ordinal bound: raising {@code MAXIMUM_PLAYERS} to seat a seventh player
 * would leave storage stricter than the domain, turning a legal join into a 500 rather than
 * a validation error. The pinning test fails loudly instead.
 *
 * <p>SQL state {@code 23513} is H2's CHECK-constraint violation code, and it is asserted
 * rather than merely catching {@link SQLException} because the inserts in these tests can
 * fail for other reasons — a unique violation on {@code uq_player_session_seat} is
 * {@code 23505} and a missing parent row is {@code 23506}, and either would let a test pass
 * without the CHECK existing. This mirrors the reasoning in
 * {@code TrickPlayComponentCheckConstraintTest:41-61}. Note that PostgreSQL 17 reports
 * {@code 23514} for the same violation; the state is asserted here only because the suite
 * runs on H2, and the code that translates these violations into HTTP statuses must accept
 * both.
 *
 * <p>No Spring context. Owns its own uniquely-named in-memory H2 database.
 * {@code connection.setAutoCommit(true)} is set immediately after Liquibase runs, because
 * Liquibase leaves auto-commit off and uncommitted parent rows would make these inserts fail
 * on a foreign key rather than on the CHECK under test.
 */
@DisplayName("005 range CHECK constraints on seat and sequence columns are enforced")
class SeatAndSequenceBoundsTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:seat-and-sequence-bounds;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /**
     * H2 raises {@code 23513} for a CHECK constraint violation, as distinct from
     * {@code 23505} for a unique/PK violation and {@code 23506} for a foreign key.
     * Asserting the state proves which constraint fired.
     */
    private static final String SQL_STATE_CHECK_VIOLATION = "23513";

    /**
     * Path to the changeset on the classpath. The master changelog uses
     * {@code relativeToChangelogFile="true"}, so changesets live under
     * {@code db/changelog/changes/}.
     */
    private static final String CHANGESET_CLASSPATH =
            "db/changelog/changes/005-seat-and-sequence-bounds.xml";

    /** The highest legal seat index, derived from the domain rather than hard-coded. */
    private static final int MAX_SEAT = GameSession.MAXIMUM_PLAYERS - 1;

    /**
     * The three values @security-auditor measured as accepted before this changeset existed.
     * Named constants so the tests below read as the regression they are.
     */
    private static final int MEASURED_BAD_SEAT_ORDER = -7;

    private static final int MEASURED_BAD_SEQUENCE = -5;

    private static final int MEASURED_BAD_LEADER_SEAT = 9999;

    private Connection connection;

    private Liquibase liquibase;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        final Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        liquibase = new Liquibase(
                "db/changelog/db.changelog-master.xml",
                new ClassLoaderResourceAccessor(),
                database);
        liquibase.update(new Contexts(), new LabelExpression());
        // Liquibase leaves auto-commit disabled. Re-enable it so parent rows are committed
        // and these inserts fail on the CHECK under test rather than on a foreign key.
        connection.setAutoCommit(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (liquibase != null) {
            liquibase.close();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        try (Connection dropConn = DriverManager.getConnection(JDBC_URL, "sa", "");
             var stmt = dropConn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS DELETE FILES");
        } catch (SQLException ignored) {
            // Best-effort cleanup.
        }
    }

    // ---------------------------------------------------------------------
    // chk_player_seat_order
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("player.seat_order = -7 is rejected by chk_player_seat_order (23513)")
    void negativeSeatOrderIsRejected() throws SQLException {
        // Arrange
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Act + Assert — the exact value measured as accepted before 005 existed
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, MEASURED_BAD_SEAT_ORDER))
                .as("seat_order %d must be rejected: the domain seats at most %d players, "
                        + "so no negative seat is representable",
                        MEASURED_BAD_SEAT_ORDER, GameSession.MAXIMUM_PLAYERS)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_CHECK_VIOLATION);
    }

    @Test
    @DisplayName("player.seat_order one above the last legal seat is rejected by chk_player_seat_order (23513)")
    void seatOrderAboveMaximumIsRejected() throws SQLException {
        // Arrange
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Act + Assert — MAXIMUM_PLAYERS is a count, so the seat equal to it is one too far
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, GameSession.MAXIMUM_PLAYERS))
                .as("seat_order %d must be rejected: seats are 0-based, so %d seats means "
                        + "the highest legal seat is %d",
                        GameSession.MAXIMUM_PLAYERS, GameSession.MAXIMUM_PLAYERS, MAX_SEAT)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_CHECK_VIOLATION);
    }

    @Test
    @DisplayName("player.seat_order at both boundaries (0 and the last legal seat) is accepted")
    void seatOrderBoundariesAreAccepted() throws SQLException {
        // Arrange
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Act + Assert — non-vacuity: the CHECK must not reject legal seats.
        // Both boundaries are inclusive, so both ends are exercised.
        assertThatCode(() -> {
            MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 0);
            MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, MAX_SEAT);
        })
                .as("seats 0 and %d are legal and must be accepted — without this assertion "
                        + "the rejection tests above would pass against a CHECK that rejects everything",
                        MAX_SEAT)
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // chk_trick_sequence
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("trick.sequence = -5 is rejected by chk_trick_sequence (23513)")
    void negativeTrickSequenceIsRejected() throws SQLException {
        // Arrange
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Act + Assert — the exact value measured as accepted before 005 existed
        assertThatThrownBy(() -> insertTrickWithSequence(sessionId, MEASURED_BAD_SEQUENCE))
                .as("sequence %d must be rejected: trick sequences are 1-based", MEASURED_BAD_SEQUENCE)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_CHECK_VIOLATION);
    }

    @Test
    @DisplayName("trick.sequence = 0 is rejected by chk_trick_sequence (23513)")
    void zeroTrickSequenceIsRejected() throws SQLException {
        // Arrange
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Act + Assert — 0 is the off-by-one a 0-based caller would produce, and it is the
        // value a bound of `>= 0` would wrongly admit, so it is worth its own test
        assertThatThrownBy(() -> insertTrickWithSequence(sessionId, 0))
                .as("sequence 0 must be rejected: the first trick in a session is sequence 1")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_CHECK_VIOLATION);
    }

    @Test
    @DisplayName("trick.sequence = 1 is accepted, and so is a high sequence (no upper bound)")
    void trickSequenceLowerBoundAndHighValueAreAccepted() throws SQLException {
        // Arrange
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Act + Assert — 1 is the first legal trick, and 23 is the last one a three-player hand
        // reaches: the 68-card deck deals 23/23/22, so ceil(68 / 3) = 23 tricks.
        // 999 documents a deliberate absence — no upper bound is derivable, because no domain
        // constant caps the sequence, so the changeset asserts only `>= 1`. A value past every
        // legal game length is what demonstrates that; a legal length on its own would not.
        assertThatCode(() -> {
            insertTrickWithSequence(sessionId, 1);
            insertTrickWithSequence(sessionId, 23);
            insertTrickWithSequence(sessionId, 999);
        })
                .as("sequence 1 is the first trick, 23 is the last a three-player hand reaches, "
                        + "and 999 is past every legal length; the check bounds none of them")
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // chk_game_session_current_leader_seat
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("game_session.current_leader_seat = 9999 is rejected by chk_game_session_current_leader_seat (23513)")
    void outOfRangeCurrentLeaderSeatIsRejected() throws SQLException {
        // Arrange
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Act + Assert — the exact value measured as accepted before 005 existed
        assertThatThrownBy(() -> updateCurrentLeaderSeat(sessionId, MEASURED_BAD_LEADER_SEAT))
                .as("current_leader_seat %d must be rejected: it names a seat at the table, "
                        + "and the table has %d seats",
                        MEASURED_BAD_LEADER_SEAT, GameSession.MAXIMUM_PLAYERS)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_CHECK_VIOLATION);
    }

    @Test
    @DisplayName("game_session.current_leader_seat = -1 is rejected by chk_game_session_current_leader_seat (23513)")
    void negativeCurrentLeaderSeatIsRejected() throws SQLException {
        // Arrange
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Act + Assert — the lower bound needs its own test: a CHECK written as
        // `current_leader_seat IS NULL OR current_leader_seat <= 5` would admit -1, and the
        // 9999 test above would not notice
        assertThatThrownBy(() -> updateCurrentLeaderSeat(sessionId, -1))
                .as("current_leader_seat -1 must be rejected: seats are 0-based and non-negative")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_CHECK_VIOLATION);
    }

    @Test
    @DisplayName("game_session.current_leader_seat accepts NULL and both seat boundaries")
    void currentLeaderSeatNullAndBoundariesAreAccepted() throws SQLException {
        // Arrange — a freshly inserted session leaves current_leader_seat NULL, which is
        // itself the first half of this assertion: NULL means "not yet dealt", and a CHECK
        // that rejected it would make every session insert fail.
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        assertThat(currentLeaderSeatIsNull(sessionId))
                .as("a session in LOBBY has no leader seat yet, so NULL must be permitted")
                .isTrue();

        // Act + Assert — both inclusive boundaries, then back to NULL
        assertThatCode(() -> {
            updateCurrentLeaderSeat(sessionId, 0);
            updateCurrentLeaderSeat(sessionId, MAX_SEAT);
        })
                .as("seats 0 and %d are legal leader seats and must be accepted", MAX_SEAT)
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // Domain-constant pinning
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("both seat CHECK constraints declare an upper bound equal to MAXIMUM_PLAYERS - 1")
    void checkConstraintsArePinnedToMaximumPlayers() throws IOException {
        // Arrange
        final String changesetXml = readClasspathResource(CHANGESET_CLASSPATH);

        // Act + Assert — player.seat_order
        assertThat(soleUpperBound(changesetXml, PLAYER_SEAT_CHECK, "chk_player_seat_order"))
                .as("chk_player_seat_order's upper bound must equal MAXIMUM_PLAYERS - 1 (%d). "
                        + "Liquibase cannot read the Java constant, so raising MAXIMUM_PLAYERS "
                        + "without editing the changeset would make storage stricter than the "
                        + "domain and turn a legal join into a 500.", MAX_SEAT)
                .isEqualTo(MAX_SEAT);

        // Act + Assert — game_session.current_leader_seat
        assertThat(soleUpperBound(changesetXml, LEADER_SEAT_CHECK, "chk_game_session_current_leader_seat"))
                .as("chk_game_session_current_leader_seat's upper bound must equal "
                        + "MAXIMUM_PLAYERS - 1 (%d): it names a seat at the same table", MAX_SEAT)
                .isEqualTo(MAX_SEAT);
    }

    /**
     * Matches the whole {@code ADD CONSTRAINT chk_player_seat_order CHECK (...)} statement,
     * capturing the upper bound.
     *
     * <p>Anchoring on the full statement including the constraint name — rather than on
     * {@code BETWEEN 0 AND (\d+)} alone — is what stops a prose comment from satisfying the
     * pattern. That was a real defect in the first version of the equivalent test for
     * changeset 006: an explanatory comment mentioning the old bound became the pinned
     * value, and the test reported success while the deployed constraint said something else.
     */
    private static final Pattern PLAYER_SEAT_CHECK = Pattern.compile(
            "ALTER\\s+TABLE\\s+player\\s+"
                    + "ADD\\s+CONSTRAINT\\s+chk_player_seat_order\\s+"
                    + "CHECK\\s*\\(\\s*seat_order\\s+BETWEEN\\s+0\\s+AND\\s+(\\d+)\\s*\\)",
            Pattern.DOTALL);

    /**
     * Matches the whole {@code ADD CONSTRAINT chk_game_session_current_leader_seat CHECK (...)}
     * statement, capturing the upper bound. The {@code IS NULL OR} disjunct is required by the
     * pattern: without it the column could not represent an undealt session, and a changeset
     * that dropped it would be a different constraint that this test should not accept.
     */
    private static final Pattern LEADER_SEAT_CHECK = Pattern.compile(
            "ALTER\\s+TABLE\\s+game_session\\s+"
                    + "ADD\\s+CONSTRAINT\\s+chk_game_session_current_leader_seat\\s+"
                    + "CHECK\\s*\\(\\s*current_leader_seat\\s+IS\\s+NULL\\s+OR\\s+"
                    + "current_leader_seat\\s+BETWEEN\\s+0\\s+AND\\s+(\\d+)\\s*\\)",
            Pattern.DOTALL);

    /**
     * Returns the single upper bound captured by the given pattern, asserting the statement
     * appears exactly once. The match count matters: two declarations of the same constraint
     * with different bounds would otherwise let the first one stand in for both.
     */
    private static int soleUpperBound(
            final String changesetXml, final Pattern pattern, final String constraintName) {
        final Matcher matcher = pattern.matcher(changesetXml);
        assertThat(matcher.find())
                .as("%s must declare an ALTER TABLE ... ADD CONSTRAINT ... CHECK statement in %s",
                        constraintName, CHANGESET_CLASSPATH)
                .isTrue();
        final int bound = Integer.parseInt(matcher.group(1));
        assertThat(matcher.find())
                .as("%s must be declared exactly once in %s", constraintName, CHANGESET_CLASSPATH)
                .isFalse();
        return bound;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Inserts a {@code trick} row with an explicit sequence.
     *
     * <p>{@code MigrationTestFixtures.insertMinimalTrickInSession} hard-codes
     * {@code sequence = 1}, which is exactly the value the CHECK accepts, so it cannot be
     * used to test the constraint. {@code leader_seat} is fixed at 0 here because it is not
     * the column under test and 0 is always legal.
     */
    private void insertTrickWithSequence(final UUID sessionId, final int sequence) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO trick (id, game_session_id, sequence, leader_seat) VALUES (?, ?, ?, 0)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, sessionId);
            ps.setInt(3, sequence);
            ps.executeUpdate();
        }
    }

    /** Sets {@code current_leader_seat} on the given session. */
    private void updateCurrentLeaderSeat(final UUID sessionId, final int seat) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE game_session SET current_leader_seat = ? WHERE id = ?")) {
            ps.setInt(1, seat);
            ps.setObject(2, sessionId);
            ps.executeUpdate();
        }
    }

    /** Returns true if the session's {@code current_leader_seat} is NULL. */
    private boolean currentLeaderSeatIsNull(final UUID sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT current_leader_seat FROM game_session WHERE id = ?")) {
            ps.setObject(1, sessionId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).as("session %s must exist", sessionId).isTrue();
                rs.getInt(1);
                return rs.wasNull();
            }
        }
    }

    /** Reads a classpath resource into a string. */
    private static String readClasspathResource(final String path) throws IOException {
        try (InputStream in = SeatAndSequenceBoundsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s must exist", path).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
