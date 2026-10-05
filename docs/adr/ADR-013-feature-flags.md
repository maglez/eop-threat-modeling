# ADR-013: Feature Flags via Spring Configuration Properties

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** @tech-lead, @product-owner

## Context

The product owner's acceptance criteria separate "code deployed to production" from
"feature released to users", with a flag between the two. That separation only has
value once there is a production to deploy to. Today there is none: the pipeline
builds and publishes a container image, and `terraform apply` has never run
(ADR-012).

No flag library is on the dependency list. The candidates range from a
configuration property to a hosted service (Unleash, Flipt, FF4J), and the cost
of each is very different: a property costs nothing, a library costs a
dependency and a convergence risk, and a service costs a network hop on the
request path plus an operational component to run alongside the application and
its database on a single developer machine.

The first story to face this, EOP-6, is a read-only card catalogue. The flag the
backlog originally proposed for it would have gated `GET /api/v1/cards` behind a
`404`, which buys nothing while nothing is deployed and costs a conditional code
path and a doubled test matrix.

## Decision

**Feature flags are Spring configuration properties.** A flag is a boolean
property under `eop.features.*`, read through `@ConfigurationProperties` with
`@Validated`, and gated with `@ConditionalOnProperty` where a whole bean should
disappear or with a plain conditional where behaviour should branch.

Consequences of that shape, stated so they are not discovered later:

- **Flipping a flag restarts the application.** Properties are bound at startup.
  With one process and no load balancer that means a few seconds of
  downtime, which is acceptable for a proof of concept and is the same
  interruption every deployment already causes.
- **Flags are set as environment variables**, so a flag can be
  flipped without rebuilding the image — set `EOP_FEATURES_SESSION_LIFECYCLE=true`
  in the environment the container is started with and restart the stack with
  `docker compose -f compose.app.yml up -d`.
- **Every flag is off by default.** An unset property reads as disabled, so
  forgetting to configure a flag fails closed rather than releasing early.
- **A flag is deleted once its feature is released.** A flag that outlives its
  rollout is a permanent branch in the code and a permanent second path through
  the tests.

**EOP-6 ships unflagged.** A flag protects a live production surface from a
half-finished feature. There is no live production surface, the endpoint is read
only, and the deck it serves is placeholder data that no client depends on.
Adding a flag there would be ceremony that has to be tested and then removed.

**Flagging starts at EOP-10**, the first story with a user-visible write surface to
protect. From that point every user-visible change arrives behind a flag, as the
product owner's criteria require.

**Rejected: a flag library or service.** Unleash and Flipt solve problems this
project does not have — percentage rollouts, per-user targeting, an audit trail
of who flipped what, and flag changes without a restart. Buying them now would
add a dependency and, in the hosted case, a third component to run alongside the
application and its database on one developer machine. The migration path is open:
flags are read through one configuration properties class, so replacing the
mechanism touches that class and not the call sites.

## Consequences

- **Positive:** No new dependency, and nothing new to run or monitor. Flags are
  visible in `application.yml` and overridable per environment by the mechanism
  the deployment already uses. Unit tests set a property rather than stubbing a
  client.
- **Positive:** Reading flags through one typed properties class means the call
  sites do not know how a flag is stored, so a later move to a library or service
  is a change in one place.
- **Negative — a restart is required to flip a flag.** A real kill switch for an
  incident in progress would need something else. Accepted: the application can
  be restarted in seconds, and there are no users to protect from a few seconds
  of downtime.
- **Negative — no audit trail.** Who enabled a flag, and when, is not recorded
  anywhere except the shell history of whoever edited the environment file.
- **Neutral — the flag namespace is a convention, not an enforced rule.** Nothing
  stops a future property from being added outside `eop.features.*`. A reviewer
  catches that, not the compiler.
- **Neutral — this decision was recorded before it was needed**, so that the first
  story to want a flag did not have to make it under time pressure. That story
  turned out to be EOP-10 rather than EOP-7.

## Amendments

**2026-08-10 — the deployment premises in this ADR were corrected; the decision
was not changed.** Three statements referred to a `2 GB instance` and to editing
`/opt/eop/.env` on that instance. The EC2 target has been withdrawn
([ADR-012](./ADR-012-deployment-target.md)), so those premises were false. They
have been reworded to describe the local container stack ([ADR-016](./ADR-016-local-container-runtime.md)).

The reasoning is untouched, because none of it depended on the host. A configuration
property still costs nothing, a hosted flag service is still a third process to run
next to the application and its database, and flipping a flag still requires a
restart — a restart of a local container rather than of a remote one, which is if
anything cheaper.

The trigger story also moved. This ADR said flagging starts at EOP-7, the first
live deployment. EOP-7 is closed as superseded and no longer exists as a story, and
the first flag in the codebase was in fact introduced by EOP-10:
`eop.features.session-lifecycle`, which withholds `SessionController` entirely via
`@ConditionalOnProperty`. [ADR-019](./ADR-019-session-lifecycle-and-join-codes.md)
records that, and its own amendment records the one place where this ADR's
prescribed shape did not fit: `@ConditionalOnProperty` is evaluated before any
`@ConfigurationProperties` bean is bound, so a flag that decides whether a bean
exists cannot also be read through a typed properties class.

**2026-08-13 — a second flag is owed, and it is owed by EOP-14 Slice C2 rather than
Slice C1.** `.opencode/rules/feature-flags.md` makes this ADR and
`src/main/resources/application.yml` the flag register and says there is no separate
catalogue, so the second flag belongs here even before it exists. It does not exist
yet. `eop.features.trick-play` will withhold the deal, play and resolve-trick routes,
and [ADR-023](./ADR-023-deal-remainder-and-turn-order.md) makes it a **merge
precondition** for Slice C2: defaulted to `false`, recorded here, and covered by an
off-position test that asserts the bean is absent as well as the routes answering 404.

**2026-08-14 — `eop.features.trick-play` now exists, and what it withholds grew twice.**
*(Superseded in part by the EOP-14 Slice E note below — it grew a third time. Every cardinal in
this paragraph is Slice D's and is kept unaltered as history rather than corrected in place;
read the Slice E note for what the flag withholds today.)*
Slice C2 created it gating three use-case beans and no route, because that slice shipped
no route to gate; the off-position test could therefore only assert bean absence, and the
"routes answering 404" half of the clause above was unwritable. EOP-14 Slice D discharges
it. The flag now withholds **four use-case beans and one controller**: `DealHandsUseCase`,
`ReadOwnHandUseCase`, `PlayCardUseCase`, `ResolveTrickUseCase` and `TrickController`, whose
four routes — `POST /{sessionId}/deal`, `GET /{sessionId}/hand`, `POST /{sessionId}/plays`
and `POST /{sessionId}/tricks/current/resolve` — answer the framework's own 404 while it is
off. `TrickPlayDisabledIntegrationTest` now asserts both halves: all five beans absent *and*
all four routes 404, because the status is what a client is promised while the absence is
what pins the mechanism, and a test asserting only the status would pass against a design
this ADR forbids. It stays `false` on merge.

Two things about this flag are worth recording because they are not obvious from its name.
First, `ReadOwnHandUseCase` is a **read** and is gated anyway, which departs from the
"only the writers are gated" principle stated elsewhere in this repository: with the flag
off no hand has ever been dealt, so an ungated read would exist only to answer 409.
Second, the flag cannot be turned on when Slice D merges, and not because of a defect —
`TrickDto` deliberately publishes no answer to whose turn it is, whether the trick is
complete, or which seat leads next, so no client can yet play a game. Turning it on needs
a later slice to add a read that exposes them, and — per @security-auditor on Slice D —
needs [ADR-026](./ADR-026-use-case-observability.md) resolved first, since dealing,
playing and resolving are now reachable and entirely unaudited.

*(The second of those two things — from "Second, the flag cannot be turned on" to "add a read that
exposes them" — is Slice D's position and is superseded by the note immediately below. It is kept
verbatim because it records why the flag stayed down through Slice D, not because it is still true:
EOP-14 Slice E is the later slice it asks for. The first, on gating a read, still holds.)*

**2026-08-14 — EOP-14 Slice E: what it withholds grew a third time, and the read this ADR said
was missing now exists.** The paragraph above says the flag "cannot be turned on" because
`TrickDto` "publishes no answer to whose turn it is, whether the trick is complete, or which seat
leads next, so no client can yet play a game", and that "turning it on needs a later slice to add
a read that exposes them". **That later slice is this one, and the reason has expired.**
`GetTrickStateUseCase` reads the hands and the open trick together and returns a `TrickState`
(`TrickState.java:47-50`) that publishes exactly those three answers — `seatToPlay`, `complete`
and `nextLeaderSeat` — plus a fourth, `handComplete`; `TrickStateDto` (`TrickStateDto.java:38-41`)
maps it to the wire and computes nothing; and `GET /api/v1/sessions/{sessionId}/tricks/current`
(`TrickController.java:209`) serves it. A client can now learn whose turn it is without attempting
a play and reading the refusal, so the gameplay gap is no longer a reason to keep the flag down.
Because this ADR and `src/main/resources/application.yml` *are* the flag register (see the
2026-08-13 note above and `.opencode/rules/feature-flags.md`), an operator asking "is flag-on
reachable?" reads this paragraph and no separate catalogue: the answer is that the gameplay
blocker is gone and **three named predecessors remain** — [ADR-026](./ADR-026-use-case-observability.md)
(no use case emits any audit record), EOP-48 (the fail-open `@ConditionalOnProperty` on
`SessionController`, recorded in the next note) and EOP-15 (releasing a hand and scoring it, which
`handComplete` deliberately does not claim). [ADR-028](./ADR-028-end-of-hand-without-release-or-score.md)
names those three and owns the flag-on story.
*(Amended 2026-08-14, EOP-48 — **two** of those three predecessors remain. EOP-48 is discharged by
commit `34d30d7`, recorded in the second 2026-08-14 note below; the register's answer to "is
flag-on reachable?" is now ADR-026 and EOP-15. The list of three is kept as written because it
records why the flag was still down when Slice E merged, and because ADR-028's decision sentence
names the same three; neither is to be read as the current count.)*

**The counts moved again, and are stated below with a date rather than as a standing "current"
figure.** Re-derived from source on 2026-08-21 under EOP-41, the flag withholds **seven use-case
beans and three controllers — ten beans in all**: `DealHandsUseCase`, `ReadOwnHandUseCase`,
`PlayCardUseCase`, `GetTrickStateUseCase`, `ResolveTrickUseCase`, `EndSessionUseCase` and
`GetScoreUseCase`, plus `TrickController`, `ScoreController` and `EndSessionController`
(`UseCaseConfiguration.java:281`, `:269`, `:295`, `:331`, `:353`, `:384`, `:410`,
`TrickController.java:70`, `ScoreController.java:42` and `EndSessionController.java:54`), whose
**seven** routes — `POST /{sessionId}/deal`, `GET /{sessionId}/hand`, `POST /{sessionId}/plays`,
`GET /{sessionId}/tricks/current`, `POST /{sessionId}/tricks/current/resolve`,
`GET /{sessionId}/score` and `POST /{sessionId}/end` — answer the framework's own 404 while it is
off. `TrickPlayDisabledIntegrationTest` asserts both halves at that arity: ten beans absent
(`:90`, `:96`, `:102`, `:108`, `:123`, `:130`, `:136`, `:143`, `:150`, `:163`) *and* seven routes
404 (`:171`, `:179`, `:189`, `:197`, `:205`, `:212`, `:220`). The flag is **`true`** —
`application.yml:211` (anchor: `trick-play`), flipped by EOP-70; see the 2026-08-17 note below.

*(Amended 2026-08-21, EOP-41 — every figure and anchor in the paragraph above was re-derived before
being written, because none of the previous ones survived. It read "this is the register's current
statement", claimed eight beans and six routes, cited eight `UseCaseConfiguration`/controller
anchors that no longer pointed at the annotations they named, and closed "It stays `false` on merge
— `application.yml:112`", which EOP-70 had made false in both the value and the line. Prefer
regenerating to trusting the list: `grep -rn 'trick-play' --include='*.java' src/main/java | grep
ConditionalOnProperty` prints exactly the ten gated declarations, and `grep -n 'trick-play'
src/main/resources/application.yml` prints the flag's value and line. The figures here will decay
the same way — the date is on them so that a reader can tell how much to trust them, which is the
distinction the superseded wording lost by calling itself "current".)*

**2026-08-14 — a fail-open condition in the first flag, found reviewing the second.**
`TrickController` was written with `@ConditionalOnProperty(prefix = "eop.features",
name = "trick-play")` and no `havingValue`, which matches any value that is not literally
`false`, while its four use-case beans at that date required `"true"`. `trick-play: yes` would therefore
have registered the routes with none of their use cases and failed at startup — fail-closed,
but a trap for whoever reaches for the flag during an incident. Slice D fixed it to
`havingValue = "true"`. `SessionController` still carries the loose form and is the worse of
the two: its use-case beans are unconditional, so nothing fails the context and
`session-lifecycle: off` — which YAML 1.1 reads as boolean false, and which is the idiom an
operator is likeliest to reach for — would silently **enable** five live routes an operator
believed were off. Fail-open, not cosmetic. It is left for its own commit under its own Jira
key rather than buried in a feature branch — that key is **EOP-48**, raised before Slice D's pull
request was opened; `application.yml` currently sets the literal `false`, so the shipped default is
safe and the exposure is latent operator error.
**Every `@ConditionalOnProperty` on a flag in this repository must carry
`havingValue = "true"`.**
*(Corrected 2026-08-14, EOP-48 — the three claims this note makes about `SessionController` are
all **false of the tree as it now stands**, and are kept only as the record of what was found on
the day it was written: it no longer "still carries the loose form", its use-case beans are no
longer "unconditional", and it is no longer "left for its own commit" because that commit is
`34d30d7`. Do not quote this paragraph in the present tense; read the note immediately below,
which also strengthens the mandate in its last two lines.)*

**2026-08-14, later the same day — EOP-48: the explicit form on both flags, and the beans were
the deeper half of the defect.** `SessionController.java:69` (anchor: `havingValue`) now reads
`@ConditionalOnProperty(prefix = "eop.features", name = "session-lifecycle", havingValue = "true")`,
and the four use cases that open or mutate a session — `createSessionUseCase`,
`joinSessionUseCase`, `getSessionStateUseCase`, `startSessionUseCase` — each carry the same
condition (`UseCaseConfiguration.java:122` (anchor: `session-lifecycle`), `:145`, `:189`, `:204`). The two spellings in the tree,
`prefix` + `name` on the controllers and the single dotted `name = "eop.features.…"` on the beans,
resolve to the same property and the same condition; the inconsistency is cosmetic and is recorded
here so that nobody reads it as two mechanisms.

The mechanism is now written down exactly, because the note above understated it. `matchIfMissing`
defaults to `false`, so an **absent** property was never the hole: no property, no bean, in every
version of this code. The hole was confined to the case where the property is *present* — and
there, with `havingValue` left empty, the condition reduces to `!"false".equalsIgnoreCase(value)`
(verified against the resolved `spring-boot-autoconfigure-4.1.0` bytecode, @security-auditor,
EOP-48). `off`, `no`, `0` and `disabled` therefore all **enabled** the feature, and so did **the
empty string** — `session-lifecycle:` with nothing after the colon, or
`EOP_FEATURES_SESSION_LIFECYCLE=` exported empty in the environment a container starts with, which
is the likeliest way an operator unsets a variable while leaving it *present*. That last case was
recorded nowhere in this repository before EOP-48. It is why the mandate below is absolute rather
than a style preference: there is no value an operator can reach for, other than the six letters
`false`, that turns a loosely-conditioned flag off.

The controller was never the whole defect, and this is the part worth carrying forward to the next
flag. Withholding a request mapping hides a feature from HTTP; it does not withhold the code that
performs it. Before EOP-48 the four beans above existed in every context regardless of the flag, so
`session-lifecycle: off` produced an application that had loaded, wired and could execute session
creation, and whose only protection was that one bean's request mapping was absent. Nothing failed
the context to announce the contradiction — which is precisely why the loose form on
`TrickController` was merely a trap (its beans required `"true"`, so a bad value failed startup)
while the same loose form on `SessionController` was an exposure. Gating the beans as well makes
the off position a property of the **application context** rather than of the URL space, and it
gives the off-position test something to assert that a route test cannot: absence, not 404.

Measured rather than argued. `SessionLifecycleOffValueIntegrationTest` pins the `off` spelling with
12 tests — five bean absences, five 404s, one counterweight and one unrelated-route check — and run
against the pre-fix tree it failed **6 of 12**, with `POST /api/v1/sessions` answering **201
Created** and persisting a session while the flag said `off`. The `false` spelling keeps its own
older test (`SessionControllerDisabledIntegrationTest`), untouched, because a fix that made the two
spellings agree by breaking the one that already worked would pass a single-test suite.

One exception is deliberate and load-bearing: `resolvePlayerUseCase`
(`UseCaseConfiguration.java:158`) stays **ungated**. It is a pure lookup that writes nothing, and it
is a constructor dependency of two lifecycle use cases *and* all six trick-play use cases, so
gating it on the lifecycle flag would make the lobby-off/trick-play-on combination — the
combination this repository's own suite runs — an unsatisfiable context rather than a withheld
feature. The same reasoning already keeps `DeckShuffler` ungated: **a collaborator shared across two
flags belongs to neither, and a pure read is not state to withhold.** Both exceptions are recorded
in the javadoc at the bean, not only here, because the failure mode is a future contributor tidying
up the asymmetry.

**Every `@ConditionalOnProperty` on a flag in this repository must carry
`havingValue = "true"` — the loose form matches every value but the literal `false`, including the
empty string. And the flag must be repeated on every bean that opens or mutates the state behind
it, not on the controller alone; collaborators that only read, and collaborators shared with
another flag, stay ungated and say in their javadoc why.**

### Amendment, 2026-08-23 (EOP-50): `matchIfMissing = true` is prohibited too

The paragraph above about `matchIfMissing` says only that it *defaults* to `false`, and that
therefore an absent property was never the hole. That remains true, and it was the right thing to
establish under EOP-48, because it bounded the defect to the present-but-not-`false` case. It is
not a prohibition, and the difference matters: a default is what a future commit overrides.

A site written `@ConditionalOnProperty(name = "eop.features.x", havingValue = "true",
matchIfMissing = true)` satisfies the mandate above **to the letter** while enabling the feature
whenever the property is **absent**, inverting the fail-closed default that an unset flag reads as
disabled. It reaches the same fail-open shape as a loose `havingValue` through a different
attribute, and in one respect it is worse: a loose `havingValue` still needs an operator to set the
property to *something*, whereas this fires with no operator action at all — shipping the
annotation is sufficient.

So the mandate is extended: **no `@ConditionalOnProperty` in this repository may carry
`matchIfMissing = true`.** No site does today, so this is preventive rather than a fix.

This clause was written during EOP-50, on @security-auditor's finding that a build gate asserting
`havingValue` alone would admit a configuration defeating its own purpose. It is recorded *here*,
alongside the `havingValue` mandate, so that the whole flag mandate can be read in one place;
[ADR-052](./ADR-052-having-value-mandate-is-build-enforced.md) owns the enforcement mechanism and
the reasoning for choosing it, and originally carried this clause alone until
@architecture-guardian's gate on EOP-50 called that split authority a defect.

Slice C1 shipped without it, and the reason is worth stating in the register rather
than only in the ADR that argued it. C1 is the persistence layer — five JPA entities,
five Spring Data interfaces, two ports and one adapter — with no controller, no route
and no bean that injects a port. There is nothing for a flag to withhold, so the
off-position test this ADR requires cannot be written: there is no bean to assert
absent and no route to assert 404. **A flag whose off-position test cannot be written
is not containment**, and a `@ConditionalOnProperty` on an adapter nothing injects
would be worse than nothing, because it would produce a passing test that proved only
that an unused bean can be switched off. What contains C1 is structural: the absence
of a caller. From C2 onwards the containment is a flag, which is exactly when the flag
arrives.

Inheriting `eop.features.session-lifecycle` is not an option, for the reason this ADR
gives above: a flag is deleted once its feature is released, so borrowing one that is
due for deletion would tie the removal of a shipped flag to the readiness of an
unshipped feature. The full argument, including the one respect in which C1's
containment is *weaker* than the schema-only slice before it — mapping five tables
makes `ddl-auto: validate` load-bearing, which
`MappedSchemaValidationIntegrationTest` is the answer to — is at the end of
[ADR-023](./ADR-023-deal-remainder-and-turn-order.md). It is not restated here.

## Related

- [ADR-012: Deployment to a Single EC2 Instance with Terraform](./ADR-012-deployment-target.md)
  — the deployment premises this ADR originally borrowed; its EC2 target is withdrawn
- [ADR-016: Colima as the local container runtime](./ADR-016-local-container-runtime.md)
  — where the application actually runs, and therefore where a flag is actually set
- [ADR-019: Session lifecycle and join codes](./ADR-019-session-lifecycle-and-join-codes.md)
  — the first flag in the codebase, and why it is gated but not typed
- [ADR-040: Enable `eop.features.trick-play`](./ADR-040-trick-play-flag-on.md)
  — the second of the three flag flips catalogued below
- [ADR-042: Enable `eop.features.game-over`, and the shipped-flag-default trap](./ADR-042-game-over-flag-on.md)
  — the third flip, the masking trap that let a flag ship `false` for two stories with a green
  build, and the only dated flag-removal commitment in the repository
- [ADR-008: Liquibase migrations](./ADR-008-database-migration-liquibase.md)
  — its EOP-27 amendment is the precedent for the same masking trap on `spring.h2.console.enabled`
- [Product requirements](../requirements/PRD-eop-card-game.md)
- [Configuration rules](../../.opencode/rules/configuration.md)

## Amendments

**2026-08-16 (EOP-22).** `eop.features.session-lifecycle` now governs two additional beans:
`SweepExpiredSessionsUseCase` and `ExpiredSessionSweepScheduler`. The flag's scope is widened from
"session-lifecycle HTTP endpoints" to "session-lifecycle endpoints and the abandoned-session sweep".
`ResolvePlayerUseCase` remains ungated (it is a shared chokepoint used by both flags, and its
expiry guard is a read-only check). The sweep's deletion behaviour means that removing this flag
makes the sweep unconditional and permanent — verify the sweep cadence and remove any environment
overrides before removing the flag. See [ADR-036](ADR-036-session-expiry-and-sweep.md) for the
full rationale.

**2026-08-16 (EOP-25) — `eop.features.session-lifecycle` is now ON.** All six gate stories
(EOP-17, EOP-18, EOP-19, EOP-20, EOP-21, EOP-22) and the additional gate EOP-26 are Done.
`src/main/resources/application.yml` now sets `session-lifecycle: true` as the permanent default.
This is a reviewed change, not an environment override — the default moves in the source file so
the audit trail is the commit history rather than the shell history of whoever set an env var.
The flag and its `@ConditionalOnProperty` guards remain in place until the feature is confirmed
stable; removal is a separate story. No `EOP_FEATURES_SESSION_LIFECYCLE` entry is needed in
`compose.app.yml` — the default is now `true` and the env var would only be needed to override
it back to `false`.

This flip also activates `ExpiredSessionSweepScheduler` (the destructive half of expiry), as
the EOP-22 amendment above required to be verified before this flag's position changed. The sweep
cadence is `eop.sweep.interval-ms` (default 1 hour) with `eop.sweep.initial-delay-ms` (default
5 minutes). Both are appropriate for the current single-instance deployment (ADR-012, ADR-036).

The released surface was **lobby-only** at EOP-25: `eop.features.trick-play` was `false`, so deal,
play and resolve-trick routes were absent. "session-lifecycle is now ON" meant the five lobby
endpoints and the sweep were live; it did not mean the full game was playable.

*(Amended 2026-08-21, EOP-49 — tense pinned, because this paragraph was written in the present and
had become the opposite of true. `eop.features.trick-play` is `true` as of EOP-70 (2026-08-17),
`application.yml:211` (anchor: `trick-play`), so the deal, play and resolve-trick routes are present and the released
surface is no longer lobby-only. This mattered more than an ordinary stale sentence: as the flag
register, this document is where every other ADR now sends a reader for the flag's value — ADR-023
and `docs/adr/README.md` both drop their own arity and point here — and it was simultaneously
stating `true` in the dated counts above and `false` here. A register that contradicts itself is
worse than one that is merely out of date, because each half corroborates a different answer. The
counts paragraph above is the live statement; anything in this document phrased in the present
tense about a flag's **value** and not carrying a date should be read as the state at its own
story and checked with `grep -n 'trick-play' src/main/resources/application.yml`.)*

Note on the Decision bullets above: "Every flag is off by default" (line 44) describes the
invariant for *new* flags and for the off position of existing ones — it is not violated by this
rollout, which is the documented permanent-rollout path. "A flag is deleted once its feature is
released" (line 46) is satisfied by the removal story that follows this one; the flag stays until
confirmed stable, per `.opencode/rules/feature-flags.md` ("one release after full rollout").

**Accepted gap — observability (ADR-026).** `SessionController` and `CreateSessionUseCase` emit
no logging. `.opencode/rules/observability.md` requires INFO audit logging with actor context for
game-affecting actions and INFO request/response summaries at controllers. ADR-026 (Use-Case
Observability) is still Proposed; implementing structured logging on the session-lifecycle surface
is deferred to the ADR-026 story. This gap is accepted for this rollout and is not a blocker.

---

*(Amended 2026-08-17, EOP-70 — `eop.features.trick-play` is now `true` as of this story.
The three remaining predecessors named in the EOP-14 Slice E amendment (ADR-026, EOP-48, EOP-15)
are all discharged: EOP-48 by commit `34d30d7`, EOP-15 by ADR-032, and ADR-026 by EOP-70 itself
(option 4 chosen — audit logging at the HTTP boundary in `TrickController`; see ADR-026 for the
full decision record). The full game is now playable: deal, play-card and resolve-trick routes are
live. The flag and its `@ConditionalOnProperty` guards remain in place until confirmed stable;
removal is a separate story. No `EOP_FEATURES_TRICK_PLAY` entry is needed in `compose.app.yml` —
the default is now `true` and the env var would only be needed to override it back to `false`.)*

---

**2026-08-18 (EOP-82) — `eop.features.game-over` is now ON, and is catalogued here for the first
time.** `src/main/resources/application.yml` now sets `game-over: true` as the permanent default.
This is a reviewed change, not an environment override — the default moves in the source file so
the audit trail is the commit history rather than the shell history of whoever set an env var. The
flag and its four `@ConditionalOnProperty` guards (`GameOverController` plus the
`GetLeaderboardUseCase`, `PersistGameResultUseCase` and `NewGameUseCase` beans) remain in place
until the feature is confirmed stable; removal is a separate story, and unlike the two amendments
above it now carries a dated expiry condition, recorded in
[ADR-042](ADR-042-game-over-flag-on.md). No `EOP_FEATURES_GAME_OVER` entry is needed in
`compose.app.yml` — the default is now `true` and the env var would only be needed to override it
back to `false`. `application-prod.yml` carries no `eop.features` block at all, so this
default-profile value is authoritative under `SPRING_PROFILES_ACTIVE=prod` (ADR-012).

**This flag was omitted from this ADR at EOP-65.** The rule at the head of this document —
record each flag in `application.yml` next to its default *and here* — was not followed when
EOP-65 introduced `game-over`, so the catalogue listed two of three flags for two stories. The
omission is why the flag's position went unexamined until it produced an incident: there was no
document in which "shipped `false`, feature complete" was visible in one place. The catalogue is
complete again as of this amendment: `session-lifecycle` (EOP-25), `trick-play` (EOP-70) and
`game-over` (EOP-82), all three `true`.

**2026-08-18 (EOP-82) — the shipped default of a flag cannot be asserted through the Spring
`Environment`.** `src/test/resources/application.properties` pins all three flags `true` (lines
17, 22 and 27), which the rule above requires, because a suite running with a feature off would be
testing its absence. The consequence is that no `@SpringBootTest` and no `Environment` lookup can
observe what `application.yml` actually ships: in the test `Environment` the shipped value is
exactly the value that has been overwritten. `game-over` shipped `false` for two stories with a
complete, fully tested feature behind it and a green build throughout. This is the same masking
trap that EOP-27 removed for `spring.h2.console.enabled` (see the `**Amendment, 2026-08-10
(EOP-27).**` block of [ADR-008](ADR-008-database-migration-liquibase.md)), and EOP-27's remedy —
stop pinning the property in test resources — is unavailable here, because pinning flags ON in
tests is mandatory. `ShippedFeatureFlagDefaultsTest` was the interim mitigation: it read
`application.yml` off the classpath with `YamlPropertiesFactoryBean`, outside any Spring context.
It asserted three hand-named keys, so a fourth flag was invisible to it. **Superseded 2026-08-23
(EOP-84):** the structural fix — derive the assertion from the flags that exist and check them
against a registry declaring intended state, owning story and expiry — is built as
`FeatureFlagRegistryTest` and recorded in [ADR-053](ADR-053-feature-flag-registry-build-gate.md).
The masking trap itself is unchanged and will keep masking every future flag from any
`Environment` lookup.

**Standing consequence — the deletion bullet in Decision is not being honoured.** "**A flag is
deleted once its feature is released.**" (line 46) has now been restated at three flag flips
(EOP-25, EOP-70, EOP-82) and acted on at none. All three flags are permanently `true` and all three
guards are still in the code. Treat the bullet as an aspiration with one dated commitment against
it (ADR-042's expiry condition for `game-over`) rather than as a description of practice, and do
not add a fourth undated promise: a new flag flip must either delete a flag or state a date.

**2026-08-23 (EOP-84) — the flag namespace is now compiler-checked, and every flag must declare
intent.** The catalogue in this ADR is no longer the only record of which flags exist:
`src/test/resources/feature-flag-registry.yml` declares, per flag, its intended shipped value, the
Jira story that owns that position, and an expiry date or an explicit `null`.
`FeatureFlagRegistryTest` derives the flag set independently from the shipped `application.yml` and
from the compiled `@ConditionalOnProperty` sites, and fails `./mvnw verify` unless all three agree.
Three consequences for anyone adding or moving a flag.

- **Adding a flag is now three coordinated edits, not one.** A key in `application.yml`, a gate in
  the code, and a registry entry. Any two without the third fails the build. This is the inversion
  ADR-042 asked for: the guard fails *until* intent is declared, rather than staying silent unless
  someone remembers to add a line.
- **Changing a flag's shipped value means changing the registry in the same commit**, because the
  registry's `shipped-default` is asserted against the YAML rather than merely documenting it. That
  is the point — it forces the change to be a recorded decision instead of an edited literal.
- **The deletion bullet in Decision finally has a mechanism behind it, for flags that carry a
  date.** The standing consequence below still holds for the two flags whose expiry is `null`:
  `session-lifecycle` and `trick-play` remain permanently `true` with no date against them, and
  ADR-053 deliberately did not invent dates that no decision authorised. What has changed is that
  the field cannot be *omitted*, so a fourth flag can no longer inherit no-expiry by silence — the
  absence of a date is now a visible declaration rather than an oversight.

The mechanism is ADR-053's; this ADR remains the authority on what the flags are and what they gate.
