package org.maglez.eop.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.TestInfo;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL 17 container, started once for the whole integration-test JVM.
 *
 * <p>The image is pinned by digest to the exact image {@code compose.app.yml} runs in production
 * ({@code postgres:17-alpine}, resolved to a specific manifest — see {@link #IMAGE}). Testing
 * against a different minor, a different base image, or merely a different <em>build</em> of the same
 * tag would reintroduce, in smaller form, exactly the gap EOP-164 exists to close: a schema verified
 * on one engine and deployed on another.
 *
 * <p>Deliberately a static singleton rather than a JUnit {@code @Container} field.
 * {@code @Testcontainers} plus {@code @Container static} starts and stops one container per test
 * class, which for this suite would mean paying container startup several times over. Failsafe runs
 * with {@code forkCount=1} and {@code reuseForks=true} by default, so a single static container is
 * shared by every integration test in the run and startup is amortised across all of them. Nothing
 * stops it: the JVM exiting tears the container down, and Testcontainers' own Ryuk sidecar reaps it
 * if the JVM dies without unwinding.
 *
 * <p>Because the container is shared, no two <em>tests</em> may share a database — not merely no two
 * classes. Liquibase migration tests apply, roll back and re-apply the entire changelog, so anything
 * pointed at one database sees whatever the previous occupant left, depending on execution order.
 * {@link #freshDatabase(String)} creates a database per name inside the shared container, which is
 * the cheap part of the isolation (a {@code CREATE DATABASE} costs milliseconds; a container start
 * costs seconds).
 *
 * <p><strong>{@code freshDatabase} is a reset, not an isolation guarantee, and the distinction has
 * cost a story.</strong> Callers that pass one name for a whole class give every test in it the same
 * database, so isolation then rests entirely on the {@code DROP}/{@code CREATE} pair succeeding on
 * every entry rather than on the tests being structurally unable to reach each other's state. That
 * pair can be delayed: {@code WITH (FORCE)} must terminate any backend still holding the database
 * open, which a test that closed its connection unsuccessfully leaves behind, and neither statement
 * carries a query timeout, so a blocked {@code DROP} waits rather than failing with a diagnosis.
 * Pass a name unique to each <em>test</em>, and treat the reset as belt-and-braces on top of that.
 * EOP-239 records the investigation.
 *
 * <p>The container's <em>default</em> database is left untouched by the raw-Liquibase tests so that
 * the Spring-context test, which reaches the container through {@code @ServiceConnection} and
 * therefore cannot choose its own database name, has one nobody else migrates.
 */
final class PostgresTestContainer {

    /**
     * Pinned by digest to the exact image the {@code POSTGRES_IMAGE} default names in
     * {@code compose.app.yml} and {@code compose.e2e.yml}.
     *
     * <p>The digest, not merely the tag, is the point. Until EOP-229 this constant read
     * {@code postgres:17-alpine} and claimed parity with {@code compose.app.yml}, which was true
     * only for as long as both resolved the same mutable tag to the same image. Once EOP-229 pinned
     * the two compose references by digest the claim became false, and the failure it opened is the
     * quiet kind: {@code 17-alpine} is rebuilt upstream, these tests verify the changelog against
     * whatever the registry serves that week, and the stack deploys something else. That is EOP-164's
     * gap — a schema verified on one engine and deployed on another — reintroduced in smaller form by
     * a pin that only went half way.
     *
     * <p>{@code PostgresImagePinTest} holds this string against both compose files and against
     * {@code tools/supply-chain/expected-containers.json}, so moving the pin means moving all four
     * together in one reviewed commit. Derive a replacement digest with
     * {@code docker buildx imagetools inspect} — never {@code docker inspect}, which reports the
     * host platform's child digest on a developer machine instead of the index digest CI resolves
     * (ADR-055).
     */
    private static final String IMAGE =
            "postgres:17-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73";

    private static final PostgreSQLContainer CONTAINER = startSingleton();

    /** PostgreSQL's identifier limit in bytes; a longer database name is silently truncated. */
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    /**
     * Every database name handed out by {@link #freshDatabaseFor(String, TestInfo)} in this JVM.
     *
     * <p>Held so that a name collision fails the test that would have inherited another's state,
     * rather than silently reinstating the sharing this method exists to remove. Because the set is
     * static and the container is JVM-wide, it catches a collision between two <em>classes</em> as
     * well as between two tests in one class.
     */
    private static final Set<String> ASSIGNED_DATABASE_NAMES = ConcurrentHashMap.newKeySet();

    private PostgresTestContainer() {
        throw new AssertionError("Static holder; not instantiable.");
    }

    /**
     * Starts the one shared container.
     *
     * <p>{@code asCompatibleSubstituteFor} is required rather than decorative, and the reason is worth
     * keeping: Testcontainers refuses a digest-pinned reference outright, because a digest is opaque —
     * it cannot infer from {@code postgres@sha256:...} that the image behind it speaks PostgreSQL, so it
     * declines to guess and asks the caller to assert it. The assertion is safe here only because
     * {@code PostgresImagePinTest} holds this digest equal to the one both Compose stacks run and to the
     * reviewed entry in {@code tools/supply-chain/expected-containers.json}, and
     * {@code tools/supply-chain/audit-containers.sh} confirms against the registry that the digest still
     * resolves and still carries {@code linux/amd64}. Without those, this call would be a bare promise.
     *
     * @return the started container
     */
    private static PostgreSQLContainer startSingleton() {
        final PostgreSQLContainer container =
                new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
        container.start();
        return container;
    }

    /**
     * The shared container, started on first access.
     *
     * @return the running PostgreSQL 17 container
     */
    static PostgreSQLContainer container() {
        return CONTAINER;
    }

    /**
     * Drops and recreates a named database in the shared container, then connects to it.
     *
     * <p>Dropping first rather than only on teardown makes a test independent of whether the
     * previous run unwound cleanly. {@code WITH (FORCE)} terminates any connection still holding
     * the database open, which a failed test can leave behind; without it the {@code DROP} fails
     * with "database is being accessed by other users" and every subsequent run of the same class
     * inherits the previous run's schema.
     *
     * <p>Neither statement can run inside a transaction. The returned {@link Connection} is left in
     * the JDBC default of auto-commit, and callers that hand it to Liquibase must restore that
     * afterwards -- Liquibase turns auto-commit off and does not turn it back on, and on PostgreSQL,
     * where DDL is transactional, an uncommitted migration is invisible to metadata queries.
     *
     * @param databaseName lower-case database name, unique to the calling test — not merely to its class
     * @return a connection to the freshly created, empty database
     * @throws SQLException if the database cannot be recreated or connected to
     */
    static Connection freshDatabase(final String databaseName) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + databaseName);
        }
        return DriverManager.getConnection(
                jdbcUrlFor(databaseName), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    /**
     * Creates a database belonging to one test method and connects to it.
     *
     * <p>This is the form callers should use. Passing a single name for a whole class gives every
     * test in it the same database, which leaves isolation resting on the {@code DROP}/{@code CREATE}
     * pair in {@link #freshDatabase(String)} winning a race on every entry rather than on the tests
     * being unable to reach each other's state at all — see this class's own documentation.
     *
     * <p>The method name is hashed rather than appended whole: names in these classes already reach
     * PostgreSQL's {@value #MAX_IDENTIFIER_LENGTH}-byte identifier limit, and an over-long name is
     * truncated silently, which would reintroduce exactly the collision being removed. A readable
     * suffix follows the hash so a database left behind in the container can still be traced to its
     * test. The uniqueness assertion is what makes that a guarantee rather than an expectation.
     *
     * @param prefix lower-case prefix identifying the calling class, ending in an underscore
     * @param testInfo the running test, injected by JUnit into {@code @BeforeEach}
     * @return a connection to a freshly created, empty database belonging to that test
     * @throws SQLException if the database cannot be recreated or connected to
     */
    static Connection freshDatabaseFor(final String prefix, final TestInfo testInfo) throws SQLException {
        final String methodName = testInfo.getTestMethod().orElseThrow().getName();
        final String stem = prefix + Integer.toHexString(methodName.hashCode()) + "_";
        final String readable = methodName.toLowerCase(Locale.ROOT);
        final String databaseName = stem
                + readable.substring(0, Math.min(readable.length(), MAX_IDENTIFIER_LENGTH - stem.length()));
        assertThat(ASSIGNED_DATABASE_NAMES.add(databaseName))
                .as("database %s is already assigned, so %s would inherit another test's state", databaseName, methodName)
                .isTrue();
        return freshDatabase(databaseName);
    }

    /**
     * Builds a JDBC URL for a database other than the container's default.
     *
     * <p>Built from host and mapped port rather than by string-substituting
     * {@link PostgreSQLContainer#getJdbcUrl()}, whose query parameters would make that substitution
     * fragile.
     *
     * @param databaseName the database to address
     * @return a JDBC URL for that database in the shared container
     */
    private static String jdbcUrlFor(final String databaseName) {
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":"
                + CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
    }
}
