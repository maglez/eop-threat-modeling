# ADR-008: Database Migration Strategy with Liquibase

- **Status:** Accepted (amended 2026-08-10 — the H2 console consequence was never true; amended 2026-08-19 — the "Context labels" comparison row records a capability this project has decided not to use, see ADR-043; amended 2026-08-23 — XML over formatted-SQL rationale, centred on rollback; amended 2026-08-23 — the schema-validation, "silently" and file-forking claims in that rationale are narrowed, and rollback trust is grounded in executing the inverse; all in Amendments)
- **Date:** 2026-07-26
- **Author:** Engineering Team
- **Deciders:** Architecture Guardian, DevOps Engineer, Security Auditor

## Context

The application will persist domain entities (threat cards, games, players, scores) requiring a relational database. Without a versioned migration strategy, schema changes become untraceable, environment drift occurs, and rollbacks are manual and error-prone. We need:

- **Traceability** — every schema change is versioned, reviewed, and auditable
- **Repeatability** — fresh environments reproduce the exact schema
- **Rollback support** — revert a migration cleanly if a deployment fails
- **CI integration** — migrations are validated in the pipeline before reaching production

## Decision

Adopt **Liquibase** with **XML changelogs** as the sole mechanism for all DDL and reference data DML changes.

### Why Liquibase over alternatives

| Criterion | Liquibase | Flyway |
|---|---|---|
| Changelog formats | XML, YAML, JSON, SQL | SQL only |
| Rollback support | First-class (`rollback` tags) | Limited (undo via SQL) |
| Preconditions | Rich precondition support | Basic version checks |
| Spring Boot integration | Auto-configuration via `liquibase-core` | Auto-configuration via `flyway-core` |
| Context labels | Built-in (`context` attribute) | Not available |
| Team preference | XML format preferred | — |

### Database targets

- **Dev / Test:** H2 in-memory (auto-configured via `application.yml`, zero setup)
- **Production:** PostgreSQL (via `application-prod.yml` and `DATASOURCE_*` env vars)

Hibernate `ddl-auto` is set to `validate` in all profiles — Liquibase owns schema management, Hibernate only validates that entities match the changelog.

## Consequences

### Positive

- Every schema change is a versioned file in `src/main/resources/db/changelog/changes/`
- Rollbacks are explicit and testable — each `<changeSet>` should include a `<rollback>` block
- CI can run `mvn liquibase:updateSQL` to preview migrations without applying them
- New developers get a fully migrated DB on `./mvnw spring-boot:run` with no manual steps
- H2 console available at `/h2-console` in `dev` profile for ad-hoc queries

*Retracted by the 2026-08-10 amendment below: there is no `dev` profile, and there is no
console. Ad-hoc inspection of the in-memory schema is covered in the amendment.*

### Negative

- Developers must write Liquibase XML changesets instead of raw DDL (learning curve)
- Changesets must never be modified after merge — corrections are always new changesets
- PostgreSQL dialect differences may surface in prod but not in H2 dev

### Mitigations

- Rule file `.opencode/rules/database.md` provides change-set templates and conventions
- H2 PostgreSQL compatibility mode can be used if dialect issues arise
- Staging environment runs against PostgreSQL to catch dialect mismatches before prod

## Amendments

**Amendment, 2026-08-10 (EOP-27).** The positive consequence "H2 console available at
`/h2-console` in `dev` profile for ad-hoc queries" is withdrawn. It was wrong in two
independent ways, and the second one mattered.

There has never been a `dev` profile. ADR-012 fixes the profile set at `{default, prod}`
precisely so that local and deployed runs execute identical configuration, so the console
was never scoped to a developer-only overlay — it was configured on the profile that every
run uses unless told otherwise. `application.yml` carried `spring.h2.console.enabled: true`
from the Walking Skeleton until this amendment.

That is a serious default to have written down. The console is unauthenticated arbitrary
SQL against the running application's own database, it accepts a JDBC URL supplied by the
caller — the CVE-2021-42392 JNDI/RCE shape — and this project has no Spring Security
dependency that could have stood in front of it.

It was never actually served. Spring Boot 4 moved the console's autoconfiguration out of
`spring-boot-autoconfigure` into a separate `org.springframework.boot:spring-boot-h2console`
module, which this project does not depend on, so no class on the classpath ever read the
property. The exposure was latent, not live: one transitive dependency, or one developer
adding the module for convenience, and it would have arrived with the configuration already
consenting and no review to notice.

The property therefore stays in `application.yml` as an explicit `false` with the reasoning
attached, rather than being deleted. Deleting it would leave the next person free to add the
module and inherit a default; leaving it means they have to change a line that says why it is
off. `H2ConsoleAbsentIntegrationTest` pins this: it asserts the console is absent, and its
load-bearing assertion is a tripwire on the autoconfiguration class itself, so the build fails
the day the module lands and the decision is taken at review time rather than in an incident.

Adding the module behind a working opt-in was considered and rejected as out of scope: it
would create exactly the attack surface this change removes, and it belongs to its own ticket
if anybody ever wants it. Until then, ad-hoc inspection of the in-memory schema is done with
`spring.jpa.show-sql=true`, `./mvnw liquibase:updateSQL` for the DDL, or an integration test —
an in-memory H2 database is only reachable from inside the JVM that owns it in any case, unless
an H2 TCP server is explicitly started, which nothing here does.

**Amendment, 2026-08-19 (EOP-35).** The "Context labels" row in the comparison table above
records a capability this project has since decided never to use. The row stands as written —
it was true at selection time and it remains true of Liquibase — but it is not a reason to
reach for `context="prod"`. A context attribute restricts nothing unless
`spring.liquibase.contexts` names a real, non-empty context in **every** profile, including the
one that must not run the changeset; left unset, as it is here, the tag is inert and the
changeset runs everywhere. `LiquibaseContextGatingAbsentTest` now fails the build if any
changeset in this repository carries `context`, `contextFilter` or `labels`, or if either
profile file sets the property. See
[ADR-043](ADR-043-liquibase-contexts-are-not-used.md) for the mechanism, the evidence and the
residual risks.

**Amendment, 2026-08-23 (EOP-162).** The comparison table row "Team preference | XML format
preferred | —" is replaced with a rationale.

Liquibase supports two changelog formats: XML and formatted SQL. The XML format was chosen
because it delivers three properties that formatted SQL cannot, all centring on rollback:

1. **Declarative, dialect-agnostic rollback.** Liquibase's XML elements express the inverse
   operation directly, and the rollback blocks in this repository use eight of them —
   `<dropTable>`, `<insert>`, `<delete>`, `<update>`, `<modifyDataType>`, `<dropColumn>`,
   `<dropUniqueConstraint>` and `<dropForeignKeyConstraint>`. Each renders for both H2 and
   PostgreSQL from a single source. Formatted SQL expresses rollback as a `--rollback` comment
   directive that the SQL parser must ignore — a mistyped directive silently yields no rollback
   and is discovered only during an incident. This repo writes every rollback explicitly
   (26 `<rollback>` blocks across ten changelogs), so it does not rely on Liquibase's
   auto-inference, but the explicit blocks still benefit from the declarative form: they are
   portable across the two database targets without writing dialect-specific SQL twice.

2. **Schema-validated rollback.** Every changelog carries `xsi:schemaLocation` to
   `dbchangelog-latest.xsd`, so a malformed XML rollback is rejected when the changelog is
   parsed — and because the test suite runs Liquibase, that rejection fails `./mvnw verify`
   rather than waiting for someone to attempt a rollback. A comment directive has no schema to
   be checked against, so the failure mode differs in *when* it surfaces, not merely in how
   loudly: XML makes a bad rollback a parse-time error, formatted SQL makes it a rollback-time
   one.

3. **Reference-data rollback.** The two migrations that delete seeded reference data — the
   deck trim of 2026-08-17 and the ace removal of 2026-08-18 — reverse a `<delete>` by
   re-seeding the exact rows, and their `<rollback>` blocks hold 10 `<insert>` elements between
   them (4 and 6 respectively) carrying 50 `<column>` children. That is the sharpest case for
   the declarative form: the same 50 column values written as `INSERT` statements in formatted
   SQL would have to be maintained per dialect, or pinned to the dialect intersection, and a
   down-migration that re-seeds reference data is exactly the kind that rots unnoticed because
   nothing exercises it until it is needed.

The worked example is changeset `006-session-expiry.xml`, which adds `expires_at` to
`game_session` with a database-side default. PostgreSQL uses `NOW() + INTERVAL '24 hours'`
while H2 uses `CURRENT_TIMESTAMP + INTERVAL '24' HOUR`. XML localises the divergence to the
`defaultValueComputed` attribute on each of two changesets, gated by `<preConditions
onFail="MARK_RAN"><dbms type="postgresql"/></preConditions>` and `<dbms type="h2"/>`. Formatted
SQL would require forking the file entirely.

`onFail="MARK_RAN"` in that example is a portability trade-off, not a pattern to reach for
generally: it records the non-matching changeset as run *without* running it. That is sound here
only because the two changesets are mutually exclusive alternatives that between them cover
every supported target, so exactly one applies on any database. Used anywhere that property does
not hold, `MARK_RAN` is a fail-open marker that silently retires a migration nobody applied.

The cost is real: five of the 26 rollbacks fall back to raw `<sql>` because no declarative
element covers the operation, all of them in `005-seat-and-sequence-bounds.xml` and
`006-session-expiry.xml`. XML confines dialect-specific text rather than eliminating it, on top
of the learning curve already conceded under **Negative** above. XML was the right choice for
this project's rollback requirements, and the row now records why.

**Amendment, 2026-08-23 (EOP-165).** Three claims in the EOP-162 block above are narrowed here:
argument 2 ("Schema-validated rollback") overstates what the XSD buys, argument 1's "silently"
overstates the formatted-SQL failure mode, and the worked example's closing sentence about
forking the file is wrong. The EOP-162 text is left exactly as written. A dated amendment is
history; editing one in place would erase the record of what was claimed and when, which is the
only thing that makes a correction like this legible.

**Argument 2, narrowed: the XSD validates form, not meaning.** It rejects a rollback that is
not well-formed or not schema-legal. It has nothing to say about a rollback that is entirely
legal and does the wrong thing — and both failures already exist in this repository:

- `002-real-deck.xml:90-96` (anchor: `002-seed-placeholder-deck`) is a `<rollback>` whose only
  child is a `<comment>`. It parses, it validates, and it restores nothing. Its own comment
  text concedes the point in prose the XSD cannot read.
- `001-card-catalogue.xml:125-127` (anchor: `tableName="card"`) gives an unqualified
  `<delete tableName="card"/>` as the inverse of a changeset that inserted six specific rows.
  Schema-valid, and a whole-table delete rather than the inverse of six inserts.

So the distinction the EOP-162 text collapses is between a *malformed* rollback, which the XSD
does catch at parse time, and a *semantically wrong* rollback, which it cannot catch at all.
@expert-uncle-bod put it best, and the formulation is worth keeping: *"A schema stops you
writing nonsense; it never stops you writing the wrong thing correctly."* @expert-alex-xu was
blunter about the cost of publishing the claim as written — presenting XSD validation as a
rollback safety property *"is worse than not claiming it, because it converts a green build into
false confidence."* That is the reason to narrow it rather than merely soften it: the parse-time
guarantee is real, but it covers the failure mode nobody makes twice, and stating it under the
heading "rollback" invites the reader to believe the covered set is larger than it is.

**What actually makes a rollback trustworthy is executing the inverse in a test.** Three
round-trip tests do that on H2 — `DeckTrimMigrationRoundTripTest`, `SessionExpiryMigrationTest`
and `TrickPlaySchemaRoundTripTest` — each applying a changelog, rolling it back by a computed
changeset count, and asserting the resulting state. Since EOP-164 and EOP-163 there is also
`PostgresRollbackRoundTripIT` and `JoinCodeRollbackGuardTest`. Reading that coverage takes two
figures rather than one, and conflating them is how a false sense of safety gets built:

- **Executability** — every rollback runs without error and the changelog re-applies — is
  covered for all 27 changesets across all eleven changelogs on PostgreSQL 17, by
  `PostgresRollbackRoundTripIT.java:179` (anchor: `entire changelog`) unwinding
  `PostgresRollbackRoundTripIT.java:105` (anchor: `EXPECTED_CHANGESET_ROWS`) changesets in one
  pass.
- **State restoration** — the rollback puts the database back — is covered for six of the eleven
  changelogs: `004-trick-play-schema.xml`, `006-session-expiry.xml`,
  `2026-08-17--trim-deck-to-74-printed-cards.xml`, `2026-08-18--remove-ace-cards.xml`,
  `2026-08-22--widen-join-code-to-8-characters.xml` and
  `2026-08-23--guard-join-code-rollback.xml`. The remaining five —
  `001-card-catalogue.xml`, `002-real-deck.xml`, `003-session-lifecycle.xml`,
  `005-seat-and-sequence-bounds.xml` and `2026-08-16--game-result.xml` — have none, and both
  counterexamples above sit in that uncovered set.

A whole-changelog rollback cannot substitute for the second figure, because it terminates at an
empty database — and at an empty database a no-op rollback and an over-broad truncation are both
indistinguishable from a correct one. EOP-163 is the empirical proof: a schema-valid rollback
that reported "Rollback command completed successfully" while silently truncating live join
codes, whose changelog records that "a rehearsal against an empty database passes and the same
rollback fails against production". It was found by executing the inverse against two real
engines with data present. It was never going to be found by the XSD.

EOP-165 was filed asking for the figure "3 of 10 changelog files have rollback round-trip
coverage". That figure is wrong in both terms and is deliberately not published: it counted
three *test class names* as three changelogs, but `DeckTrimMigrationRoundTripTest` covers two —
`DeckTrimMigrationRoundTripTest.java:71` (anchor: `CHANGELOG_ACE_REMOVAL`) and
`DeckTrimMigrationRoundTripTest.java:77` (anchor: `CHANGELOG_DECK_TRIM`) — and the denominator
became eleven when EOP-163 landed a new changelog.

**A worked composition, to show what neither the XSD nor an empty-database round trip sees.**
Liquibase unwinds in reverse execution order, so a *full* unwind of `002-real-deck.xml` is
harmless. A two-step partial one is not. `rollbackCount` unwinds the last *n* changesets applied
across the whole changelog rather than anything scoped to a file, so with this file's two
changesets applied last, `rollbackCount=2` targets the state after `002-seed-placeholder-deck`
and before `003-remove-placeholder-deck` — the six placeholder rows present. Step one runs
`004-seed-real-deck`'s rollback,
`002-real-deck.xml:737-741` (anchor: `suit_order`), which empties the table. Step two runs
`003-remove-placeholder-deck`'s comment-only rollback, which re-inserts nothing. The result is an
empty table where the faithful inverse holds six rows. Every element involved is schema-valid.
Relatedly, the unqualified delete in `001-card-catalogue.xml` is correct today only because
those two rollbacks happen to run before it and leave the table empty. Nothing in the file, the
schema or the build records that dependency, and following the precedent `003` already sets —
replacing a rollback body with a comment — would turn it into a live whole-table truncation
while remaining fully schema-valid.

**Argument 1, narrowed: "silently" is too strong.** A mistyped `--rollback` directive in
formatted SQL does not produce a *wrong* rollback that runs quietly. It produces *no* rollback,
and Liquibase then refuses the operation with a rollback-impossible error rather than proceeding.
The accurate claim is narrower and still favours XML: the mistake is invisible until someone
attempts a rollback, at which point it surfaces loudly but too late — during the incident, not
in CI. Note the same timing applies to the XML counterexamples above, which are also accepted at
parse time; what closes the gap in either format is a test that executes the inverse.

**The worked example's closing sentence is wrong.** "Formatted SQL would require forking the file
entirely" is not true: formatted SQL carries the same `dbms` attribute inline on the changeset
line — `--changeset eop:001-add-expires-at-postgresql dbms:postgresql` — so it would fork the
same two changesets inside the same one file. Same structure, different syntax. What survives of
that argument is smaller: XML confines the divergence to one `defaultValueComputed` attribute per
changeset, where formatted SQL restates each `ALTER TABLE` in full per dialect.

**Two figures in the EOP-162 block have since drifted, and are restated rather than edited.**
"26 `<rollback>` blocks across ten changelogs" (argument 1) and "five of the 26 rollbacks fall
back to raw `<sql>`" (the cost paragraph) were correct when written. EOP-163 then added
`2026-08-23--guard-join-code-rollback.xml`, whose rollback is itself raw `<sql>`
(`2026-08-23--guard-join-code-rollback.xml:116-141`), and did not touch this ADR. The current
figures are **27 `<rollback>` blocks across eleven changelogs**, of which **6 use raw `<sql>`** —
three in `005-seat-and-sequence-bounds.xml`, two in `006-session-expiry.xml` and one in the
guard. Both are corroborated by `PostgresRollbackRoundTripIT.java:85`. When recounting, anchor
the pattern: `005-seat-and-sequence-bounds.xml:28` (anchor: `rollback`) mentions `<rollback>`
inside a file-header comment, so an unanchored `grep -c '<rollback>'` overcounts by one. Nothing
in the build holds a figure in this ADR against the tree, so this drift is a review concern —
which is itself an argument for citing a constant a test already asserts, as the restatement
above does, rather than a number counted by hand.

## Related

- [ADR-002: Spring Boot Walking Skeleton](ADR-002-spring-boot-bootstrap.md)
- [ADR-007: Versioning Strategy](ADR-007-versioning-strategy.md)
- [ADR-043: Liquibase contexts are not used](ADR-043-liquibase-contexts-are-not-used.md)
- [ADR-056: Liquibase Migration Tests Against PostgreSQL 17 via Testcontainers](ADR-056-postgres-migration-tests-via-testcontainers.md)
- [ADR-057: Honest Join-Code Rollback — Refusing to Truncate Live Data](ADR-057-honest-join-code-rollback.md)
- `.opencode/rules/database.md`
