package org.maglez.eop.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Proves that every mapped entity still agrees with the migrated schema.
 *
 * <p>This is the test that stops {@code ddl-auto: validate} from being free. Until
 * this slice, three tables were mapped and the five created by changeset {@code 004}
 * were not, so Hibernate validated nothing about them and a mistake in the schema
 * could only be found by a query that happened to touch it. Mapping them means
 * Hibernate now checks all eight tables, and every column and type on them, at every
 * context start — on H2 in this suite and on PostgreSQL in production.
 *
 * <p>That check is silent when it passes, which is exactly why it needs a test of its
 * own rather than being left as something the other integration tests get
 * incidentally. Two things could turn it off without any test going red:
 *
 * <ul>
 *   <li>{@code ddl-auto} being changed to {@code none} or {@code update}, either of
 *       which would let a mapping drift from the schema indefinitely. The first test
 *       here asserts the effective value, so that change fails loudly instead of
 *       quietly removing a guard.</li>
 *   <li>An entity being deleted or renamed. Validation only covers what is mapped, so
 *       an unmapped table passes startup with no complaint at all — which is the
 *       precise reason changeset {@code 004} refused to add a {@code resolved_at}
 *       column that nothing would map.</li>
 * </ul>
 *
 * <p>The second test goes further than startup does. Hibernate's validation compares
 * mappings against database metadata; this issues a real {@code SELECT} of every
 * mapped column of every entity against the migrated database. A column name that
 * validation somehow tolerated still fails here, and it fails naming the entity.
 *
 * <p>These are entity names, not table names: the query is JPQL. The mapping from one
 * to the other is what is under test, so writing table names here would test the
 * database against itself.
 */
@SpringBootTest
@DisplayName("the mapped schema")
class MappedSchemaValidationIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("is validated against the migrations at every context start")
    void shouldValidateMappingsAgainstTheMigratedSchema() {
        final String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto");

        assertThat(ddlAuto)
                .as("Hibernate must validate mappings against the schema Liquibase built, rather than "
                        + "creating or altering it. Anything other than 'validate' means a mapping can "
                        + "drift from the migrations without a single test noticing.")
                .isEqualTo("validate");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CardJpaEntity",
                "GameSessionJpaEntity",
                "PlayerJpaEntity",
                "HandJpaEntity",
                "HandCardJpaEntity",
                "TrickJpaEntity",
                "TrickPlayJpaEntity",
                "TrickPlayComponentJpaEntity"
            })
    @DisplayName("can read every column it maps")
    void shouldSelectEveryMappedColumn(final String entityName) {
        assertThatCode(() -> selectOneRow(entityName))
                .as("Selecting from %s failed, which means a column it maps does not match the "
                        + "migrated schema. Either the entity or the changeset is wrong; the changeset "
                        + "is merged and immutable, so it is almost certainly the entity.", entityName)
                .doesNotThrowAnyException();
    }

    /**
     * Issues a {@code SELECT} of every mapped column of one entity.
     *
     * <p>Bounded to a single row because the point is that the statement is valid,
     * not what it returns; an empty table is a perfectly good result. The
     * {@link EntityManager} is created and closed here rather than injected so that
     * the test needs no transaction of its own.
     *
     * @param entityName the JPA entity name to query
     */
    private void selectOneRow(final String entityName) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            final List<?> rows = entityManager
                    .createQuery("SELECT e FROM " + entityName + " e", Object.class)
                    .setMaxResults(1)
                    .getResultList();

            assertThat(rows).as("a bounded read of %s returns at most one row", entityName).hasSizeLessThan(2);
        }
    }
}
