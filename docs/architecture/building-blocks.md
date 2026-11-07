# Building Blocks — Static Module View

arc42 Section 5 — Static Decomposition

---

## 1. Application Layers

This project follows Hexagonal Architecture (ports & adapters). Dependencies point inward:

```
Adapters (inbound/outbound)  →  Ports (application/port/in|out)  →  Services + Domain
```

| Layer | Package root | Permitted dependencies |
|---|---|---|
| Domain | `org.maglez.eop.domain` | None (pure Java) |
| Application ports | `org.maglez.eop.application.port.in` / `port.out` | Domain only |
| Application services | `org.maglez.eop.application.service` | Ports, Domain |
| Adapters | `org.maglez.eop.inbound.*`, `org.maglez.eop.outbound.*` | Ports, Domain; Spring permitted |
| Config (composition root) | `org.maglez.eop.config` | All layers; Spring permitted here only |

---

## 2. Framework Module Inventory

### 2.1 Purpose of this section

Spring Boot 4 modularised optional autoconfiguration out of `spring-boot-autoconfigure` into
standalone `spring-boot-*` modules. **A `spring.*` property whose owning module is not a declared
dependency is inert — nothing reads it, nothing binds it, nothing warns, and nothing fails.**

This section records which autoconfiguration modules are present on the classpath and which are
deliberately absent. It exists so that:

- A reviewer can check whether a `spring.*` property will actually be read before trusting it.
- A deliberate absence is documented as intentional rather than an oversight.

This project was bitten twice before this inventory existed (EOP-27, EOP-33).

### 2.2 How to regenerate this inventory

Run the following against the project root to get the ground truth:

```bash
# 1. List all spring-boot-* artifacts declared in pom.xml
grep -E '<artifactId>spring-boot' pom.xml | grep -v '<!--' \
  | sed 's/.*<artifactId>//;s/<\/artifactId>.*//' | sort -u

# 2. Verify which AutoConfiguration classes are actually importable
#    (requires the app to have been compiled at least once)
./mvnw dependency:build-classpath -q -DincludeScope=runtime \
  -Dmdep.outputFile=.tmp/cp.txt && \
  for jar in $(tr ':' '\n' < .tmp/cp.txt | grep spring-boot); do
    jar tf "$jar" | grep 'AutoConfiguration.imports' && echo "  ^ in $jar"
  done
```

Update this file whenever a `spring-boot-*` module is added to or removed from `pom.xml`.
Write the classpath scratch file inside the worktree, as above: `.tmp/` is gitignored and needs
no `external_directory` permission grant, whereas `/tmp` prompts on every touch (see `AGENTS.md`).

### 2.3 Modules present (verified against `pom.xml` on 2026-09-02)

| Module | Autoconfiguration it activates | Notes |
|---|---|---|
| `spring-boot-starter-web` | `DispatcherServletAutoConfiguration`, `WebMvcAutoConfiguration`, etc. | Core MVC stack |
| `spring-boot-starter-data-jpa` | `HibernateJpaAutoConfiguration`, `DataSourceAutoConfiguration` | JPA + Hibernate; `ddl-auto: validate` — Liquibase owns DDL |
| `spring-boot-liquibase` | `LiquibaseAutoConfiguration` | Added explicitly (EOP-27); not pulled in by `starter-data-jpa` in SB4 |
| `spring-boot-starter-validation` | `ValidationAutoConfiguration` | Bean Validation / Hibernate Validator |
| `spring-boot-starter-cache` | `CacheAutoConfiguration` | On classpath but dormant — no `@EnableCaching` yet (see `caching.md`) |
| `spring-boot-starter-test` | Test-scope only | JUnit 5, Mockito, AssertJ |
| `spring-boot-webmvc-test` | `MockMvcAutoConfiguration` | `MockMvc` support; not bundled in `starter-test` in SB4 |
| `spring-boot-testcontainers` | `@ServiceConnection` support for Testcontainers | Test-scope only; used by the PostgreSQL 17 integration tests |

### 2.4 Modules deliberately absent

| Module | Property that looks authoritative but is inert | Reason for absence |
|---|---|---|
| `spring-boot-h2console` | `spring.h2.console.enabled` | H2 console is an unauthenticated SQL endpoint. Deliberately excluded. The property in `application.yml` is a dead letter — it is never read. See EOP-27 and the security-auditor false-CRITICAL that preceded it. |

> **Rule:** Before adding a `spring.*` property to `application.yml`, confirm its owning module
> appears in the "Modules present" table above. If it does not, the property is inert.
> Add the module first, then the property, then update this table.

---

## 3. Key External Dependencies

Every non-Boot dependency the project declares, in the order `pom.xml` declares them. The first cell is
the artifactId exactly as the pom spells it — not a prose label — because `ModuleInventoryTest` compares
this column against `/project/dependencies` in both directions, and a label such as "H2 (runtime)" names
no artifact and so compares clean against every pom there has ever been.

| Artifact | Role | Notes |
|---|---|---|
| `springdoc-openapi-starter-webmvc-ui` | API documentation (`/swagger-ui.html`) | Contract-first: `docs/api/openapi.yml` is authoritative |
| `liquibase-core` | Database migration engine | Changelogs under `src/main/resources/db/changelog/`. Declared with no `<version>` of ours — the Boot parent's `liquibase.version` owns it, so read the number off `./mvnw help:effective-pom` rather than quoting one here (`database.md`) |
| `h2` | In-memory RDBMS for local development and the test suite (`runtime` scope) | Schema managed by Liquibase, not `ddl-auto`. Excluded from the repackaged jar by the Boot plugin, so it cannot reach a deployed artifact (ADR-047) |
| `postgresql` | JDBC driver for the deployed database (`runtime` scope) | The `prod` profile is the one that uses it; there is no `dev` profile (`configuration.md`) |
| `testcontainers-postgresql` | Real PostgreSQL for integration tests (`test` scope) | Keeps migration and repository tests off H2's SQL dialect |
| `testcontainers-junit-jupiter` | Testcontainers lifecycle for JUnit 5 (`test` scope) | Container start and stop bound to the test lifecycle |

> **`commons-text` is deliberately not a row here.** It appears in `pom.xml` only inside
> `<dependencyManagement>`, which pins the version of a transitive without declaring a dependency on
> it. `ModuleInventoryTest` reads only the direct children of `/project/dependencies` for exactly this
> reason, so adding a row for it would fail the build as an artifact the pom does not declare. The same
> scoping excludes the `<parent>` coordinates, this project's own artifactId, and the second `h2` that
> appears inside an `<exclude>` in the Boot plugin's repackage configuration.

> **Resilience4j is not a dependency of this project and must not be listed here.**
> An earlier draft of this file claimed it provided retry, circuit-breaker and time-limiter
> support. It does not: `grep -rn 'resilience4j' pom.xml src/` returns nothing, there is no
> `@Retry`, `@CircuitBreaker` or `@TimeLimiter` anywhere in `src/`, and no `resilience4j.*`
> block in either profile file. The reason is that the application makes no calls that leave
> the process other than to its own database through Spring Data, so there is nothing to wrap.
> Adopting it starts with adding the dependency, and therefore with an ADR — see
> `.opencode/rules/resilience.md`, which this row previously contradicted.

---

*Last updated: 2026-09-03. Sections 2.3, 2.4 and 3 are build-enforced against `pom.xml` in both
directions by `ModuleInventoryTest`, so a dependency added without a row — or a row naming a dependency
that is not declared — fails `./mvnw verify` rather than sitting here untrue. That gate reads the first
cell of each row only; the Role and Notes columns are still reviewer-enforced, as is section 2.3's claim
about which autoconfiguration a module activates. Regenerate section 2 whenever `pom.xml` changes, using
the recipe in 2.2. Originally authored under EOP-33 (2026-08-19); section 3 restructured to carry
artifactIds under EOP-000 (2026-09-03).*
