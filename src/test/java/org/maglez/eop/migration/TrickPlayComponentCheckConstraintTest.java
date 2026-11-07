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
import org.maglez.eop.domain.TrickPlay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the CHECK constraint on {@code trick_play_component.ordinal} is actually
 * enforced by the database engine, not merely declared.
 *
 * <p>The FK chain required to insert a {@code trick_play_component} row is:
 * <pre>
 *   game_session  ← trick  ← trick_play  ← trick_play_component
 *   card          ← trick_play
 * </pre>
 * Satisfying this chain requires inserting rows into {@code game_session}, {@code card},
 * {@code trick}, and {@code trick_play} before the component row can be attempted.
 * That is non-trivial but not disproportionate: the constraint being tested is a
 * database-level enforcement, and a test that bypasses the FK chain would be testing
 * a different (weaker) schema than the one that ships. The FK chain is therefore
 * satisfied in full.
 *
 * <p>No Spring context. Owns its own uniquely-named in-memory H2 database.
 *
 * <p>Both tests assert SQL state {@code 23513} (H2's CHECK constraint violation code),
 * not a bare {@link java.sql.SQLException}. This matters because the pigeonhole argument
 * depends on knowing <em>which</em> constraint fired: a PK collision (23505) and a CHECK
 * violation (23513) are both {@link java.sql.SQLException} subclasses, and asserting only
 * the supertype cannot distinguish them.
 */
@DisplayName("trick_play_component CHECK constraint is enforced by the database")
class TrickPlayComponentCheckConstraintTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:trick-play-component-check;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /**
     * SQL state H2 emits on a CHECK constraint violation.
     * Verified empirically: H2 2.4.240 throws
     * {@code org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException} with
     * {@code getSQLState() == "23513"} for CHECK violations, as distinct from
     * {@code 23505} for unique/PK violations. Asserting this state rather than
     * bare {@link java.sql.SQLException} proves the CHECK fired, not a PK collision.
     */
    private static final String SQL_STATE_CHECK_VIOLATION = "23513";

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
        // Liquibase leaves the connection with auto-commit off. Every insert in this
        // class runs on this one connection, so the tests pass either way, but a
        // second connection opened against the same database would not see the
        // uncommitted parent rows and would fail on a foreign key (SQL state 23506)
        // instead of the constraint actually under test. Committing here keeps that
        // failure mode out of reach.
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

    @Test
    @DisplayName("inserting a component with ordinal = MAX_COMPONENTS (20) is rejected by the database")
    void ordinalAtMaxComponentsIsRejected() throws Exception {
        // Arrange — build the full FK chain
        final UUID cardId = MigrationTestFixtures.anyExistingCard(connection);
        final UUID trickId = MigrationTestFixtures.insertMinimalTrick(connection);
        final UUID trickPlayId = MigrationTestFixtures.insertMinimalTrickPlay(connection, trickId, cardId);

        // Act + Assert — ordinal = MAX_COMPONENTS (20) violates CHECK (ordinal <= 19)
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertTrickPlayComponent(connection, trickPlayId, TrickPlay.MAX_COMPONENTS, "Component A"))
                .as("ordinal = %d must be rejected by the CHECK constraint (ordinal <= %d) "
                        + "with SQL state %s (CHECK violation), not a bare SQLException",
                        TrickPlay.MAX_COMPONENTS, TrickPlay.MAX_COMPONENTS - 1, SQL_STATE_CHECK_VIOLATION)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_CHECK_VIOLATION);
    }

    @Test
    @DisplayName("inserting a 21st component for one play is impossible: PK + CHECK bound the count by pigeonhole")
    void twentyFirstComponentIsImpossible() throws Exception {
        // Arrange — build the full FK chain and insert 20 valid components (ordinals 0..19)
        final UUID cardId = MigrationTestFixtures.anyExistingCard(connection);
        final UUID trickId = MigrationTestFixtures.insertMinimalTrick(connection);
        final UUID trickPlayId = MigrationTestFixtures.insertMinimalTrickPlay(connection, trickId, cardId);

        for (int ordinal = 0; ordinal < TrickPlay.MAX_COMPONENTS; ordinal++) {
            MigrationTestFixtures.insertTrickPlayComponent(connection, trickPlayId, ordinal, "Component " + ordinal);
        }

        // Act + Assert — a 21st component cannot be inserted:
        // ordinals 0..19 are all taken (PK violation), and ordinal 20 violates the CHECK.
        // We attempt ordinal 20, which hits the CHECK first.
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertTrickPlayComponent(connection, trickPlayId, TrickPlay.MAX_COMPONENTS, "Component 20"))
                .as("a 21st component (ordinal %d) must be rejected by the CHECK constraint "
                        + "with SQL state %s (CHECK violation) — all valid ordinals 0..%d are exhausted",
                        TrickPlay.MAX_COMPONENTS, SQL_STATE_CHECK_VIOLATION, TrickPlay.MAX_COMPONENTS - 1)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_CHECK_VIOLATION);
    }

}
