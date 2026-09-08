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
(`GameOverScreen.tsx:326`, anchor: `Retry loading results`).

**Consequence for the suite:** `expectGameOver` (`e2e/game.ts:636`, anchor: `expectGameOver`) asserts the `Game over` heading
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

**EOP-230 has now decided to accept this behaviour.** The decision is recorded in the
[2026-09-08 amendment to ADR-015](ADR-015-player-identity.md#amendments), which argues the
rejection alternative and the display-only disambiguation alternative, and rejects both. The
scenario therefore stands as the deliberate pin. The test is written to fail loudly if a
uniqueness rule is ever introduced without revisiting it: the assertion is that the duplicate
*is* seated.

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

**EOP-231 has now decided to accept both halves of this.** The decision is recorded in the
[2026-09-08 amendment to ADR-015](ADR-015-player-identity.md#amendments), which argues moving the
token to `localStorage`, issuing a short-lived reconnect code, and releasing the seat of an absent
player, and rejects all three. Scenarios 4 and 5 therefore stand as the deliberate pins, and their
assertions are unchanged — only their comments stop describing a settled decision as pending.

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

`e2e/game.ts:376` (anchor: `expectJoinRefused`) exports `expectJoinRefused(seat, joinCode): Promise<string>` as a deliberate
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

- **EOP-230** — duplicate display names are admitted. The decision is recorded in the
  [2026-09-08 amendment to ADR-015](ADR-015-player-identity.md#amendments).
- **EOP-231** — a player who loses their session token cannot return to their seat (covers both
  the mid-game lockout and the LOBBY ghost seat). The decision is recorded in the
  [2026-09-08 amendment to ADR-015](ADR-015-player-identity.md#amendments).
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

The front end used to briefly *render* the lobby: it routed the facilitator through
`screen: 'lobby'` after the 204, and `LobbyScreen`, observing `IN_PROGRESS`, forwarded immediately to
the game screen. It was a transition, not a destination, and the same path a mid-game reload takes
(EOP-217's fourth scenario). **EOP-233 removed it** — `App.tsx` now routes every seat straight to the
game screen on the session `GameOverScreen` observed (`ui/src/App.tsx:204`, anchor: `screen: 'game'`),
so no post-new-game path passes through the lobby at all. The finding above is unaffected: the domain
never re-entered `LOBBY`, and the front end no longer suggests otherwise. See the EOP-233 amendment.

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

**Closed by EOP-233 on 2026-09-08; the record below describes the behaviour as EOP-219 found it, and
the two assertions it names have since been inverted. See the EOP-233 amendment.**

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

`e2e/tests/leaderboard.spec.ts:172` (anchor: `captureLeaderboards`) installs a `page.on('response')`
listener before the game completes and keeps the last parsed leaderboard body. Every rendered cell
is then compared against the payload the server actually sent, including `sessionStatus`, which the
screen does not render at all.

This was chosen over re-issuing the request from the test. Doing that would require the
player-token header name, and asserting against a *second* response would prove only that two
requests agree — not that the table on screen matches the bytes that produced it. The capture also
keeps the suite honest about the tier: nothing is stubbed and no request is intercepted.

`e2e/tests/leaderboard.spec.ts:84` (anchor: `STRIDE_COLUMNS`) mirrors the six STRIDE column labels
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

## Amendment, 2026-09-08 (EOP-238)

EOP-238 triaged the first-ever Firefox flake in the E2E suite (CI run 34101389640 on `main` at `37ff946`, the push after PR #393 merged EOP-220). The ticket hypothesised either a test-side race in the seat-loss assertion or a genuine application race in SSE disconnect handling. **Both hypotheses were wrong**, and the triage produced two factual corrections plus a fix.

### Finding A — the trace of the failing attempt does not exist

`e2e/playwright.config.ts` sets `trace: 'on-first-retry'`, which records during the *retry*, not the original attempt. The only `trace.zip` in the retained report belongs to the passing retry (`…-firefox-retry1/`). Attempt 0's retained evidence is three screenshots plus `error-context.md` (an ARIA snapshot), and the server-side Caddy access log from the `e2e-stack-logs` artefact.

### Finding B — the failure was in scenario setup, not seat loss

The failing assertion was at `e2e/tests/boundary.spec.ts:197`, which is `joinSession(stayer, joinCode)` — the third player's (Carol's) join, inside the *shared* `joinSession` helper. It is before `startGame` and long before `leaver.context.close()`, so the seat-loss/SSE-disconnect path was never reached. The error was `expect(getByRole('heading', {name:'Game Lobby', level:1})).toBeVisible()` timing out after 10 s.

### The evidence chain (lost submit)

Cross-referencing the ARIA snapshot against every branch of `ui/src/components/JoinSessionForm.tsx`'s `handleSubmit`:
- Carol's page loaded fine (`GET /` at 08:43:35.439 in the Caddy log).
- At failure the join form was still rendered with both controlled inputs holding correct values (`#join-code` an 8-char code, `#display-name` `Carol`).
- **No** `role="alert"` / GOV.UK error summary anywhere in the snapshot → `validate()` did not fail and no fetch rejection was rendered.
- The submit button read `Join a session` and was enabled → `isSubmitting === false`, so no request was in flight.
- The Caddy access log shows **zero** `POST /api/v1/sessions/{code}/players` for Carol across the entire 10.26 s of silence, and the `eop-e2e-app` container logged **nothing** in the window. The server was idle, not slow.

Therefore the form's `onSubmit` never fired even though Playwright reported the click as successful — a **lost click / lost submit**. This is a test-side defect, not an application race; no lower-level failing test is required, and neither ADR-069 nor ADR-070 is touched.

### The Firefox-specific ingredient

Both forms autofocus their first field (`JoinSessionForm.tsx:106`, `CreateSessionForm.tsx:99`). Firefox processes the autofocus candidate at the end of a rendering opportunity and scrolls the element into view when it does, so a late flush can move the page between Playwright's hit-test coordinate computation and the dispatched mouse event, landing the click off the button. That is consistent with every observation and with chromium and webkit passing first time — but it **cannot be proven** from the retained artefacts, because of Finding A. The mechanism is stated as consistent with the evidence, not as a confirmed cause.

### The fix

All in `e2e/game.ts`:
- `SUBMIT_ATTEMPTS = 3` (`e2e/game.ts:195`, anchor: `SUBMIT_ATTEMPTS`) and `SUBMIT_EFFECT_TIMEOUT_MS = 2_000` (`e2e/game.ts:206`, anchor: `SUBMIT_EFFECT_TIMEOUT_MS`).
- `expectFormReady(seat, field, formName)` (`e2e/game.ts:223`, anchor: `expectFormReady`) asserts the first field `toBeFocused()` — an observable condition proving Firefox's autofocus flush and its scroll-into-view already happened, closing the window *before* anything is typed. A condition on state, never a longer timeout.
- `submitUntilHandled(seat, submitName, busyName)` (`e2e/game.ts:246`, anchor: `submitUntilHandled`) clicks, then polls a disjunction of three observable effects — the busy/relabelled button, a `role="alert"` refusal summary, or the `Game Lobby` heading — and re-dispatches the click only while none of them is observed, up to three attempts, then throws with a message naming the condition that failed.
- `fillJoinForm(seat, joinCode)` (`e2e/game.ts:285`, anchor: `fillJoinForm`) asserts the fields committed their values, asserting the join code's **length** and never its value (ADR-071: a failing matcher prints its received value and a custom message becomes a step title even on success, so either channel would publish the secret; length is also invariant under the field's own uppercasing).
- All three call sites — `createSession` (`e2e/game.ts:314`), `joinSession` (`e2e/game.ts:342`) and `expectJoinRefused` (`e2e/game.ts:376`) — were rewired onto these helpers, because all three had the identical blind click→assert shape and the same autofocus exposure.

### The safety invariant

Re-dispatching a click is normally dangerous — a second successful submit would double-join. It is safe **only** under the proof the disjunction gives: every branch of `handleSubmit` leaves something on screen, so their joint absence together with an idle, unrelabelled button is positive evidence that `onSubmit` did not fire and therefore that no request was issued. A submit that took effect is never re-clicked. This is the load-bearing invariant, and it is a property of the front-end's four observable states — so a future change to `JoinSessionForm` or `CreateSessionForm` that removes the busy relabelling, or renders a refusal without `role="alert"`, silently weakens the helper. That coupling is what a reviewer must watch.

### What would have proven the Firefox mechanism

`trace: 'on-first-retry'` records during the retry, not the original attempt. Changing it to `trace: 'on-failure'` would have captured the failing attempt's trace, which would have shown whether the click landed on the button element or elsewhere. That change was not made in this story, because the fix works regardless of the mechanism's confirmation — but the next flake triager should know the difference.

### Correction to prior prose

The EOP-217, EOP-218 and EOP-219 amendments describe `joinSession`, `createSession` and `expectJoinRefused` as adequate for their scenarios. They are — they passed — but the shape they shared (click the submit button, then assert the lobby heading) exposed every caller to the lost-click window. The helpers were not wrong for the scenarios that used them; they were incomplete for the browser engine that ran them. This amendment's rewiring makes them complete.

## Amendment, 2026-09-08 (EOP-233)

EOP-233 closed Finding 5. The fix is in production code, not in the suite, and the two assertions the
scenario carried for "a future fixer to invert" have been inverted. This amendment records what
changed, and two things the fix turned up that the original finding did not name.

### The fix

`GameOverScreen` now opens a session subscription, which is what every other screen already did. The
doorbell carries no state (ADR-015), so each ring provokes a `getSession` read, and a status of
`IN_PROGRESS` — the only status a second game produces — invokes a callback carrying the observed
session. `App.tsx` routes on that callback.

**One destination for every seat is the point of the shape.** The old code navigated the facilitator
off the back of its own 204 and navigated nobody else at all, so the two paths could diverge — and
did, silently, for as long as this screen opened no subscription. The facilitator now takes the same
route as a participant: `startNewGame` awaits the 204 and then performs the same read, so a single
code path serves both. Losing that read is not a lockout even for the facilitator, because its own
subscription is open and the `HAND_DEALT` its call published rings its own doorbell.

The transitional `screen: 'lobby'` is gone, and Finding 2's second paragraph has been corrected in
place. Routing every seat straight to the game screen is possible precisely because the callback
carries the session the screen already read — the caller needs no second request to build the view.

### Correction — the once-only guard has to sit *after* the read

The first implementation checked a `useRef` "already routed" flag before awaiting `getSession`. A unit
test ringing four doorbells in one `act` block showed the callback firing **four** times: every ring
cleared the flag before any of their reads landed. The check has to be repeated after the await, and
the pre-await check dropped entirely — TypeScript narrows the ref to `false` after the first read of
it, so keeping both makes the second one dead code that `@typescript-eslint/no-unnecessary-condition`
correctly fails the lint on.

Each ring still gets its own read. Dropping a ring while a read is in flight would be cheaper, and was
rejected: a read issued before the reset committed can answer `COMPLETED`, so the dropped ring might
have been the only one whose read would have seen the second game — reintroducing the defect in a
narrower window. Firing once while reading N times is the correct trade, and it is what `LobbyScreen`
already does.

### A dead stream is now visible rather than silent

If the event stream fails other than by refusing the token, the screen shows `Lost the live connection
to this session. Reload the page if the facilitator starts another game.` A dead doorbell puts this
screen back in exactly the position Finding 5 describes, so it says so, and it names the one recovery
still open. The message never displaces an existing one: a leaderboard that failed to load is the more
specific problem and owns that slot. A 403 or 404 still ejects the seat, as the leaderboard read does.

### What the suite asserts now, and what was deleted

The fourth scenario asserts that **every** seat is dealt into the second game and that no seat is
still showing the finished game's leaderboard. The reload block was **deleted, not adapted**: reloading
was a stranded participant's only escape, so asserting it after the fix would hide a regression behind
the very workaround the fix removes. Deleting it also removes three 30-second reload waits.

Note the deck-arithmetic assertions that close the scenario were previously load-bearing on that
reload — they count hands on every seat, which could only pass once the reloads had recovered the
participants. They now assert the same thing about a state reached without any manual intervention,
which is strictly stronger.

### Coverage boundary

`GameOverScreen.test.tsx` covers the subscription: routing on `IN_PROGRESS`, staying put on
`COMPLETED`, the `onOpen` catch-up read (EOP-224), firing once under a burst, the facilitator's path
through the same read, the 403 ejection, the dead-stream message, and unsubscribing on unmount —
20 tests in that file, 274 across the front end.

`App.tsx`'s routing of that callback is covered by the E2E scenario rather than by a unit test, which
is a deliberate boundary and worth stating rather than leaving to be noticed: `App.test.tsx` drives
real components against a stubbed `fetch`, so reaching game-over there means playing a whole game
through stubs. The game-screen-to-game-over routing beside it is untested for the same reason. If that
boundary is to move, it moves for both.
