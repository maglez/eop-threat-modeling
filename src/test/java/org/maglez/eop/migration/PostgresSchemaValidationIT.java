package org.maglez.eop.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that {@code spring.jpa.hibernate.ddl-auto: validate} agrees with the schema Liquibase
 * renders on <em>PostgreSQL 17</em> — the engine production runs — rather than only with the schema
 * it renders on H2.
 *
 * <p>This is the Spring half of EOP-164. {@link PostgresChangelogIT} proves the changelog applies
 * and that {@code 006-session-expiry.xml} selects its PostgreSQL branch, but it drives Liquibase
 * directly and so never asks Hibernate whether the resulting schema matches the JPA mappings.
 * Hibernate's schema validation is Spring's job, and it only ever runs while a Spring context is
 * starting, so proving it against PostgreSQL needs a context whose {@code DataSource} points at the
 * container. That is what this test is for.
 *
 * <p>The gap it closes was real, not theoretical. {@code GameSessionJpaEntity} maps
 * {@code expires_at}, and {@code 006-session-expiry.xml} renders that column from two mutually
 * exclusive changesets — {@code NOW() + INTERVAL '24 hours'} on PostgreSQL,
 * {@code CURRENT_TIMESTAMP + INTERVAL '24' HOUR} on H2 — selected by a {@code <dbms>} precondition.
 * Until now only the H2 branch was ever validated against the mappings, so a PostgreSQL-only
 * rendering mistake would have reached production as a startup failure.
 *
 * <p>Two differences from the H2 analogue,
 * {@code org.maglez.eop.outbound.persistence.MappedSchemaValidationIntegrationTest}, are deliberate.
 * First, the entity list is read from the JPA metamodel rather than hardcoded in a
 * {@code @ValueSource}, so it cannot drift as entities are added — with a floor asserted below so a
 * broken metamodel cannot make the test vacuously pass. Second, the container is wired in with
 * {@code @ServiceConnection} rather than a profile, because tests in this repository activate no
 * profile: {@code application-prod.yml} holds the PostgreSQL dialect but activating it would also
 * demand {@code DATASOURCE_*} environment variables and drag in unrelated production overrides, so
 * the two PostgreSQL-specific properties are set explicitly instead.
 *
 * <p>This test uses the shared container's <em>default</em> database, which
 * {@link PostgresTestContainer} deliberately leaves untouched, because {@code @ServiceConnection}
 * derives the JDBC URL from the container and cannot be pointed at a database of our choosing.
 *
 * @see PostgresChangelogIT
 * @see PostgresRollbackRoundTripIT
 */
@SpringBootTest(properties = {
    "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@Import(PostgresSchemaValidationIT.ContainerConfiguration.class)
@DisplayName("the mapped schema on PostgreSQL 17")
class PostgresSchemaValidationIT {

    /**
     * Lower bound on the number of mapped entities, so an empty or broken metamodel fails instead of
     * passing every assertion trivially. The H2 analogue names eight entities explicitly; this floor
     * is set to that count and should rise, never fall.
     */
    private static final int MINIMUM_MAPPED_ENTITIES = 8;

    /** Major version of the PostgreSQL the schema is expected to be validated against. */
    private static final int EXPECTED_POSTGRES_MAJOR_VERSION = 17;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    /**
     * Registers the shared PostgreSQL container as the context's {@code DataSource}.
     *
     * <p>The bean returns {@link PostgresTestContainer#container()} rather than constructing its own
     * container, so this context reuses the single instance already started for the migration
     * integration tests instead of paying a second startup — the amortisation EOP-164 asks for.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ContainerConfiguration {

        /**
         * Exposes the shared container as a service connection.
         *
         * @return the singleton PostgreSQL container backing every migration integration test
         */
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return PostgresTestContainer.container();
        }
    }

    @Test
    @DisplayName("is validated by Hibernate against PostgreSQL, not merely against H2")
    void validatesTheMappingsAgainstThePostgresRenderedSchema() throws Exception {
        // Arrange — the context has already started, which is itself the assertion: Hibernate ran
        // its schema validation against the container while the context was being built, and a
        // mismatch would have failed this test during initialisation.

        // Assert — the guard that makes that meaningful is still switched on
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .as("Hibernate must validate the mapped schema, never generate or update it")
                .isEqualTo("validate");

        // Assert — and it validated against PostgreSQL 17, not the suite's H2 database. Without this
        // the test would still pass against H2 if the service connection were ever mis-wired.
        try (Connection connection = dataSource.getConnection()) {
            final DatabaseMetaData metaData = connection.getMetaData();
            assertThat(metaData.getDatabaseProductName())
                    .as("database the mappings were validated against")
                    .isEqualTo("PostgreSQL");
            assertThat(metaData.getDatabaseMajorVersion())
                    .as("PostgreSQL major version")
                    .isEqualTo(EXPECTED_POSTGRES_MAJOR_VERSION);
        }
    }

    @Test
    @DisplayName("can select every column of every mapped entity from the PostgreSQL schema")
    void selectsEveryMappedColumnFromThePostgresSchema() {
        // Arrange — read the entity names from the metamodel so the list cannot drift from the
        // mappings, unlike a hardcoded @ValueSource
        final Set<String> entityNames = mappedEntityNames();

        // Assert — the floor first, so a broken metamodel cannot make the loop below vacuous
        assertThat(entityNames)
                .as("mapped entities discovered from the JPA metamodel")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_MAPPED_ENTITIES);

        // Act + Assert — startup validation checks types and nullability, but issuing a real SELECT
        // of every mapped column is what proves each column is genuinely readable on PostgreSQL. A
        // column Liquibase rendered under a different name or type on this engine fails here.
        for (final String entityName : entityNames) {
            assertThatCode(() -> selectOneRow(entityName))
                    .as("selecting every mapped column of %s", entityName)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Collects the names of every entity in the JPA metamodel.
     *
     * @return the mapped entity names, sorted so a failure message is stable
     */
    private Set<String> mappedEntityNames() {
        final Set<String> names = new TreeSet<>();
        for (final EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            names.add(entity.getName());
        }
        return names;
    }

    /**
     * Issues a bounded JPQL select over every mapped column of one entity.
     *
     * <p>The row limit keeps the query cheap on the seeded card catalogue; the point is that
     * Hibernate can render and PostgreSQL can execute a select naming every mapped column, not what
     * the rows contain.
     *
     * @param entityName the JPA entity name to select from
     */
    private void selectOneRow(final String entityName) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            final List<Object> rows = entityManager
                    .createQuery("SELECT e FROM " + entityName + " e", Object.class)
                    .setMaxResults(1)
                    .getResultList();
            assertThat(rows).as("at most one row of %s", entityName).hasSizeLessThan(2);
        }
    }
}
