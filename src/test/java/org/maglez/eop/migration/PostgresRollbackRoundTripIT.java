package org.maglez.eop.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Applies the whole changelog to PostgreSQL 17, rolls it back, and applies it again — with real rows
 * in the tables while the rollbacks run.
 *
 * <p>The H2 migration tests in this package already exercise rollback, but they cannot establish
 * what PostgreSQL does when a rollback narrows a column that live data no longer fits: H2 and
 * PostgreSQL differ precisely there. That gap is what let the defect tracked as EOP-163 reach
 * {@code main} — {@code 2026-08-22--widen-join-code-to-8-characters.xml} carries a rollback that is
 * both lossy and, for most values, hard-failing, and nothing in the build noticed.</p>
 *
 * <p>Three of the tests below characterised that defect: they asserted the behaviour PostgreSQL
 * actually exhibited rather than the behaviour we wanted, pinning it down on the engine production
 * runs so EOP-163's fix had something to flip. That fix has now landed as
 * {@code 2026-08-23--guard-join-code-rollback.xml}, whose filename sorts after the widening's, so
 * Liquibase unwinds it first; its rollback adds a validated CHECK constraint asserting that every
 * join code still fits {@code VARCHAR(6)} and then drops it again, which fails if and only if a
 * violating row exists. Those three tests have therefore been flipped rather than removed: each now
 * pins the <em>refusal</em>, asserting that the rollback aborts naming the guard and that no row and
 * no column type was modified. Do not delete them to make the file read more happily — a deleted
 * characterisation test is how the defect got in.</p>
 *
 * <p>Like the other tests in this package this drives the Liquibase API directly rather than through
 * {@code @SpringBootTest}: Spring's Liquibase would already have migrated the datasource, so
 * {@code update()} would find nothing pending and a rollback would target the wrong changeset. Each
 * test gets its own freshly created database from {@link PostgresTestContainer}.</p>
 */
@DisplayName("Liquibase rollback round-trip against PostgreSQL 17")
class PostgresRollbackRoundTripIT {

    /**
     * Prefix for this class's per-test databases; distinct from every other IT so the classes cannot
     * interfere.
     *
     * <p>Each of the six tests gets its own database rather than all six sharing one. The comment this
     * replaced claimed a database was created "per test" while a single constant was passed to {@link
     * PostgresTestContainer#freshDatabase(String)} for the whole class, so isolation rested entirely on
     * that method's {@code DROP}/{@code CREATE} pair winning a race on every entry — and three of these
     * tests deliberately abandon their connection mid-transaction, which is exactly when that drop has
     * a live backend to terminate. EOP-239 records the investigation.</p>
     */
    private static final String DATABASE_NAME_PREFIX = "eop_rollback_it_";

    /** The master changelog, as {@code application.yml} references it. */
    private static final String CHANGELOG_MASTER = "db/changelog/db.changelog-master.xml";

    /** Filename of the join-code widening changelog, used to compute rollback depth. */
    private static final String CHANGELOG_JOIN_CODE = "2026-08-22--widen-join-code-to-8-characters.xml";

    /** Changesets that changelog contributes: the {@code modifyDataType} and the padding update. */
    private static final int JOIN_CODE_CHANGESETS = 2;

    /**
     * EOP-163's guard changelog, which must execute after the widening for the refusal tests to mean
     * anything: it is the changeset whose rollback re-asserts that every join code still fits
     * {@code VARCHAR(6)}, so a depth that excludes it unwinds the widening unopposed.
     */
    private static final String CHANGELOG_GUARD = "2026-08-23--guard-join-code-rollback.xml";

    /**
     * The CHECK constraint EOP-163's guard changelog adds and immediately drops while rolling back.
     *
     * <p>Assertions on it must ignore case: the engines both name it in the failure but disagree on
     * the casing — H2 reports {@code CK_EOP163_JOIN_CODE_FITS_VARCHAR6}, PostgreSQL reports it in
     * lower case.</p>
     */
    private static final String GUARD_CONSTRAINT = "ck_eop163_join_code_fits_varchar6";

    /**
     * Total changesets in the changelog; the h2 branch of 006 is MARK_RAN but still recorded.
     *
     * <p>27, not 26: EOP-163's {@code 2026-08-23--guard-join-code-rollback.xml} contributes one more,
     * a marker changeset that applies no schema change and exists only so that its rollback runs.</p>
     */
    private static final int EXPECTED_CHANGESET_ROWS = 27;

    /**
     * An eight-character join code of the kind {@code JoinCode} actually generates. Chosen not to end
     * in {@code 00} because that is the overwhelmingly likely case: only 1 in 1024 generated codes
     * end in two zeroes.
     */
    private static final String JOIN_CODE_GENUINE = "ABCDEFGH";

    /**
     * An eight-character join code that happens to end in {@code 00}. Indistinguishable, to the
     * rollback's {@code WHERE} clause, from a six-character code the forward migration padded.
     */
    private static final String JOIN_CODE_ENDING_IN_ZEROS = "ABCDEF00";

    /** A six-character code, as sessions created before the widening hold. */
    private static final String JOIN_CODE_LEGACY = "QRSTUV";

    /**
     * Every table the changelog creates, checked for absence after a full rollback and for presence
     * after re-applying.
     *
     * <p>This list was one entry short when the class was first written: {@code trick_play_component}
     * was missing, so a rollback that failed to drop it would have gone unnoticed. A hand-kept list
     * of this shape cannot be trusted on its own, which is why
     * {@link #appliesRollsBackAndReappliesTheEntireChangelog()} also counts the tables the migrated
     * schema actually holds and asserts the total matches this array's length. Add a table here and
     * the count moves with it; forget one and the count disagrees.
     */
    private static final String[] MIGRATED_TABLES = {
        "card", "game_session", "player", "hand", "hand_card", "trick", "trick_play", "trick_play_component",
        "game_result", "game_result_player",
    };

    /** The changelog holding the two mutually exclusive, engine-gated {@code expires_at} changesets. */
    private static final String CHANGELOG_SESSION_EXPIRY = "006-session-expiry.xml";

    /** Changesets 006 declares. Both are recorded; only the engine-matching one executes. */
    private static final int SESSION_EXPIRY_CHANGESETS = 2;

    /** The table 006 adds its column to. */
    private static final String TABLE_GAME_SESSION = "game_session";

    /** The column 006 adds, with a DB-side default whose SQL differs between engines. */
    private static final String COLUMN_EXPIRES_AT = "expires_at";

    /** The index 006 creates alongside the column, and must drop on rollback. */
    private static final String INDEX_EXPIRES_AT = "idx_game_session_expires_at";

    private Connection connection;
    private Liquibase liquibase;

    @BeforeEach
    void setUp(final TestInfo testInfo) throws Exception {
        connection = PostgresTestContainer.freshDatabaseFor(DATABASE_NAME_PREFIX, testInfo);
        final Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        liquibase = new Liquibase(CHANGELOG_MASTER, new ClassLoaderResourceAccessor(), database);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (liquibase != null) {
                liquibase.close();
            }
        } finally {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("applies, rolls back and re-applies the entire changelog on an empty database")
    void appliesRollsBackAndReappliesTheEntireChangelog() throws Exception {
        // Arrange
        update();
        assertThat(changelogRowCount()).as("changesets recorded by the first apply").isEqualTo(EXPECTED_CHANGESET_ROWS);
        assertThat(migratedTableCount())
                .as("tables in the migrated schema must match MIGRATED_TABLES, which was one entry short once already")
                .isEqualTo(MIGRATED_TABLES.length);

        // Act — unwind everything, then wind it back up
        rollback(EXPECTED_CHANGESET_ROWS);

        // Assert — the schema is gone, not partially gone
        for (final String table : MIGRATED_TABLES) {
            assertThat(tableExists(table))
                    .as("table %s after rolling back every changeset", table)
                    .isFalse();
        }
        assertThat(changelogRowCount()).as("changesets remaining after full rollback").isZero();

        // Act
        update();

        // Assert — and back again, byte for byte as far as the changelog is concerned
        for (final String table : MIGRATED_TABLES) {
            assertThat(tableExists(table)).as("table %s after re-applying", table).isTrue();
        }
        assertThat(changelogRowCount()).as("changesets recorded by the second apply").isEqualTo(EXPECTED_CHANGESET_ROWS);
        assertThat(joinCodeLength()).as("join_code length after re-applying").isEqualTo(8);
    }

    @Test
    @DisplayName("rolls back and re-applies the PostgreSQL branch of 006-session-expiry")
    void roundTripsThePostgresBranchOfSessionExpiry() throws Exception {
        // Arrange — 006 is the only changelog whose DDL differs by engine, so its rollback is the one
        // the H2 suite structurally cannot rehearse. SessionExpiryMigrationTest proves the H2 branch
        // unwinds and comes back; until this test nothing proved the PostgreSQL branch did.
        update();
        assertThat(columnExists(TABLE_GAME_SESSION, COLUMN_EXPIRES_AT))
                .as("%s after applying every changeset", COLUMN_EXPIRES_AT)
                .isTrue();

        // Act — unwind 006 and everything layered on top of it
        rollback(rollbackDepthFrom(CHANGELOG_SESSION_EXPIRY, SESSION_EXPIRY_CHANGESETS));

        // Assert — both halves of the changeset are undone, the column and its index
        assertThat(columnExists(TABLE_GAME_SESSION, COLUMN_EXPIRES_AT))
                .as("%s after rolling back 006", COLUMN_EXPIRES_AT)
                .isFalse();
        assertThat(indexExists(INDEX_EXPIRES_AT)).as("%s after rolling back 006", INDEX_EXPIRES_AT).isFalse();

        // Act
        update();

        // Assert — and it is the PostgreSQL branch that came back, rendered with PostgreSQL's own
        // interval syntax. The H2 branch's CURRENT_TIMESTAMP + INTERVAL '24' HOUR does not parse
        // here, so this also proves the dbms gate still selects correctly on a re-apply.
        assertThat(columnExists(TABLE_GAME_SESSION, COLUMN_EXPIRES_AT))
                .as("%s after re-applying 006", COLUMN_EXPIRES_AT)
                .isTrue();
        assertThat(indexExists(INDEX_EXPIRES_AT)).as("%s after re-applying 006", INDEX_EXPIRES_AT).isTrue();
        assertThat(columnDefaultOf(TABLE_GAME_SESSION, COLUMN_EXPIRES_AT))
                .as("re-applied %s default", COLUMN_EXPIRES_AT)
                .containsIgnoringCase("now()")
                .containsIgnoringCase("interval");
    }

    /**
     * Counts the tables the migrated schema holds, excluding Liquibase's own bookkeeping pair.
     *
     * <p>This exists to keep {@link #MIGRATED_TABLES} honest. That array is hand-kept, and it was
     * one entry short when this class was written, so comparing its length against the schema's real
     * table count turns a forgotten entry into a failure instead of a silently narrower assertion.
     *
     * @return the number of non-Liquibase tables in the {@code public} schema
     * @throws SQLException if the metadata query fails
     */
    private int migratedTableCount() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_type = 'BASE TABLE' AND table_name NOT LIKE 'databasechangelog%'")) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("table count for the migrated schema").isTrue();
                return rows.getInt(1);
            }
        }
    }

    /**
     * Derives how many changesets must be rolled back to undo the named changelog and everything
     * applied after it.
     *
     * <p>Liquibase counts backwards from the most recent changeset, so a literal depth is only
     * correct until someone appends a changelog. Deriving it from {@code databasechangelog} keeps the
     * depth right as the changelog grows.
     *
     * <p>The query scopes by neither {@code deploymentid} nor {@code dateexecuted}, so it is only
     * meaningful over exactly one apply — hence the precondition below. Rows left by an earlier apply
     * into the same database would raise the count above the floor and unwind changesets the caller
     * never named.
     *
     * @param changelogFilename the changelog file whose first changeset marks the rollback floor
     * @param ownChangesets the number of changesets that file declares, asserted as a lower bound
     * @return the number of changesets to pass to {@code Liquibase#rollback}
     * @throws SQLException if the bookkeeping query fails
     */
    private int rollbackDepthFrom(final String changelogFilename, final int ownChangesets) throws SQLException {
        assertThat(changelogRowCount())
                .as("a derived depth is only meaningful over exactly one apply of the changelog")
                .isEqualTo(EXPECTED_CHANGESET_ROWS);
        final int depth;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM databasechangelog WHERE orderexecuted >= "
                        + "(SELECT MIN(orderexecuted) FROM databasechangelog WHERE filename LIKE ?)")) {
            statement.setString(1, "%" + changelogFilename);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("rollback depth for %s", changelogFilename).isTrue();
                depth = rows.getInt(1);
            }
        }
        assertThat(depth)
                .as("%s declares %d changesets, so the depth cannot be smaller", changelogFilename, ownChangesets)
                .isGreaterThanOrEqualTo(ownChangesets);
        assertThat(depth)
                .as("the depth cannot exceed the %d changesets one apply records", EXPECTED_CHANGESET_ROWS)
                .isLessThanOrEqualTo(EXPECTED_CHANGESET_ROWS);
        return depth;
    }

    /**
     * Reports whether a column exists, using lower-cased identifiers.
     *
     * @param table the table name, folded to lower case for PostgreSQL
     * @param column the column name, folded to lower case for PostgreSQL
     * @return {@code true} when {@code information_schema} lists the column
     * @throws SQLException if the metadata query fails
     */
    private boolean columnExists(final String table, final String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            statement.setString(2, column.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("information_schema row for %s.%s", table, column).isTrue();
                return rows.getInt(1) > 0;
            }
        }
    }

    /**
     * Reports whether an index exists in the {@code public} schema.
     *
     * <p>Reads {@code pg_indexes} rather than JDBC metadata because the index under test is created
     * by its own {@code createIndex} element and is not tied to a constraint.
     *
     * @param index the index name, folded to lower case for PostgreSQL
     * @return {@code true} when {@code pg_indexes} lists the index
     * @throws SQLException if the catalogue query fails
     */
    private boolean indexExists(final String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
            statement.setString(1, index.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("pg_indexes row for %s", index).isTrue();
                return rows.getInt(1) > 0;
            }
        }
    }

    /**
     * Reads a column's DB-side default expression as PostgreSQL stores it.
     *
     * @param table the table name, folded to lower case for PostgreSQL
     * @param column the column name, folded to lower case for PostgreSQL
     * @return the normalised default expression, or {@code null} when the column has none
     * @throws SQLException if the metadata query fails
     */
    private String columnDefaultOf(final String table, final String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT column_default FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            statement.setString(2, column.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("information_schema row for %s.%s", table, column).isTrue();
                return rows.getString(1);
            }
        }
    }

    @Test
    @DisplayName("keeps a legacy six-character session intact across a rollback and re-apply of the widening")
    void roundTripsASixCharacterSessionWithoutLoss() throws Exception {
        // Arrange — a session created before the widening, plus a player and a finalised result.
        // Note the ordering: update() runs FIRST, so the six-character code is seeded into a column
        // that is already VARCHAR(8) and no eight-character code exists when the rollback runs. That
        // is what makes this test the proof that EOP-163's guard is data-conditional rather than a
        // blanket refusal — the very rollback the three tests below see refused succeeds here.
        update();
        final SeededSession seeded = seedSession(JOIN_CODE_LEGACY);

        // Act — unwind the widening and wind it back up
        rollback(joinCodeRollbackDepth());
        assertThat(joinCodeLength()).as("join_code length while rolled back").isEqualTo(6);
        assertThat(joinCodeOf(seeded.sessionId())).as("six-character code is untouched by the rollback").isEqualTo(JOIN_CODE_LEGACY);
        update();

        // Assert — every row survives, and the code is padded back to eight characters
        assertThat(joinCodeOf(seeded.sessionId()))
                .as("six-character code padded by the forward migration")
                .isEqualTo(JOIN_CODE_LEGACY + "00");
        assertThat(playerCountOf(seeded.sessionId())).as("players surviving the round trip").isEqualTo(2);
        assertThat(displayNameOf(seeded.facilitatorId())).as("facilitator display name").isEqualTo("Facilitator");
        assertThat(identityTokenHashOf(seeded.facilitatorId()))
                .as("identity token hash, which a reconnect matches on")
                .isEqualTo(seeded.facilitatorIdentityHash());
        assertThat(resultCountOf(seeded.sessionId())).as("game_result rows surviving the round trip").isEqualTo(1);
        assertThat(resultPlayerCountOf(seeded.resultId())).as("game_result_player rows surviving the round trip").isEqualTo(2);
    }

    @Test
    @DisplayName("refuses to roll back the widening while a genuine eight-character code is live, leaving it intact")
    void refusesToRollBackTheWideningWhenAGenuineEightCharacterCodeExists() throws Exception {
        // Arrange — the ordinary case: a live session holding a generated eight-character code
        update();
        final SeededSession seeded = seedSession(JOIN_CODE_GENUINE);

        // Act — EOP-163's guard unwinds first and tries to add a CHECK constraint asserting that
        // every join code still fits VARCHAR(6). This one does not, so the ALTER TABLE fails and the
        // whole rollback aborts before either of the widening's own rollbacks can run.
        final Throwable thrown = catchThrowable(() -> rollback(joinCodeRollbackDepth()));

        // Assert — the refusal names the guard, so it is the guard that stopped this and not a later
        // accident.
        //
        // This is the case that made EOP-163 worse than its title, and the reason the guard exists.
        // Changeset 002's rollback leaves this code alone (it does not end "00"), so without the
        // guard the code survived to meet changeset 001's narrowing to VARCHAR(6) — and that
        // narrowing did NOT fail. Liquibase's PostgreSQL modifyDataType generator narrows with an
        // explicit cast, and an explicit cast to varchar(n) truncates in PostgreSQL instead of
        // raising "value too long for type character varying(6)". So on the engine production runs,
        // rolling back the widening would quietly rewrite EVERY eight-character join code, not just
        // the 1-in-1024 that end "00" which changeset 002's own rollback catches. Silent truncation
        // reported as success, not the hard failure EOP-163's title describes, is what is being
        // prevented here.
        assertThat(thrown)
                .as("rolling back the widening while a genuine eight-character code is live")
                .isInstanceOf(LiquibaseException.class);
        assertThat(thrown.getMessage())
                .as("the guard constraint named in the refusal")
                .containsIgnoringCase(GUARD_CONSTRAINT);

        // Assert — refused, not half-applied. PostgreSQL DDL is transactional, so neither the row nor
        // the column type was modified.
        connection.rollback();
        connection.setAutoCommit(true);
        assertThat(joinCodeOf(seeded.sessionId())).as("join code after the refused rollback").isEqualTo(JOIN_CODE_GENUINE);
        assertThat(joinCodeLength()).as("column width after the refused rollback").isEqualTo(8);
        assertThat(playerCountOf(seeded.sessionId())).as("players after the refused rollback").isEqualTo(2);
    }

    @Test
    @DisplayName("refuses to roll back the widening before two sessions sharing a six-character prefix can collide")
    void refusesToRollBackTheWideningBeforeTwoSessionsCanCollide() throws Exception {
        // Arrange — two live sessions whose codes differ only in the last two characters. Both are
        // legitimate under a 40-bit keyspace and neither ends "00".
        update();
        final SeededSession first = seedSession("ABCDEFGH");
        final SeededSession second = seedSession("ABCDEFJK");

        // Act
        final Throwable thrown = catchThrowable(() -> rollback(joinCodeRollbackDepth()));

        // Assert — the rollback still refuses, but now for a better reason. Narrowing would have
        // truncated both codes to ABCDEF, which uq_game_session_join_code forbids; the guard fires
        // first, so that collision is never reached. Asserting the message names the guard and does
        // NOT name the unique constraint is the point of this test: it proves the refusal happens
        // before the destructive UPDATE and the narrowing, rather than the collision being what
        // saved us.
        //
        // That distinction also removes the trap. Failing only on a collision made the abort
        // data-dependent — a rollback rehearsed on an empty or lightly-seeded database succeeded
        // while the same rollback against production aborted, which is exactly the failure mode a
        // migration rollback must never have. The guard is deterministic for ANY code wider than six
        // characters, so it no longer takes two unlucky sessions for a rehearsal to show the refusal.
        assertThat(thrown)
                .as("rolling back the widening with two codes sharing a six-character prefix")
                .isInstanceOf(LiquibaseException.class);
        assertThat(thrown.getMessage())
                .as("the refusal must name the guard, not the unique constraint the guard pre-empts")
                .containsIgnoringCase(GUARD_CONSTRAINT)
                .doesNotContainIgnoringCase("uq_game_session_join_code");

        // Assert — both rows and the column type are as they were.
        connection.rollback();
        connection.setAutoCommit(true);
        assertThat(joinCodeOf(first.sessionId())).as("first join code after the refused rollback").isEqualTo("ABCDEFGH");
        assertThat(joinCodeOf(second.sessionId())).as("second join code after the refused rollback").isEqualTo("ABCDEFJK");
        assertThat(joinCodeLength()).as("column width after the refused rollback").isEqualTo(8);
    }

    @Test
    @DisplayName("refuses to roll back the widening while a genuine code ending in 00 is live, leaving it intact")
    void refusesToRollBackTheWideningWhenAGenuineCodeEndingInZerosExists() throws Exception {
        // Arrange — the 1-in-1024 case changeset 002's rollback WHERE clause cannot tell from a code
        // the forward migration padded
        update();
        final SeededSession seeded = seedSession(JOIN_CODE_ENDING_IN_ZEROS);

        // Act
        final Throwable thrown = catchThrowable(() -> rollback(joinCodeRollbackDepth()));

        // Assert — this is the case no better predicate could ever rescue, which is why refusing is
        // the fix rather than tightening changeset 002's LIKE '%00'. Once the forward migration has
        // run, a padded QRSTUV -> QRSTUV00 is byte-for-byte indistinguishable from a genuinely
        // generated code that happens to end "00": the padding destroyed the distinction and nothing
        // in the schema records which rows it touched. No predicate can separate them, so any
        // rollback that proceeds must guess — and without the guard it guessed silently and wrongly.
        // This code became ABCDEF, a seated player reconnecting with ABCDEF00 no longer resolved to
        // the session, and nothing recorded that it ever existed. Refusing is the only safe outcome.
        assertThat(thrown)
                .as("rolling back the widening while a genuine code ending in 00 is live")
                .isInstanceOf(LiquibaseException.class);
        assertThat(thrown.getMessage())
                .as("the guard constraint named in the refusal")
                .containsIgnoringCase(GUARD_CONSTRAINT);

        // Assert — nothing was modified
        connection.rollback();
        connection.setAutoCommit(true);
        assertThat(joinCodeOf(seeded.sessionId()))
                .as("join code after the refused rollback")
                .isEqualTo(JOIN_CODE_ENDING_IN_ZEROS);
        assertThat(joinCodeLength()).as("column width after the refused rollback").isEqualTo(8);
        assertThat(playerCountOf(seeded.sessionId())).as("players after the refused rollback").isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // Liquibase helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Applies every pending changeset and restores auto-commit.
     *
     * <p>Liquibase leaves the connection with auto-commit disabled. PostgreSQL DDL is transactional,
     * so without this the migrated schema is invisible to the metadata queries that follow.</p>
     *
     * @throws LiquibaseException if the migration fails
     * @throws SQLException if auto-commit cannot be restored
     */
    private void update() throws LiquibaseException, SQLException {
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
    }

    /**
     * Rolls back the given number of changesets and restores auto-commit.
     *
     * @param count how many changesets to unwind, counting back from the most recent
     * @throws LiquibaseException if the rollback fails
     * @throws SQLException if auto-commit cannot be restored
     */
    private void rollback(final int count) throws LiquibaseException, SQLException {
        liquibase.rollback(count, new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
    }

    /**
     * Counts how many changesets must be unwound to reach back through the join-code widening.
     *
     * <p>Liquibase counts backwards from the most recent changeset, so a literal depth is only
     * correct until someone appends a changelog. Deriving it from {@code DATABASECHANGELOG} keeps the
     * test honest — the same lesson {@code DeckTrimMigrationRoundTripTest} records after a literal
     * {@code rollback(1)} silently unwound the wrong changeset.</p>
     *
     * <p>Deriving it is also what makes EOP-163's guard participate: {@code
     * 2026-08-23--guard-join-code-rollback.xml} sorts after the widening and so executes after it,
     * which puts its changeset inside this depth and unwinds it first. The depth is three today, not
     * the widening's own two.</p>
     *
     * <p>That participation is asserted rather than assumed. If the guard ever fell outside the depth —
     * because a changelog dated between the two was dropped into {@code changes/}, which the guard's own
     * header warns about, or because the depth was computed over a contaminated table — the three
     * refusal tests would not be refused at all, and would fail with a null throwable naming no cause.
     * Asserting the guard is inside the window turns that into a failure that names it. The assertion is
     * on the filename rather than on a literal three, so appending a changelog does not break it.</p>
     *
     * @return the rollback depth, never fewer than the widening's own two changesets
     * @throws SQLException if the query fails
     */
    private int joinCodeRollbackDepth() throws SQLException {
        final int depth = rollbackDepthFrom(CHANGELOG_JOIN_CODE, JOIN_CODE_CHANGESETS);
        assertThat(changelogFilenamesInsideDepth(depth))
                .as("unwinding to %s must unwind %s first, or the rollback is never refused",
                        CHANGELOG_JOIN_CODE, CHANGELOG_GUARD)
                .anyMatch(filename -> filename.endsWith(CHANGELOG_GUARD));
        return depth;
    }

    /**
     * Lists the changelog files of the changesets a rollback of the given depth would unwind.
     *
     * @param depth how many changesets a rollback would unwind, counting back from the most recent
     * @return the filenames of those changesets, most recently executed first
     * @throws SQLException if the bookkeeping query fails
     */
    private List<String> changelogFilenamesInsideDepth(final int depth) throws SQLException {
        final List<String> filenames = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT filename FROM databasechangelog ORDER BY orderexecuted DESC LIMIT ?")) {
            statement.setInt(1, depth);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    filenames.add(rows.getString(1));
                }
            }
        }
        return filenames;
    }

    // ---------------------------------------------------------------------------------------------
    // Seeding
    // ---------------------------------------------------------------------------------------------

    /**
     * The identifiers and values a seeded session was written with, so assertions can compare against
     * what went in rather than against a literal repeated in two places.
     *
     * @param sessionId the {@code game_session} row
     * @param facilitatorId the facilitator {@code player} row
     * @param facilitatorIdentityHash the facilitator's {@code identity_token_hash}
     * @param resultId the {@code game_result} row
     */
    private record SeededSession(UUID sessionId, UUID facilitatorId, String facilitatorIdentityHash, UUID resultId) {
    }

    /**
     * Writes a complete session graph: one {@code game_session}, two {@code player} rows in adjacent
     * seats, one {@code game_result} and two {@code game_result_player} rows.
     *
     * <p>The whole foreign-key chain is satisfied rather than suppressed. The H2 tests reach for
     * {@code SET REFERENTIAL_INTEGRITY FALSE} when they need an orphan row, but that statement does
     * not exist on PostgreSQL and its equivalents need superuser rights — and a rollback test that
     * disabled integrity would no longer be testing what production does.</p>
     *
     * @param joinCode the session's join code
     * @return the identifiers written
     * @throws SQLException if any insert fails
     */
    private SeededSession seedSession(final String joinCode) throws SQLException {
        final UUID sessionId = UUID.randomUUID();
        final UUID facilitatorId = UUID.randomUUID();
        final UUID participantId = UUID.randomUUID();
        final UUID resultId = UUID.randomUUID();
        final String facilitatorIdentityHash = identityHashOf(facilitatorId);
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        final String insertSession = "INSERT INTO game_session (id, join_code, status, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insertSession)) {
            statement.setObject(1, sessionId);
            statement.setString(2, joinCode);
            statement.setString(3, "COMPLETED");
            statement.setObject(4, now);
            statement.setObject(5, now);
            statement.setLong(6, 0L);
            statement.executeUpdate();
        }

        insertPlayer(sessionId, facilitatorId, "Facilitator", 0, "FACILITATOR", facilitatorIdentityHash, now);
        insertPlayer(sessionId, participantId, "Participant", 1, "PARTICIPANT", identityHashOf(participantId), now);

        final String insertResult = "INSERT INTO game_result "
                + "(id, game_session_id, facilitator_display_name, started_at, finalised_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insertResult)) {
            statement.setObject(1, resultId);
            statement.setObject(2, sessionId);
            statement.setString(3, "Facilitator");
            statement.setObject(4, now);
            statement.setObject(5, now);
            statement.executeUpdate();
        }

        insertResultPlayer(resultId, facilitatorId, "Facilitator", 0, 3);
        insertResultPlayer(resultId, participantId, "Participant", 1, 2);

        return new SeededSession(sessionId, facilitatorId, facilitatorIdentityHash, resultId);
    }

    /**
     * Inserts one {@code player} row.
     *
     * @param sessionId the owning session
     * @param playerId the player's identifier
     * @param displayName the player's display name
     * @param seatOrder the seat, unique within the session
     * @param role {@code FACILITATOR} or {@code PARTICIPANT}
     * @param identityHash the SHA-256 hex of the player's identity token, unique across all players
     * @param joinedAt when the player joined
     * @throws SQLException if the insert fails
     */
    private void insertPlayer(final UUID sessionId, final UUID playerId, final String displayName, final int seatOrder,
            final String role, final String identityHash, final OffsetDateTime joinedAt) throws SQLException {
        final String sql = "INSERT INTO player (id, game_session_id, display_name, seat_order, player_role, "
                + "connection_status, identity_token_hash, joined_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            statement.setObject(2, sessionId);
            statement.setString(3, displayName);
            statement.setInt(4, seatOrder);
            statement.setString(5, role);
            statement.setString(6, "CONNECTED");
            statement.setString(7, identityHash);
            statement.setObject(8, joinedAt);
            statement.executeUpdate();
        }
    }

    /**
     * Inserts one {@code game_result_player} row.
     *
     * @param resultId the owning result
     * @param playerId the player the score belongs to
     * @param displayName the player's display name, denormalised into the result
     * @param seatOrder the seat the player held
     * @param score the player's score
     * @throws SQLException if the insert fails
     */
    private void insertResultPlayer(final UUID resultId, final UUID playerId, final String displayName,
            final int seatOrder, final int score) throws SQLException {
        final String sql = "INSERT INTO game_result_player (id, game_result_id, player_id, display_name, seat_order, score) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, resultId);
            statement.setObject(3, playerId);
            statement.setString(4, displayName);
            statement.setInt(5, seatOrder);
            statement.setInt(6, score);
            statement.executeUpdate();
        }
    }

    /**
     * Builds a 64-character hex string derived from a player's identifier, the shape
     * {@code identity_token_hash} requires.
     *
     * <p>Not a real SHA-256 digest — the column's only constraints are its width and uniqueness, and
     * hashing something here would suggest the value is meaningful to the migration. Deriving it from
     * the player's own UUID is what makes it unique: {@code uq_player_identity_token_hash} spans every
     * player in the database, so a fixed filler string collides as soon as a test seeds a second
     * session.</p>
     *
     * @param playerId the player the value belongs to
     * @return a 64-character hex string unique to that player
     */
    private static String identityHashOf(final UUID playerId) {
        return playerId.toString().replace("-", "").repeat(2);
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata and row queries
    // ---------------------------------------------------------------------------------------------

    /**
     * Reports whether a table exists in the {@code public} schema.
     *
     * <p>Identifiers are lower-cased because PostgreSQL folds unquoted identifiers to lower case —
     * the opposite of the H2 tests in this package, which upper-case them.</p>
     *
     * @param table the table name
     * @return true if the table exists
     * @throws SQLException if the query fails
     */
    private boolean tableExists(final String table) throws SQLException {
        final String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            return countFrom(statement) == 1;
        }
    }

    /**
     * Reads the declared maximum length of {@code game_session.join_code}.
     *
     * @return the column's character maximum length
     * @throws SQLException if the query fails
     */
    private int joinCodeLength() throws SQLException {
        final String sql = "SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'game_session' AND column_name = 'join_code'";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("game_session.join_code is present").isTrue();
            return rows.getInt(1);
        }
    }

    /**
     * Reads a session's join code.
     *
     * @param sessionId the session
     * @return the stored join code
     * @throws SQLException if the query fails
     */
    private String joinCodeOf(final UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT join_code FROM game_session WHERE id = ?")) {
            statement.setObject(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("game_session row %s is present", sessionId).isTrue();
                return rows.getString(1);
            }
        }
    }

    /**
     * Reads a player's display name.
     *
     * @param playerId the player
     * @return the stored display name
     * @throws SQLException if the query fails
     */
    private String displayNameOf(final UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT display_name FROM player WHERE id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("player row %s is present", playerId).isTrue();
                return rows.getString(1);
            }
        }
    }

    /**
     * Reads a player's identity token hash.
     *
     * @param playerId the player
     * @return the stored hash
     * @throws SQLException if the query fails
     */
    private String identityTokenHashOf(final UUID playerId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT identity_token_hash FROM player WHERE id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("player row %s is present", playerId).isTrue();
                return rows.getString(1);
            }
        }
    }

    /**
     * Counts the players in a session.
     *
     * @param sessionId the session
     * @return the number of {@code player} rows
     * @throws SQLException if the query fails
     */
    private int playerCountOf(final UUID sessionId) throws SQLException {
        return countByUuid("SELECT COUNT(*) FROM player WHERE game_session_id = ?", sessionId);
    }

    /**
     * Counts the results recorded for a session.
     *
     * @param sessionId the session
     * @return the number of {@code game_result} rows
     * @throws SQLException if the query fails
     */
    private int resultCountOf(final UUID sessionId) throws SQLException {
        return countByUuid("SELECT COUNT(*) FROM game_result WHERE game_session_id = ?", sessionId);
    }

    /**
     * Counts the per-player scores recorded against a result.
     *
     * @param resultId the result
     * @return the number of {@code game_result_player} rows
     * @throws SQLException if the query fails
     */
    private int resultPlayerCountOf(final UUID resultId) throws SQLException {
        return countByUuid("SELECT COUNT(*) FROM game_result_player WHERE game_result_id = ?", resultId);
    }

    /**
     * Counts the rows Liquibase has recorded in its tracking table.
     *
     * @return the number of {@code databasechangelog} rows
     * @throws SQLException if the query fails
     */
    private int changelogRowCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM databasechangelog")) {
            assertThat(rows.next()).as("count query returned a row").isTrue();
            return rows.getInt(1);
        }
    }

    /**
     * Runs a single-UUID-parameter count query.
     *
     * @param sql a query selecting one count column and taking one UUID parameter
     * @param id the parameter value
     * @return the count
     * @throws SQLException if the query fails
     */
    private int countByUuid(final String sql, final UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            return countFrom(statement);
        }
    }

    /**
     * Reads the single count column from a prepared count query.
     *
     * @param statement a prepared query whose first column is a count
     * @return the count
     * @throws SQLException if the query fails
     */
    private static int countFrom(final PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).as("count query returned a row").isTrue();
            return rows.getInt(1);
        }
    }
}
