package org.maglez.eop.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies the entire Liquibase changelog to a real PostgreSQL 17 container.
 *
 * <p>Until EOP-164 the changelog was only ever executed against H2. That mattered most for
 * {@code 006-session-expiry.xml}, which carries two mutually exclusive changesets selected by a
 * {@code <dbms>} precondition: the H2 one ran in CI, and the PostgreSQL one ran for the first time
 * in production, verified by nothing. This test is the mirror image of
 * {@link SessionExpiryMigrationTest} -- same changelog, the other branch.
 *
 * <p>Named {@code *IT} rather than {@code *Test} on purpose. Surefire's default includes do not
 * match it, so {@code ./mvnw test} stays H2-only and sub-second; failsafe picks it up at the
 * {@code integration-test} phase during {@code ./mvnw verify}. Renaming it to {@code *Test} would
 * silently move it into the fast suite and point it at H2, which is the one engine it is not
 * supposed to be testing.
 *
 * <p>Follows the raw-Liquibase idiom of the existing migration tests rather than using
 * {@code @SpringBootTest}: a Spring context would migrate the datasource before the test body runs,
 * leaving nothing pending for {@code update()} and making a rollback depth meaningless.
 */
@DisplayName("Liquibase changelog against PostgreSQL 17")
class PostgresChangelogIT {

    /**
     * Prefix for this class's per-test databases: each test gets its own database inside the shared
     * container, derived from this prefix, so neither another class nor another test in this class can
     * affect it.
     *
     * <p>All five tests here shared one database until EOP-239. A single name left isolation resting on
     * the {@code DROP}/{@code CREATE} pair in {@link PostgresTestContainer#freshDatabase(String)}
     * winning a race on every entry, rather than on the tests being structurally unable to reach each
     * other's state. EOP-239 records the investigation.</p>
     */
    private static final String DATABASE_NAME_PREFIX = "eop_changelog_it_";

    private static final String CHANGELOG_MASTER = "db/changelog/db.changelog-master.xml";

    /**
     * Changelog directory as it sits in the repository. Read from disk rather than the classpath
     * because the point is to enumerate the files, and a classpath directory cannot be listed
     * portably. Reading repository files as text from the project root is the established idiom of
     * the documentation gates in {@code org.maglez.eop.docs}.
     */
    private static final Path CHANGES_PATH =
            Path.of("src", "main", "resources", "db", "changelog", "changes");

    /**
     * Floor on the number of changelog files. Without it a wrong working directory, or a filter that
     * stopped matching, would make {@link #changesetTotalMatchesTheChangelogFiles()} scan nothing and
     * pass vacuously.
     */
    private static final int MINIMUM_CHANGELOG_FILES = 10;

    /**
     * The number of {@code DATABASECHANGELOG} rows the full changelog must produce.
     *
     * <p>27, not 26. Only 26 changesets <em>execute</em> on any one engine, because
     * {@code 006-session-expiry.xml}'s two branches are mutually exclusive -- but a changeset
     * skipped by an {@code onFail="MARK_RAN"} precondition still records a row, so the row count is
     * the same on both engines and it is the total in the files that this must equal.
     */
    private static final int EXPECTED_CHANGESET_ROWS = 27;

    private static final String CHANGESET_POSTGRES_BRANCH = "001-add-expires-at-postgresql";

    private static final String CHANGESET_H2_BRANCH = "001-add-expires-at-h2";

    private static final String EXECTYPE_EXECUTED = "EXECUTED";

    private static final String EXECTYPE_MARK_RAN = "MARK_RAN";

    /**
     * The {@code information_schema.columns} attributes {@link #columnAttribute} is allowed to select.
     *
     * <p>An allow-list rather than a comment, because the attribute name is the one part of that query
     * that cannot be a bind parameter. Every call site passes a literal today, so this guards nothing
     * yet; it exists so that a future computed argument fails the assertion instead of reaching the
     * database as concatenated SQL.
     */
    private static final Set<String> COLUMN_ATTRIBUTES = Set.of("column_default", "data_type", "is_nullable");

    private Connection connection;

    private Liquibase liquibase;

    @BeforeEach
    void setUp(final TestInfo testInfo) throws SQLException, LiquibaseException {
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
    @DisplayName("resolves the PostgreSQL dialect rather than falling back to a generic database")
    void resolvesThePostgresDialect() throws Exception {
        // Act
        final Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));

        // Assert -- the <dbms type="postgresql"> preconditions in the changelog are matched against
        // this short name, so a wrong dialect would silently skip every PostgreSQL-gated changeset
        // and the suite would pass while testing nothing.
        assertThat(database.getShortName())
                .as("Liquibase database short name, which <dbms type=\"...\"> is matched against")
                .isEqualTo("postgresql");
        assertThat(connection.getMetaData().getDatabaseMajorVersion())
                .as("PostgreSQL major version -- production runs 17 (compose.app.yml)")
                .isEqualTo(17);
    }

    @Test
    @DisplayName("applies every changeset in the changelog")
    void appliesEveryChangeset() throws Exception {
        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        // Liquibase leaves auto-commit disabled. PostgreSQL DDL is transactional, so without this
        // the schema it just created is not visible to the queries below.
        connection.setAutoCommit(true);

        // Assert
        assertThat(changelogRowCount())
                .as("DATABASECHANGELOG rows after applying %s", CHANGELOG_MASTER)
                .isEqualTo(EXPECTED_CHANGESET_ROWS);
        assertThat(failedOrSkippedExecTypes())
                .as("changesets whose EXECTYPE is neither EXECUTED nor MARK_RAN")
                .isEmpty();
    }

    @Test
    @DisplayName("runs the PostgreSQL branch of 006 and marks the H2 branch as run")
    void selectsThePostgresBranchOfSessionExpiry() throws Exception {
        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert -- this is the assertion the H2 suite structurally cannot make. On H2 these two
        // exec types are the other way round.
        assertThat(execTypeOf(CHANGESET_POSTGRES_BRANCH))
                .as("EXECTYPE of the <dbms type=\"postgresql\"> branch of 006-session-expiry.xml")
                .isEqualTo(EXECTYPE_EXECUTED);
        assertThat(execTypeOf(CHANGESET_H2_BRANCH))
                .as("EXECTYPE of the <dbms type=\"h2\"> branch of 006-session-expiry.xml")
                .isEqualTo(EXECTYPE_MARK_RAN);
    }

    @Test
    @DisplayName("renders expires_at with the PostgreSQL interval syntax accepted by the engine")
    void rendersThePostgresExpiresAtDefault() throws Exception {
        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert -- NOW() + INTERVAL '24 hours' is the PostgreSQL-only spelling. H2 rejects it, and
        // PostgreSQL rejects H2's INTERVAL '24' HOUR, which is why 006 has two changesets at all.
        // PostgreSQL normalises the stored expression, so match on its parts rather than the source
        // text.
        final String columnDefault = columnDefaultOf("game_session", "expires_at");
        assertThat(columnDefault)
                .as("column_default of game_session.expires_at as PostgreSQL stored it")
                .isNotNull()
                .containsIgnoringCase("now()")
                .containsIgnoringCase("interval");
        assertThat(columnTypeOf("game_session", "expires_at"))
                .as("declared type of game_session.expires_at")
                .isEqualTo("timestamp with time zone");
        assertThat(isNullable("game_session", "expires_at"))
                .as("game_session.expires_at is declared NOT NULL")
                .isFalse();
    }

    @Test
    @DisplayName("expected changeset total matches the changelog files on disk")
    void changesetTotalMatchesTheChangelogFiles() throws IOException {
        // Arrange -- enumerate the directory rather than hardcoding filenames. A hardcoded list is a
        // second place to update when a changelog is appended, and forgetting it leaves the total
        // below quietly stale; a scan cannot drift, and it also catches a changelog that was written
        // but never picked up because <includeAll> filters on the .xml suffix.
        final List<Path> changelogs;
        try (Stream<Path> entries = Files.list(CHANGES_PATH)) {
            changelogs = entries
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        }

        // Act
        int counted = 0;
        final Map<String, Integer> perFile = new LinkedHashMap<>();
        for (final Path changelog : changelogs) {
            final int declared = countChangesets(changelog);
            perFile.put(changelog.getFileName().toString(), declared);
            counted += declared;
        }

        // Assert -- guards EXPECTED_CHANGESET_ROWS, which is otherwise a literal that silently goes
        // stale the moment a changelog is appended.
        assertThat(changelogs)
                .as("changelog files under %s", CHANGES_PATH)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_CHANGELOG_FILES);
        assertThat(perFile)
                .as("every changelog declares at least one changeset")
                .allSatisfy((file, declared) -> assertThat(declared).as(file).isPositive());
        assertThat(counted)
                .as("total <changeSet> elements across %d changelog files: %s", changelogs.size(), perFile)
                .isEqualTo(EXPECTED_CHANGESET_ROWS);
    }

    /**
     * Counts {@code <changeSet} elements in a changelog file.
     *
     * @param changelog path to the changelog file
     * @return the number of {@code <changeSet} elements the file declares
     */
    private static int countChangesets(final Path changelog) {
        final String xml;
        try {
            xml = Files.readString(changelog, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new AssertionError("Could not read " + changelog, e);
        }
        final Matcher matcher = Pattern.compile("<changeSet\\b").matcher(xml);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int changelogRowCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM databasechangelog")) {
            assertThat(rows.next()).as("DATABASECHANGELOG count query returned a row").isTrue();
            return rows.getInt(1);
        }
    }

    private String execTypeOf(final String changesetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT exectype FROM databasechangelog WHERE id = ?")) {
            statement.setString(1, changesetId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("DATABASECHANGELOG row for changeset %s", changesetId).isTrue();
                return rows.getString(1);
            }
        }
    }

    private Map<String, String> failedOrSkippedExecTypes() throws SQLException {
        final Map<String, String> unexpected = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT id, exectype FROM databasechangelog ORDER BY orderexecuted")) {
            while (rows.next()) {
                final String execType = rows.getString(2);
                if (!EXECTYPE_EXECUTED.equals(execType) && !EXECTYPE_MARK_RAN.equals(execType)) {
                    unexpected.put(rows.getString(1), execType);
                }
            }
        }
        return unexpected;
    }

    /**
     * Reads a column's default expression as PostgreSQL stored it.
     *
     * <p>Identifiers are passed lower-cased: PostgreSQL folds unquoted identifiers to lower case,
     * the opposite of the {@code toUpperCase()} the H2 migration tests use.
     *
     * @param table  table name
     * @param column column name
     * @return the stored default expression, or {@code null} if the column has none
     * @throws SQLException if the catalogue query fails
     */
    private String columnDefaultOf(final String table, final String column) throws SQLException {
        return columnAttribute("column_default", table, column);
    }

    private String columnTypeOf(final String table, final String column) throws SQLException {
        return columnAttribute("data_type", table, column);
    }

    private boolean isNullable(final String table, final String column) throws SQLException {
        return "YES".equals(columnAttribute("is_nullable", table, column));
    }

    /**
     * Reads one {@code information_schema.columns} attribute for a column of the migrated schema.
     *
     * <p>The attribute name is concatenated into the SQL because a column being <em>selected</em> cannot be a bind
     * parameter, so it is checked against {@link #COLUMN_ATTRIBUTES} first. All three call sites pass compile-time
     * literals and none is reachable from outside this class, so the allow-list guards nothing today; it is here so
     * that a later call site passing a computed name fails loudly instead of relying on a reviewer noticing the
     * concatenation. The table and column names are ordinary bind parameters.</p>
     *
     * <p>Both identifiers are lower-cased. PostgreSQL folds unquoted identifiers to lower case, the opposite of the
     * H2 migration tests in this package, which upper-case them for the same reason.</p>
     *
     * @param attribute the {@code information_schema.columns} column to read; must be in {@link #COLUMN_ATTRIBUTES}
     * @param table the table name
     * @param column the column name
     * @return the attribute value as PostgreSQL reports it
     * @throws SQLException if the query fails
     */
    private String columnAttribute(final String attribute, final String table, final String column)
            throws SQLException {
        assertThat(COLUMN_ATTRIBUTES)
                .as("information_schema attribute must be allow-listed, not computed")
                .contains(attribute);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + attribute + " FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            statement.setString(2, column.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("information_schema row for %s.%s", table, column).isTrue();
                return rows.getString(1);
            }
        }
    }
}
