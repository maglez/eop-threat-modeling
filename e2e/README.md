# End-to-end UI tests

Playwright suite that drives the real application through a real browser against a
real container stack. It is the fourth test tier in this repository, alongside the
JUnit unit tests, the Spring integration/API tests and the k6 load tests.

See [ADR-068](../docs/adr/ADR-068-playwright-e2e-testing.md) for the decisions
behind this directory. **Which artefacts may be published, and to whom, is
[ADR-071](../docs/adr/ADR-071-e2e-artefact-publication-boundary.md) — read it before
wiring the report into CI.** How the suite *runs* in CI is still unwritten and is due
with `EOP-220`; it has no ADR number reserved, because 069 and 070 were taken by
unrelated decisions while this directory was being built.

## What it tests

The suite exercises the **shipped artefacts**, not a test-only assembly. It runs the
production `Dockerfile` app image and the production `ui/Dockerfile` image with its
own `ui/Caddyfile` baked in, so the security headers (ADR-035), the 16 KB request
body cap (ADR-033), the source-map refusal (EOP-107) and the SPA fallback are all
live and under test rather than stubbed out.

### One consequence for whoever writes the first error-path scenario

The stack runs with `SPRING_PROFILES_ACTIVE=prod`, and `application-prod.yml` sets
`server.error.include-message: never` and `server.error.include-binding-errors: never`.
Spring therefore injects no message text of its own, so an error response body carries
**only** what `GlobalExceptionHandler` authored — the RFC 9457 `type`, `title` and
`status`, plus any field-level messages a `ValidationProblemDetail` supplies.

This matters because it diverges from what a default-profile `@SpringBootTest` sees. An
assertion written against a Spring-generated message string will pass in the Java suite
and fail here, and the failure will look like a missing element rather than a wrong
expectation. Assert against domain-authored text, which does survive. This is the
production behaviour and testing against it is the point — it is only a trap if you
did not know about it.

## Prerequisites

| Requirement | Why |
|---|---|
| A container runtime, started | The suite starts `compose.e2e.yml`. `colima start` — Docker Desktop is deliberately not used (ADR-016) |
| Node >= 22.12 | The repository floor, set by `ui/package.json`. Enforced by `engines` in `package.json` |
| Browser binaries | `npm run browsers` (a wrapper for `playwright install --with-deps`) |

`npm install` in this directory installs `@playwright/test` itself; the browser
binaries are a separate, larger download cached in your home directory.

## Building the two images

The compose file has **no `build:` section**, matching `compose.app.yml`. Build both
images from the repository root first:

```bash
docker build -t eop-threat-modeling:local .
docker build -t eop-ui:e2e --build-arg VITE_GAME_SCREEN_ENABLED=true ui
```

Two things about the second command are load-bearing.

**The `--build-arg` is required, not optional.** `ui/Dockerfile` defaults all three
`VITE_*` feature flags to `false` (fail-closed, ADR-037), and these flags are
resolved at *build* time and substituted into the bundle — there is no runtime
environment variable that can turn the game screen on afterwards. An image built
without it serves an application whose game screen never renders, so every scenario
past the lobby fails for a reason that looks nothing like its cause.

**The tag is `eop-ui:e2e`, not `eop-ui:local`.** The two are not interchangeable, and
the distinct tag is what makes that visible in `docker images`. `eop-ui:local` is the
flags-off image that `compose.app.yml` uses.

## Running

```bash
npm test                  # all three browsers
npm run test:chromium     # one browser, for a fast local loop
npm run test:headed       # watch it happen
npm run report            # open the HTML report from the last run
npm run typecheck         # tsc --noEmit
npm run verify            # typecheck, then the full suite
```

`npm test` is self-contained: `global-setup.ts` starts the stack and waits for it,
the tests run, and `global-teardown.ts` destroys the stack **and its volumes**. You
do not need to start anything by hand, and a run leaves nothing behind.

### The health gate

`global-setup.ts` does not trust `docker compose up --wait` on its own. That flag
proves each container's *internal* healthcheck passed — the app probes
`http://127.0.0.1:8080/health` from inside itself, the UI image probes its own
`https://localhost:8080/index.html` — which says nothing about whether the port is
published to the host or whether the TLS handshake the browsers depend on actually
completes. So setup additionally polls `https://localhost:8443/health` from the host
until it returns 200 **with a trimmed body of exactly `OK`**.

The body check is not belt-and-braces. A Caddy instance that matched the wrong route,
or no site block at all, answers `200` with an empty body — so a status-only check
would wave through precisely the misconfiguration most likely to occur.

## What may be published, and where — read this before wiring CI

**The HTML report must never be served from GitHub Pages or any other page this repository
publishes.** Note what that sentence does *not* say. This repository is **public**, so a
GitHub Actions artefact and a CI job log are world-readable too — there is no private
destination in this pipeline. The report's remaining exposure as an artefact is therefore
explicitly *accepted*, on the reasoning that a leaked join code names a session already
destroyed by `down -v`, and not *contained*. The decision, the measurements behind it, the
channels it leaves open and the rejected alternatives are
[ADR-071](../docs/adr/ADR-071-e2e-artefact-publication-boundary.md); this is the
summary the next author needs.

| Artefact | Contains a secret? | Destination |
|---|---|---|
| `results.json` | No — verified on a passing and a failing run, and kept that way by the length-only assertion in `game.ts` | The world-readable page `EOP-221` builds. This is the **only** artefact that may be published as the page |
| `playwright-report/` (incl. `data/`) | **Yes, on every run** — a live join code per session, twice over | Actions artefact only, never the page. World-readable even so; see the acceptance in ADR-071 |
| `test-results/` (`trace.zip`, PNGs, `error-context.md`) | **Yes, on failure** — live player tokens, verbatim | Actions artefact only, never the page |

**So: a trace is never safe to publish, and neither is the HTML report — not even from a
run that passed.** That last clause is the counter-intuitive part and the reason this
section exists. Playwright's `html` reporter embeds its whole step tree as a base64 zip
inside a `<template id="playwrightReportBase64">` element, and a step title records both
a custom `expect()` message and the value handed to `locator.fill()` — on success as
well as on failure. Decoding that element on a green 15/15 run recovered **12 distinct
live join codes**. Player *tokens* behave as you would expect and appear only in a
failing run's `trace.zip`, so the two secrets have different profiles and only one of
them is failure-conditional.

`fill()` embedding the join code cannot be fixed here: typing a real code into
`#join-code` is what this tier is *for*, and suppressing the step would mean not
exercising real user input. That is why the control is the destination rather than the
content, and why `E2eArtefactPublicationBoundaryTest` in `src/test/java/org/maglez/eop/docs/`
fails `./mvnw verify` if a workflow step names `playwright-report` alongside a Pages
publisher.

Three rules for writing scenarios, all build-enforced by that test:

- **Never interpolate a secret into an assertion message.** A custom `expect()` message
  becomes a step title whether or not the assertion fires, so
  `` expect(joinCode, `join code ${joinCode} …`) `` published the code on every green
  run. Report a length or an identifier — `createSession` now says
  `` `the lobby's join code is ${joinCode.length} characters, not eight` ``.
- **Never assert *on* a secret value either.** A custom message is not enough: a failing
  matcher prints the value it received back, so `expect(joinCode, msg).toHaveLength(8)`
  emits `Received string: "…"` alongside your message, into both the report and
  `results.json`. Assert on the derived quantity instead — `createSession` uses
  `expect(joinCode.length, msg).toBe(8)`. The one exception the gate allows is
  `.not.toBeNull()`, which can only fail when the value *is* null and so can never print it.
- **Never return a secret from `page.evaluate`.** Compare it in the page and return a
  boolean. The reload scenario evaluates
  `sessionStorage.getItem('eop_session') !== null`, because non-nullness is all it needs;
  returning the value pulled `{playerToken, playerId, sessionId}` into the test process,
  where the trace records it.

To re-check the report yourself after changing a scenario — this works on a **passing**
run, which is the point:

```bash
python3 - <<'EOF'
import base64, io, re, zipfile
src = open('playwright-report/index.html', encoding='utf-8').read()
m = re.search(r'<template id="playwrightReportBase64">data:application/zip;base64,(.*?)</template>', src, re.S)
z = zipfile.ZipFile(io.BytesIO(base64.b64decode(m.group(1).strip())))
for n in z.namelist():
    body = z.read(n).decode('utf-8', 'replace')
    print(n, sorted(set(re.findall(r'"title":"(?:join code |Fill \\")([A-Z0-9]{8})', body))))
EOF
```

A non-empty list is a leak into the report. It is expected to be non-empty for the
`Fill` channel and must be empty for the `join code` channel.

Three channels this does **not** cover, which matter when you wire CI:

- **The job log.** `['list']` prints to stdout, and a CI log on a public repository is
  world-readable. Measured on four real passing runs it carries no step tree and no join
  code, but a *failing* assertion message reaches it — which is the second reason the
  length-only rule above exists.
- **Container logs.** `E2E_REUSE_STACK` exists so CI can collect them after the tests, and
  the join code is a URL path segment (`/api/v1/sessions/{joinCode}/players`), so every
  Caddy access line for a join request contains a live one. Publishing those logs inherits
  ADR-071's acceptance.
- **`blob-report/`.** Not produced by this configuration, but it is a merge-ready form of
  the same step tree, so it is subject to the same rule as `playwright-report/`.

When `EOP-221` lands and a report URL exists, it belongs in this section, next to the
table that says what is behind it.


## Escape hatches

Three environment variables, all off by default. Each is read with a strict parser
that accepts only `1`, `true`, `yes` or `on` (case-insensitively) and fails closed on
anything else, so a typo disables the hatch rather than silently enabling it.

| Variable | Effect |
|---|---|
| `E2E_REUSE_STACK` | Skip `up` **and** skip teardown; assume a stack is already running. The health wait still runs. This is what CI uses, so that it owns the stack and can collect container logs *after* the tests — a teardown here would delete the evidence |
| `E2E_KEEP_STACK` | Start the stack normally but skip teardown, to inspect a failure. The next run starts against a dirty database |
| `E2E_BASE_URL` | Point the suite somewhere else entirely. See the constraint below before changing it |

### Why the URL is `https://localhost:8443`

Every part of that is constrained.

**`https`, not `http`.** Nothing listens on plain HTTP. `ui/Caddyfile` sets
`auto_https disable_redirects` and its only site block serves `tls internal`, and
that Caddyfile is `COPY`d into the image — so plain HTTP is not a configuration
choice, it is absent from the artefact. The suite sets `ignoreHTTPSErrors: true`,
which is the same trade the repository already makes four times over: `curl -fsSk`
in the CI smoke test, `--insecure-skip-tls-verify` in the k6 canary and
`wget --no-check-certificate` in the image's own `HEALTHCHECK`.

**`localhost`, not `127.0.0.1`.** Caddy aborts the handshake server-side on a
non-matching non-empty SNI, and `default_sni` does not rescue it — a client-side
insecure flag cannot help, because the failure is on the server. This constraint was
established the hard way in EOP-160 (an ADR-055 amendment), which found four dead
alternatives before settling on addressing the target as `localhost`.

**`8443`, not `443` or `8080`.** Host `443` is `compose.app.yml`'s, so using it would
collide with a developer's running stack. Host `8080` is what a local
`./mvnw spring-boot:run` binds. Publishing on `8443` while the container still listens
on `8080` is safe because Caddy matches a site block on **hostname alone and ignores
the port** — verified empirically, both here and by CI's own smoke test, which curls
`https://localhost/health` on port 443 against the same `localhost:8080` site block.

## Isolation from the other stacks

`compose.e2e.yml` is a third, standalone stack, deliberately not an override of
`compose.app.yml`. An override would inherit its project name, network and volumes,
so starting the tests would destroy a developer's running application. Instead:

- project name `eop-e2e` (vs `eop-app`)
- subnet `172.29.0.0/24` (vs `172.28.0.0/24`)
- volumes `eop_e2e_postgres_data` / `eop_e2e_caddy_data`
- containers `eop-e2e-*`
- host port `8443` (vs `443`)

Both stacks can run at once. Teardown's `down -v` guarantees a fresh database per
run, which the leaderboard scenarios in EOP-219 require rather than merely prefer.

## Two deliberate omissions

These are decisions, not oversights.

**No `"type": "module"` in `package.json`.** Playwright's TypeScript loader emits
CommonJS, which is what lets `__dirname` resolve in `stack.ts`. `tsconfig.json`
declares `module: CommonJS` to match, so a typecheck exercises the same module
system the suite actually runs under. Declaring ESM would move a `__dirname` failure
from build time to run time.

**No ESLint.** `ui/` has one; this directory does not. The strict `tsconfig.json` —
which mirrors `ui/`'s full strictness block, including `noUncheckedIndexedAccess` and
`exactOptionalPropertyTypes` — plus `npm run typecheck` is the quality gate here.

## Layout

```
e2e/
  package.json          # @playwright/test, scripts, engines.node >= 22.12
  tsconfig.json         # strict, CommonJS; consumed only by npm run typecheck
  playwright.config.ts  # three browser projects, serial, baseURL, reporters
  stack.ts              # shared compose helpers and the env-var parsers
  global-setup.ts       # compose up --wait, then the host-side health poll
  global-teardown.ts    # compose down -v
  tests/
    smoke.spec.ts       # asserts the home screen h1 renders
    happy-path.spec.ts  # full lifecycle: create, join, start, play, complete, reload
    boundary.spec.ts    # refusals — bad join code, full session, non-facilitator start
    leaderboard.spec.ts # results across sessions, ordering and the whole-history read
```

`stack.ts` exists so setup and teardown cannot drift apart on which compose file or
project they act against. It shells out with `execFileSync` rather than `execSync`,
so no shell can misinterpret a metacharacter arriving from an environment variable.

## Why the suite runs serially

`playwright.config.ts` sets `fullyParallel: false` and `workers: 1`. There is one
application instance and one database; the leaderboard reads whole-session history
(ADR-030); and — the binding constraint — both rate limiters key on the resolved
client address, which is identical for every browser Playwright launches, so all
projects share a single bucket.

This is a starting position, not a permanent one. The cost is honest: wall-clock time
is roughly the sum of the three browsers rather than the maximum. To relax it, opt
individual files in with `test.describe.configure({ mode: 'parallel' })` and measure,
rather than flipping the global switch.

### Rate limits in the test stack

`compose.e2e.yml` raises `EOP_WEB_SESSION_CREATION_LIMIT` and
`EOP_WEB_READ_RATE_LIMIT_LIMIT` to `Integer.MAX_VALUE`. At shipped values the whole
suite would share 5 session creations and 300 reads per 60 seconds, against a
two-player game that alone peaks near 100 reads a minute. This mirrors what
`src/test/resources/application.properties` already does for the Java integration
tests, and for the same reason.

**So this suite must never be cited as evidence that rate limiting works.** The
limiters still execute and still count — only the ceiling moved, so the code path is
exercised rather than bypassed — but the production thresholds are verified by the
Java integration tests, which pin a low limit per class with `@DirtiesContext`.
