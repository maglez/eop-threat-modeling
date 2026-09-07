# ADR-068: End-to-end testing with Playwright, a dedicated `e2e/` directory and a standalone `compose.e2e.yml`

**Status:** Accepted

**Date:** 2026-09-06

**Deciders:** @tech-lead, @devops-engineer, @tester-api

## Context

This repository had three tiers of automated test and a gap where a fourth should be.

JUnit unit tests cover the domain with no Spring context. Spring integration tests
cover the HTTP API, the exception mappings and the rate limiters. The k6 canary covers
latency and throughput. All three are valuable and none of them opens a browser.

That gap is not academic. The front end is a React SPA that talks to the API over
`fetch`, restores player identity from `sessionStorage`, and re-fetches state when an
SSE doorbell rings (ADR-014). It is served by Caddy from an image with a baked-in
`Caddyfile` that sets a restrictive CSP, refuses source maps, caps request bodies at
16 KB and falls back to `index.html` for unknown paths. Every one of those behaviours
is invisible to the existing tiers:

- A Vitest test in `ui/` stubs `fetch`. It proves a component renders given a payload;
  it cannot prove the payload ever arrives, or that the bundle loads at all.
- A Spring integration test calls a controller. It never evaluates JavaScript, so a
  CSP that blocks the bundle, an asset path that 404s, or a `sessionStorage` key that
  is read under the wrong name all pass it cleanly.
- The CI smoke test curls `/health` and the card endpoint. It proves the stack is up,
  not that the application is usable.

The concrete request that prompted this work was for evidence: a report that can be
handed to a stakeholder as support that the application passes its functional tests.
No existing tier produces that, because none of them tests the application the way a
user meets it.

### The reconnect mechanic, which motivated part of the scope

Investigation during EOP-216 established how player identity persists, because the
boundary scenarios depend on it. `App.tsx` writes `{playerToken, playerId, sessionId}`
into `sessionStorage` under the key `eop_session`, and restores the lobby screen on
load when that value is present and valid. `sessionStorage` is **tab-scoped and
cleared when the browser closes**, so a returning player must re-join by join code and
display name and receives a new `playerToken`. Server-side session state is preserved
independently. That asymmetry — server remembers, client forgets — is exactly the kind
of behaviour only a browser test can verify, and it is why browser-close reconnect is
a named scenario in EOP-218 rather than an assumption.

## Decision

Adopt **Playwright** (`@playwright/test`) as the fourth test tier, in a new top-level
`e2e/` directory, driven against a new standalone `compose.e2e.yml`.

### `e2e/` at the repository root, not inside `ui/`

The suite tests the assembled system — Caddy, the API and Postgres together — not the
front-end package. Placing it in `ui/` would make it a dependency of the front-end
build, put container orchestration inside `npm run verify`, and imply the wrong scope.
It is a sibling of `test/k6/` conceptually, and a sibling of `ui/` structurally.

It carries its own `package.json`, `package-lock.json` and `tsconfig.json`. Node
floor is `>= 22.12`, the repository floor set by `ui/package.json`.

### Test against the shipped images, unmodified

The compose file runs the production app image built from the root `Dockerfile` and
the production UI image built from `ui/Dockerfile`, with its own `Caddyfile` baked in.
Nothing is bind-mounted over, and no test-only assembly exists.

This is the decision that gives the tier its value. Because the real `Caddyfile` is in
play, the security headers (ADR-035), the 16 KB body cap (ADR-033), the source-map
refusal (EOP-107) and the SPA fallback are all under test. A test-only web server
configuration would have tested none of them, and would have drifted from the shipped
one within weeks.

The one build-time difference is unavoidable and is a *flag*, not a code change: the
UI image is built with `--build-arg VITE_GAME_SCREEN_ENABLED=true` and tagged
`eop-ui:e2e`. `ui/Dockerfile` defaults all three `VITE_*` flags to `false`
(fail-closed, ADR-037), and those flags are substituted into the bundle at build time,
so there is no runtime override. A distinct tag makes the difference visible in
`docker images` rather than leaving two same-named images with different behaviour.

### TLS, not plain HTTP

EOP-216 was originally specified with a plain-HTTP test stack, and an acceptance
criterion that no `--ignore-https-errors` flag would be needed. **That premise was
false and the decision was reversed during delivery.**

`ui/Caddyfile` sets `auto_https disable_redirects` and its only site block serves
`tls internal`. Nothing listens on plain HTTP, and that Caddyfile is `COPY`d into the
image — so plain HTTP is not a configuration choice, it is absent from the artefact.
Obtaining it would have required one of:

1. Editing `ui/Caddyfile` — changes production, which the story forbade.
2. Bind-mounting a second, divergent Caddyfile — abandons the central benefit above,
   leaves four shipped behaviours untested, and guarantees drift between two files.
3. Building a separate UI image — the same divergence with more machinery.

Instead the suite sets `ignoreHTTPSErrors: true`. This is not a new concession; it is
the trade the repository already makes in four places: `curl -fsSk` in the CI smoke
test (twice), `--insecure-skip-tls-verify` in the k6 canary, and
`wget --no-check-certificate` in the UI image's own `HEALTHCHECK`.

Plain HTTP would also have been actively hazardous. `ui/Caddyfile` sets HSTS with a
two-year `max-age` and `includeSubDomains`, and **HSTS on localhost is port-agnostic**
— so a plain-HTTP listener on any localhost port would be force-upgraded in a
developer's own browser for two years. Playwright's fresh profiles dodge this; a human
cannot.

### Addressing: `https://localhost:8443`

Each component is constrained rather than chosen.

**Hostname must be `localhost`.** Caddy aborts the handshake server-side on a
non-matching non-empty SNI, and `default_sni` does not rescue it. A client-side
insecure flag cannot help, because the failure is on the server, not the client. This
was established in EOP-160 (amending ADR-055), which eliminated four alternatives —
service name, container IP literal, a `localhost` network alias, and a Compose sidecar
— before settling on addressing the target as `localhost`.

**Host port must be neither 443 nor 8080.** `compose.app.yml` publishes 443, and a
local `./mvnw spring-boot:run` binds 8080. Publishing on 8443 keeps all three usable
at once.

**Publishing 8443 to a container listening on 8080 is safe**, because Caddy matches a
site block on hostname alone and ignores the port. This was verified empirically here
and is already load-bearing in CI, whose smoke test curls `https://localhost/health`
on host port 443 against the same `localhost:8080` site block and passes.

### A standalone compose file, not an override

`compose.e2e.yml` is a third stack beside `compose.app.yml` and `docker-compose.yml`.
An override file (`-f compose.app.yml -f compose.e2e.yml`) would inherit the project
name, network and volumes, so starting the tests would tear down a developer's running
application and delete its database.

Isolation is therefore total: project `eop-e2e`, subnet `172.29.0.0/24`, volumes
prefixed `eop_e2e_`, containers prefixed `eop-e2e-`, host port 8443.

Two deliberate divergences from `compose.app.yml`:

- **Postgres credentials are defaulted, not `:?` fail-if-unset.** The database is
  throwaway and destroyed by `down -v`. Requiring `.env` would block a fresh clone
  from running the suite and would force a CI secret for a container that exists for
  40 seconds.
- **Caddy depends on the app's *healthcheck*, not merely its start.** `up -d --wait`
  returns when healthchecked services are healthy, and that is the signal
  `global-setup` relies on. Ordering alone would let it return while `/api` still 502s.

### Lifecycle: `globalSetup` starts the stack, `globalTeardown` destroys it

`global-setup.ts` runs `docker compose up -d --wait --remove-orphans`, then polls
`https://localhost:8443/health` from the host until it returns 200 with a trimmed body
of exactly `OK`. `global-teardown.ts` runs `down -v --remove-orphans`.

The host-side poll is **not** redundant with `--wait`. That flag proves each
container's internal healthcheck passed — the app probes itself on
`http://127.0.0.1:8080/health`, the UI image probes itself on
`https://localhost:8080/index.html` — which says nothing about host port publication
or the TLS handshake the browsers depend on. And the body is compared, not just the
status, because a Caddy that matched no site block answers `200` with an empty body:
the single most likely misconfiguration is invisible to a status-only check.

`-v` on teardown is a requirement rather than tidiness. A fresh database per run is
what makes the leaderboard scenarios in EOP-219 deterministic.

Three environment escape hatches exist — `E2E_REUSE_STACK`, `E2E_KEEP_STACK`,
`E2E_BASE_URL` — parsed strictly, accepting only `1`/`true`/`yes`/`on` and failing
closed on anything else, so a typo disables a hatch rather than enabling it.
`E2E_REUSE_STACK` skips both `up` and teardown but **never** the health wait; CI uses
it so that CI owns the stack and can collect container logs after a failure, which a
teardown inside the test run would have deleted.

### Three browsers, run serially

Chromium, Firefox and WebKit. WebKit earns its place as the only available proxy for
Safari, whose `EventSource` and `sessionStorage` behaviour is where this application
is most likely to diverge — and both are load-bearing here.

`fullyParallel: false`, `workers: 1`. There is one application instance and one
database, the leaderboard reads whole-session history (ADR-030), and the binding
constraint is that both rate limiters key on the resolved client address, which is
identical for every browser the suite launches. Parallel workers would contend for one
bucket and fail nondeterministically.

This is a starting position. Relaxing it means opting individual files in with
`test.describe.configure({ mode: 'parallel' })` and measuring, not flipping the global
switch.

`retries: 1` in CI only, and a retried pass reports as **flaky** rather than passed,
so instability is surfaced instead of absorbed.

### Rate limits are raised in the test stack, and that bounds what the tier proves

`compose.e2e.yml` sets `EOP_WEB_SESSION_CREATION_LIMIT` and
`EOP_WEB_READ_RATE_LIMIT_LIMIT` to `Integer.MAX_VALUE`.

Caddy forwards `X-Forwarded-For: {remote_host}` and the app is told to trust it, so
every browser resolves to the one host address and the entire suite shares a single
bucket. At shipped values that is 5 session creations and 300 reads per 60 seconds for
the whole run, against a two-player game that alone peaks near 100 reads a minute
(ADR-051). This mirrors what `src/test/resources/application.properties` already does
for the Java integration tests, for the same reason.

**Consequently this tier must never be cited as evidence that rate limiting works.**
The limiters still execute and still count — the ceiling moved, the code path is not
bypassed — but the production thresholds are verified by the Java integration tests,
which pin a low limit per class with `@DirtiesContext`. `max-tracked-keys` is left at
its shipped value because the suite produces exactly one key.

### Quality gates for `e2e/`

`tsconfig.json` mirrors `ui/`'s full strictness block — `strict`,
`noUnusedLocals`, `noUnusedParameters`, `noFallthroughCasesInSwitch`,
`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes` — and `npm run typecheck` is
wired into `npm run verify`.

Two deliberate divergences, recorded here so neither reads as an oversight:

- **`module: CommonJS`, and no `"type": "module"` in `package.json`.** Playwright's
  loader emits CommonJS, which is what lets `__dirname` resolve in `stack.ts`.
  Declaring ESM would typecheck a module system the suite does not run under, moving a
  `__dirname` failure from build time to run time. `verbatimModuleSyntax` is omitted
  for the same reason — it is incompatible with `module: CommonJS` here.
- **No ESLint.** `ui/` has one; this directory does not. The strict compiler is the
  gate. Adding ESLint later is cheap and uncontroversial; asserting it is present
  when it is not would be worse than the gap.

### Scope deliberately not taken

- **The suite is not part of `./mvnw verify`.** It needs a container runtime and
  browser binaries, and it takes tens of seconds. Coupling it to the Maven build would
  make every unit-test run depend on Docker. CI strategy is ADR-069's subject.
- **No visual regression or screenshot diffing.** Screenshots are captured on failure
  as diagnostics only. Pixel baselines across three browser engines are a maintenance
  burden with a poor signal-to-noise ratio, and the report this tier exists to produce
  is functional evidence, not visual.
- **No accessibility assertions in this tier.** `ui/` already tests accessible roles
  and names with Testing Library, and the suite queries by role, so a broken
  accessibility tree tends to fail a functional test anyway. A dedicated axe pass is a
  reasonable future story, not part of the scaffold.
- **No Playwright MCP server.** Standard `@playwright/test` as a dev dependency. The
  suite is CI machinery, not an interactive agent tool.

## Consequences

**Positive**

- The shipped artefacts are tested as assembled, including four `Caddyfile`
  behaviours that previously had no automated coverage at all.
- A real browser executes the real bundle, so a CSP regression, a broken asset path or
  a `sessionStorage` contract change fails a test instead of reaching a user.
- The suite is self-contained and reproducible: one command, no manual setup, nothing
  left behind, and a fresh database every run.
- The three-browser matrix gives genuine WebKit coverage, which no other tier has.
- The HTML report is the stakeholder-facing functional evidence the tier was asked
  for.

**Negative, and accepted**

- A container runtime and roughly 500 MB of browser binaries are now needed to run one
  of the four tiers. Documented in `e2e/README.md`; not required for `./mvnw verify`.
- Serial execution means wall-clock time is roughly the sum across three browsers.
  Accepted for now, with a measured path to relaxing it.
- The suite runs with rate limits effectively disabled, so it cannot testify about
  throttling. Stated in the compose file's comments, in `e2e/README.md` and above.
- Two UI image tags now exist with different flag settings. The distinct `:e2e` tag
  makes this visible, but it is a thing to know.
- `ignoreHTTPSErrors: true` means the suite does not verify the certificate. This is a
  local CA with `tls internal`; there is no certificate worth verifying, and the same
  trade is already made four times elsewhere.
- A fourth Node package (`e2e/`) joins `ui/` and `tools/graphify/`, each with its own
  lockfile. `tools/supply-chain/` does not cover it, matching the existing position on
  the Maven layer and on `ui/`.

## Related

- `compose.e2e.yml` — the standalone test stack
- `e2e/README.md` — how to run it, and every constraint restated operationally
- `e2e/playwright.config.ts`, `e2e/stack.ts`, `e2e/global-setup.ts`, `e2e/global-teardown.ts`
- `e2e/tests/smoke.spec.ts` — the only scenario in the scaffold story
- `e2e/tests/leaderboard.spec.ts` — the end-of-game summary screen (`EOP-219`)
- ADR-069 (not yet written, due with `EOP-220`) — how this suite runs in CI and publishes its report
- [ADR-014](ADR-014-realtime-transport.md) — the SSE doorbell the suite waits on
- [ADR-017](ADR-017-frontend-delivery-topology.md) — single origin, which is why one Caddy fronts both
- [ADR-033](ADR-033-session-creation-rate-limit-and-body-size-cap.md) — the body cap and creation limiter
- [ADR-035](ADR-035-tls-and-security-response-headers.md) — the headers now under browser test
- [ADR-037](ADR-037-frontend-build-time-feature-flags.md) — why the `--build-arg` is required
- [ADR-051](ADR-051-read-route-rate-limit.md) — the read limiter and its per-minute arithmetic
- [ADR-055](ADR-055-k6-performance-check-in-ci.md) — as amended by EOP-160, source of the SNI constraint
- Epic `EOP-215`; stories `EOP-216` (this scaffold), `EOP-217`, `EOP-218`, `EOP-219`

## Amendment, 2026-09-06 (EOP-217)

Three findings from the EOP-217 happy-path delivery contradict premises the ticket was written on, and are recorded here because they constrain every future E2E scenario. They keep the numbering of the EOP-217 pre-delivery comment, so the sequence below is 1, 3, 4: findings 2, 5 and 6 there concerned the mechanics of one story — which image carries `VITE_GAME_SCREEN_ENABLED`, how a reload re-enters the game screen, and the re-scoping of a single scenario — and constrain no later work, so they are not repeated here. A fourth finding, on the leaderboard's persistence window, was established during the gate round and is recorded below as an addendum.

### Finding 1 — minimum player count is three, not two

The epic and the ticket both assumed a two-player happy path. `GameSession.java:38` declares
`MINIMUM_PLAYERS_TO_START = 3` and `GameSession.java:246-247` enforces it by throwing
`TooFewPlayersException`, mapped to HTTP 409 in `GlobalExceptionHandler.java:308-309`. The front end
agrees independently: `LobbyScreen.tsx:37` hard-codes `session.players.length >= 3` to enable the
start control. The maximum is six (`GameSession.java:35`).

**Consequence for the suite:** the happy-path scenario is a three-player game, not two. The two
independent hard-coded 3s are a drift hazard worth naming.

### Finding 3 — a full game is 68 card plays, and the opening leader is not seat 0

The entire 68-card printed deck is dealt and nothing is discarded (`Hands.java:169-176`; its
javadoc at `Hands.java:106` gives the three-player split as 23/23/22). Three players means 23 tricks
and 68 plays. The *play* count is invariant at 68 for any seat count, while the *trick* count varies
(17 at four players, 12 at six). There is no shortcut to the terminal screen — there is no
`endSession` function in `ui/src/api.ts` and no such control in any component — so the E2E happy
path must genuinely play all 68 cards, which is why that scenario raises its own timeout with
`test.setTimeout()` rather than relying on the 60-second global in `e2e/playwright.config.ts`.

The opening leader is whoever was dealt the lowest-ranked Tampering card (`Hands.java:216-233`),
**not** seat 0, so the test must discover whose turn it is rather than assume.

### Finding 4 — follow-suit is enforced server-side only, and the UI does not prevent an illegal play

`Trick.java:208-215` enforces follow-suit, and trump grants no exemption. The UI never disables
illegal cards: `GameScreen.tsx:815` uses `disabled={!isMyTurn || isPlayingCard}` and never consults
suit.

**Consequence for the suite:** a test driving only the UI must itself choose a legal card, which is
why `e2e/game.ts` has a `chooseLegalCard` helper that reads the led suit from the trick zone. A
stale read is harmless specifically when leading, because leading permits any card.

### Addendum — the leaderboard is not readable the instant the game ends

Established during EOP-217's gate round rather than before delivery, and recorded because it
constrains every future scenario that asserts on the game-over screen.

Completing a game and recording its result are two steps, and only the first is guaranteed.
`TrickJournal` marks the session completed (`TrickJournal.java:173`), then persists the final
standings on a best-effort basis — the call is wrapped so that a `RuntimeException` is logged and
swallowed (`TrickJournal.java:180-186`) — and then publishes `GAME_COMPLETED`
(`TrickJournal.java:193`, anchor: `GAME_COMPLETED`) whether or not that persist succeeded. A client
woken by that event may therefore read the leaderboard before the result row exists, and
`GetLeaderboardUseCase.java:81` (anchor: `GameResultNotRecordedException`) throws
`GameResultNotRecordedException` for exactly that case, which `GlobalExceptionHandler.java:1017`
maps to **404** — deliberately the same status an absent session gets.

So a 404 from the leaderboard is a legitimate transient, not a failure. The front end already treats
it as one, offering a `Retry loading results` control
(`GameOverScreen.tsx:207`, anchor: `Retry loading results`).

**Consequence for the suite:** `expectGameOver` (`e2e/game.ts:513`, anchor: `expectGameOver`) asserts the `Game over` heading
first, because that heading renders unconditionally, and only then looks for the leaderboard table —
falling back to the screen's own retry control. The distinction that keeps this honest is that the
fallback is conditional on the retry control being present: a genuine 500, or a selector that has
rotted, produces no retry control and the original failure is re-thrown rather than being retried
into a false pass.

### What these mean for the design

ADR-068 designed a suite that drives the application only through the browser. These findings are
all consequences of that choice meeting the real domain: the suite must discover state (whose turn,
which suit) rather than assume it, must respect real domain minimums, and must treat a
legitimately-transient error as transient without blunting a real one.

## Amendment, 2026-09-06 (EOP-218)

EOP-218 delivered a boundary-scenario suite (`e2e/tests/boundary.spec.ts`) that exercises error
paths and edge conditions through three real browsers. The ticket's original scenarios were
factually wrong in four places, and the suite was rewritten against the source before any test was
written. Those corrections are recorded here because they constrain every future E2E scenario.

### Finding 1 — the table holds six, so the *seventh* join is refused

The ticket's Scenario 1 asserted "4th player is rejected". That was wrong on both counts.

`GameSession.java:35` declares `MAXIMUM_PLAYERS = 6`, and `GameSession.java:38` declares
`MINIMUM_PLAYERS_TO_START = 3`. The **seventh** join is refused, with 409 `SessionFullException`
→ detail "This session has no available seats. Try a different join code."

The capacity rule is stated twice on purpose — in `nextSeatOrder()` and in `join(Player, Instant)` —
and the javadoc on the former records that returning the count regardless once made the seventh
join a 400 quoting an internal invariant instead of the 409 the caller is owed.

**Consequence for the suite:** the boundary scenario drives the seventh player through the join
form rather than asserting a disabled control, because no front-end copy of the maximum exists.
`e2e/game.ts:42` (anchor: `MAXIMUM_PLAYERS`) exports `MAXIMUM_PLAYERS = 6` as a mirror of the domain constant, and the test
asserts the refusal message rather than a UI state.

### Finding 2 — duplicate display names are admitted, not rejected

The ticket's Scenario 2 asserted "Duplicate display name is rejected". No such check exists
anywhere in the domain. `DisplayName` appears in only two use cases (`JoinSessionUseCase`,
`CreateSessionUseCase`) and is never compared against seated players; no repository method looks
a player up by name. A second "Alice" is **admitted** at a new seat.

This is pinned as *observed behaviour*, not endorsed. **EOP-230** owns the decision of whether
duplicate names should be rejected, and will update this scenario in the same change if it does.
The test is written to fail loudly if a uniqueness rule is ever introduced without revisiting it:
the assertion is that the duplicate *is* seated.

### Finding 3 — a player who closes their tab mid-game is locked out permanently

The ticket's Scenario 3 asserted "Browser-close reconnect returns the player to the lobby or game
screen". The domain refuses this.

`GameSession.java:196` opens `join(Player, Instant)` with `if (!status.acceptsNewPlayers())`,
and `SessionStatus.java:52-54` returns `true` only in `LOBBY`. An `IN_PROGRESS` session therefore
throws `SessionNotJoinableException` → 409 "This session is no longer in the lobby."

`sessionStorage` (`eop_session`, tab-scoped) is the only place the token lives, and
`ResolvePlayerUseCase` resolves a caller *only* by `IdentityTokenHash`. A player who closes their
tab mid-game is permanently locked out. In `LOBBY` they can re-join, but `nextSeatOrder()` returns
`players.size()`, so they consume a **new** seat and leave a ghost player behind.

This is pinned as *observed behaviour*, not endorsed. **EOP-231** owns the decision of whether a
player who loses their token should be able to return to their seat, and will update both
scenarios in the same change if it does.

### Finding 4 — the minimum-player boundary is unreachable through the UI

The ticket's Scenario 6 asserted "Start with only one player". That is not false, but it tests a
weak boundary, and the server path is unreachable from a browser: `LobbyScreen.tsx:37` computes
`canStartGame = isFacilitator && session !== null && session.players.length >= 3` and line 281
disables the button, so `TooFewPlayersException` can never be provoked through the UI.

The real boundary a user meets is the disabled attribute at two players and enabled at three.
Scenario 6 therefore asserts the control's state rather than the 409, which belongs to the Java
unit tests. **EOP-232** covers the duplicated literal: `LobbyScreen.tsx:37` hard-codes `3`,
`e2e/game.ts:31` (anchor: `MINIMUM_PLAYERS_TO_START`) is a third copy, and the domain is the single source of truth.

### Finding 5 — the anti-oracle property is pinned at the E2E tier

Scenario 4 drives `JoinCode.parse` through the browser to assert that an unknown code and a
malformed code receive identical error messages. This is the **anti-enumeration-oracle** property
stated in `JoinCode.java:65-72`:

> Returns an empty optional rather than throwing, because the caller's response to "that is not a
> code" and to "no session has that code" must be identical. Distinguishing them would turn the
> join endpoint into an oracle that confirms which codes are real, which is exactly the help an
> attacker enumerating the keyspace needs.

The test drives `ZZZZZZZZ` (well-formed, all chars in the Crockford base32 `ALPHABET`, no such
session) and `UUUUUUUU` (unparseable — `U` is excluded from the alphabet and deliberately *not*
folded, unlike `O`→`0`, `I`→`1`, `L`→`1`) and asserts the two rendered messages are equal.

This property is asserted nowhere else at the E2E tier. The Java unit tests cover the parsing
logic, but only a browser test proves the end-to-end path: the HTTP layer, the use case, the
exception handler and the front-end's rendering of the `detail` field all participate, and any
rewiring that broke the equality would be invisible to a unit test.

### Finding 6 — the flag-OFF scenario is out of scope at this tier

The ticket's original Scenario 6 asked for an E2E run with `VITE_GAME_SCREEN_ENABLED` OFF. That
was **dropped**, not rewritten. The flag is a build-time Vite variable (ADR-037), substituted into
the bundle at build time, so a flag-off scenario would need a second UI image and a second stack
per run. The behaviour is already asserted at `ui/src/App.test.tsx:190` in milliseconds; the E2E
tier adds nothing there.

### Design decision — `expectJoinRefused` as a sibling, not a flag

`e2e/game.ts:252` (anchor: `expectJoinRefused`) exports `expectJoinRefused(seat, joinCode): Promise<string>` as a deliberate
*sibling* of `joinSession` rather than a flag on it. `joinSession` asserts the `Game Lobby`
heading, and the happy-path suite (EOP-217) depends on it staying strict. `expectJoinRefused`
returns the rendered message rather than asserting it, so callers can compare two refusals for
equality — the anti-oracle scenario uses exactly this to prove the unknown-code and malformed-code
messages are identical.

### Test evidence

`cd e2e && npx playwright test --reporter=list` → **33 passed (2.0m)**, 1 worker, zero
failures/flakes/skips. 11 tests per project across chromium, firefox, webkit: 6 boundary + 4
happy-path + 1 smoke. Slowest boundary test 4.5s (WebKit). The pre-existing happy-path and smoke
specs still pass, evidencing that leaving `joinSession` strict regressed nothing.

Every rewritten scenario passes against **unmodified production code** — that is the proof the
rewrite describes actual behaviour rather than aspiration.

### Follow-up tasks

Three findings were filed as Jira Tasks (the project has no Bug type), all linked `Relates` to
EOP-218:

- **EOP-230** — decide whether duplicate display names should be rejected.
- **EOP-231** — a player who loses their session token cannot return to their seat (covers both
  the mid-game lockout and the LOBBY ghost seat).
- **EOP-232** — `LobbyScreen.tsx:37` duplicates the minimum-players rule as a hardcoded `3`;
  `e2e/game.ts:31` (anchor: `MINIMUM_PLAYERS_TO_START`) is a legitimate third copy.

## Amendment, 2026-09-06 (EOP-219)

EOP-219 delivered the leaderboard suite (`e2e/tests/leaderboard.spec.ts`), covering the end-of-game
summary screen. Four of the ticket's five acceptance criteria described behaviour the application
does not have, so they were rewritten before any test was written — the same pattern as EOP-218,
and for the same reason: the criteria were authored from the API surface rather than from the code.

### Finding 1 — the leaderboard is per-session, and no historical leaderboard exists

The ticket asked for a scenario in which a leaderboard shows "the current game's players **and**
historical rows from the seeded prior game". No such view exists and none is planned.
`GetLeaderboardUseCase.java:76` (anchor: `GameNotCompletedException`) refuses any session that is
not `COMPLETED`, and the projection it builds is scoped to the one session in the path. The
persistence layer reinforces this: one result row is kept per session so that the leaderboard
always reflects the latest completed game of *that* session.

So there is nothing to seed. The criterion was replaced by an **isolation** scenario, which is the
useful property in the same area: a leaderboard must show only its own session's players even
though other completed games share the database. That is not vacuous here, because the three
browser projects run serially against one stack, so by the time Firefox and WebKit complete their
games Chromium's finished game is already persisted.

### Finding 2 — a new game returns to `IN_PROGRESS`, never to `LOBBY`

The ticket's fourth criterion expected `POST /new-game` to move the session "back to LOBBY" with
"all players returned to the lobby screen". It does not. The use case clears tricks and hands,
resets the session straight to `IN_PROGRESS` and deals a fresh deck to the same players in the
same seats (`NewGameUseCase.java:130`, anchor: `resetToInProgress`).
`SessionStatus.LOBBY` is not re-entered by any code path.

The front end briefly *renders* the lobby — `ui/src/App.tsx:192` (anchor: `re-dealt`) routes the
facilitator through `screen: 'lobby'` after the 204 — but `LobbyScreen` observes `IN_PROGRESS` and
forwards immediately to the game screen. It is a transition, not a destination, and it is the same
path a mid-game reload already takes (EOP-217's fourth scenario).

### Finding 3 — a tie cannot be arranged, so the tie rules are asserted as invariants

The ticket asked for a scenario in which "two players finish with equal points". There is no way to
arrange that. A score is **derived, never stored**: `ScoreSheet` recomputes every total from the
whole trick history on each read (ADR-030), so a tie cannot be seeded through SQL, and no API
forces one. Playing towards a tie is not available either, because the outcome depends on which
cards the shuffle dealt.

The scenario therefore asserts the ranking *rules* against whatever the game produced, which holds
for every possible outcome and is strictly stronger than one arranged case:

- totals are ordered descending;
- `position` is competition ranking — it repeats for equal totals and skips accordingly;
- `tied` is true **exactly** when another player shares that total, asserted in both directions
  against `ScoreSheet.java:177` (anchor: `Collections.frequency`);
- the set at `position === 1` is non-empty, and every member's `tied` flag agrees on whether first
  place is shared, so no player is ever rendered as sole winner of a shared lead.

### Finding 4 — the flag-OFF scenario is out of scope, on EOP-218's precedent

The ticket's fifth criterion asked for a run with `VITE_GAME_SCREEN_ENABLED` OFF. **Dropped, not
rewritten**, for exactly the reason recorded in the EOP-218 amendment's Finding 6: the flag is a
build-time Vite variable (ADR-037) baked in at `ui/Dockerfile:40`, so covering it needs a second
image and a second stack per run, and `ui/src/App.test.tsx:190` already asserts it in milliseconds.
`GameOverControllerDisabledIntegrationTest` covers the back-end half.

Note that the leaderboard *does* sit behind a back-end flag, `eop.features.game-over`, contrary to
the ticket's claim that no `eop.features.*` flag applies. It needs no override in `compose.e2e.yml`
because all three back-end flags ship `true`. That flag carries an expiry of 2026-09-18 under
ADR-042, so this suite will need a review when `EOP-83` deletes it.

### Finding 5 — a participant is stranded when the facilitator starts a new game

Found by this suite, filed as **EOP-233**. When the facilitator starts a second game, the
facilitator advances to it and every other player stays mounted on the previous game's leaderboard
indefinitely.

The cause is an absent subscription. `LobbyScreen` and `GameScreen` both open a session
subscription; `GameOverScreen` opens none — it fetches the leaderboard once on mount. So although
`NewGameUseCase` publishes `HAND_DEALT` before the 204 returns, no subscriber exists on that page
to act on it. The facilitator moves only because its own `onNewGame` callback navigates it locally.

This is not a race. The event is published before the response the facilitator's navigation depends
on, so any subscriber would already have been notified by the time the facilitator's new hand
renders — which is what makes the scenario deterministic rather than flaky.

A reload recovers the stranded player, via `sessionStorage` and the transitional lobby, which is why
EOP-233 is rated medium rather than a lockout. The scenario pins **current** behaviour, says so in
a comment naming the ticket, and asserts the reload escape; the two assertions that encode the
defect carry failure messages telling a future fixer to invert them.

The scenario is self-checking in a way worth noting, because a test asserting an absence usually is
not: the same hand locator is asserted absent while stranded and then present after the reload
within one test, so a broken locator cannot produce a false pass.

### Design decision — capture the response the UI already made

`e2e/tests/leaderboard.spec.ts:170` (anchor: `captureLeaderboards`) installs a `page.on('response')`
listener before the game completes and keeps the last parsed leaderboard body. Every rendered cell
is then compared against the payload the server actually sent, including `sessionStatus`, which the
screen does not render at all.

This was chosen over re-issuing the request from the test. Doing that would require the
player-token header name, and asserting against a *second* response would prove only that two
requests agree — not that the table on screen matches the bytes that produced it. The capture also
keeps the suite honest about the tier: nothing is stubbed and no request is intercepted.

`e2e/tests/leaderboard.spec.ts:82` (anchor: `STRIDE_COLUMNS`) mirrors the six STRIDE column labels
in canonical order, so a column reordering or a renamed header fails here as well as in the
front-end unit tests.

### Design decision — one deck per engine, in a serial describe block

A complete game is 68 plays (EOP-217, Finding 3) and takes minutes per engine. Four scenarios each
playing their own game would have tripled the suite's runtime for no additional coverage, since all
four interrogate the same completed game.

The block is therefore `test.describe.serial`, with a `beforeAll` that forms the session, plays the
deck out and reaches game-over on every seat; the four scenarios assert against that one fixture,
and the destructive new-game scenario is declared **last**. This is safe because
`playwright.config.ts` already runs `workers: 1` with `fullyParallel: false`. The visible effect is
that the leaderboard tests report single-digit millisecond durations while their shared setup
carries the whole cost.

### Test evidence

`cd e2e && npx playwright test --reporter=list` → **45 passed (2.5m)**, 1 worker, zero
failures/flakes/skips, green on first execution. 15 tests per project across chromium, firefox and
webkit: 6 boundary + 4 happy-path + 4 leaderboard + 1 smoke. The pre-existing 33 tests are
unchanged and still pass.

Every scenario passes against **unmodified production code**, including the two assertions that
encode EOP-233 — that is what makes them a record of behaviour rather than of intent.

### Follow-up tasks

- **EOP-233** — a participant is stranded on the stale leaderboard when the facilitator starts a
  new game. Filed as a Jira Task (the project has no Bug type), linked `Relates` to EOP-219.
