# Elevation of Privilege - Setup Guide

## Prerequisites
- Java 21 (Maven Wrapper included — no global Maven needed)
- **Colima + the Docker CLI plugins** (`brew install colima docker docker-compose docker-buildx`) — required to run the application stack, the monitoring stack or the k6 load test. Four formulae: the `docker` formula is a client only and ships **neither** Compose **nor** Buildx. **Do not install Docker Desktop** — it needs administrator rights this machine does not grant, and its licence requires a paid subscription for commercial use above an organisation-size threshold. See [ADR-016](docs/adr/ADR-016-local-container-runtime.md)
- **k6** (`brew install k6`) — required by `test/k6/run.sh`
- **direnv** — required to load `.env` into the Spring app (see below)
- **Node.js 22.12+** (`brew install node`) — required twice over. The Graphify knowledge graph needs Node 20 or later, and below that the `graphify` MCP server silently fails to start and no `graphify_*` tools appear. The `ui/` front end declares `engines.node >= 22.12` and CI builds it on Node 22, so **22.12** is the floor for this repository. The patch-level floor is not decoration: it is Vite 7's own requirement (`^20.19.0 || >=22.12.0`), so Node 22.0–22.11 satisfies a bare "22" while violating Vite's own range — and npm will not stop you, because `engine-strict` is unset and there is no `.npmrc` at the repository root or in `ui/`, so it prints an `EBADENGINE` warning and installs anyway, leaving the mismatch to surface later as a confusing Vite failure. `actions/setup-node` with `node-version: 22` resolves to the latest 22.x and is comfortably above it
- **uv** (`brew install uv`) — required by the Atlassian MCP server, launched as `uvx mcp-atlassian`. Without `uvx` on `PATH` it silently fails and no `atlassian_jira_*` tools appear

### Per-clone setup that cannot be committed
Five steps live outside version control. **All five fail silently or misleadingly**,
so verify each one:

```bash
# Install the pinned Graphify CLI (see Blueprint §5.2 for why --ignore-scripts)
cd tools/graphify && npm install --ignore-scripts && cd -
graphify --version            # must print 0.17.1

# Install the front-end dependencies
cd ui && npm install && cd -
cd ui && npm run verify && cd -   # typecheck, lint, test, build — all four must pass

# Activate the committed git hooks — enforces the [EOP-NNN] commit prefix
git config core.hooksPath .githooks
git config --get core.hooksPath   # must print .githooks

# Start the container runtime (needed after every reboot — see ADR-016)
colima start --vm-type=vz --cpu 4 --memory 6 --disk 60
docker compose version        # must print a version, not "unknown command"
docker info | grep -i 'Server Version'   # must succeed, not a socket error

# Point the Docker CLI at the Homebrew plugin directory, if it does not already
grep -q cliPluginsExtraDirs ~/.docker/config.json || \
  echo 'add "cliPluginsExtraDirs": ["/opt/homebrew/lib/docker/cli-plugins"] to ~/.docker/config.json'
```

> **If `docker compose` reports `unknown command`, do not conclude Compose is
> missing.** A previous Docker Desktop install can leave dangling plugin symlinks
> in `~/.docker/cli-plugins/` pointing into an unmounted disk image; the CLI finds
> them, cannot execute them, and reports the command as unknown. Delete any
> symlink there whose target does not exist. Also remove `credsStore` and
> `currentContext` from `~/.docker/config.json` if they name `desktop`.

MCP servers are registered only at session start, so restart OpenCode afterwards.

## Environment Configuration

### 1. Create `.env`
```bash
cp .env.example .env
chmod 600 .env
```
If `.env` already exists, append only missing variables:
```bash
grep -v '^#' .env.example >> .env   # then remove duplicates manually
```

> **Then replace every `CHANGE_ME` in your `.env` before starting anything.** The
> template ships `CHANGE_ME` rather than a plausible-looking password precisely so
> that a copied file cannot quietly seed Grafana or PostgreSQL with a
> credential published in git. Enumerate what you still owe with:
> ```bash
> grep -n CHANGE_ME .env
> ```
> Empty is the goal. Note `DB_PASSWORD=` is deliberately empty and is *not* a
> `CHANGE_ME` — it pairs with `DB_USERNAME=sa` for local in-memory H2, which has no
> password. Leave it alone.

> **An `.env` predating the containerised stack will be missing `POSTGRES_DB`,
> `POSTGRES_USER` and `POSTGRES_PASSWORD`.** `compose.app.yml` declares those with
> required-variable syntax, so `docker compose -f compose.app.yml up -d` fails
> loudly rather than starting a database with no password. That is the good case —
> add them and retry.
>
> **direnv exports `.env` at directory entry, so editing it does not change an
> already-running shell.** After changing a variable, run `direnv reload`, open a
> new shell, or pass the value inline. Two separate diagnoses were wasted on this,
> and a third was spent on it during EOP-154 — `INFLUXDB_URL` is the variable that
> keeps catching people, because a stale value makes `test/k6/run.sh` write to the
> wrong address while still exiting `0`. Check with `echo "$INFLUXDB_URL"`.

> **`chmod 600 .env` is not optional.** `cp` gives the new file your umask
> default, which on macOS is `0644` — group- and world-readable. `.env` holds
> live credentials (`JIRA_API_TOKEN`, `GITHUB_TOKEN`, `SUPERMEMORY_API_KEY`,
> `AWS_BEARER_TOKEN_BEDROCK`, and the Grafana admin password), so any
> local process under any account on the machine could read them. It sat at
> `0644` from creation until the 2026-08-02 audit caught it. For comparison,
> OpenCode's own credential store `~/.local/share/opencode/auth.json` is `0600`
> and `.opencode/goals/` is `0700` — `.env` was the outlier. Verify with
> `ls -l .env` and expect `-rw-------`.

### 2. Fill in `.env` values
You **choose** these values yourself — they seed accounts on first boot:

| Variable             | Description                              | Example               |
|----------------------|------------------------------------------|-----------------------|
| `DB_URL`             | JDBC connection URL                      | `jdbc:h2:mem:eop`     |
| `DB_USERNAME`        | Database username                        | `sa`                  |
| `DB_PASSWORD`        | Database password (blank OK for local H2)|                       |
| `GF_SECURITY_ADMIN_USER` | Grafana login username                  | `admin`               |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana login password (you choose; wrap in single quotes if it contains `$`) | |
| `INFLUXDB_URL`       | Where `test/k6/run.sh` streams metrics — a **host** address, not a container one | `http://localhost:8086/k6` |

### 3. Load `.env` via direnv (required for the backend)
Spring Boot does **not** read `.env` natively — the app fails fast if
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD` are unset. The repo's `.envrc` (`dotenv`)
loads them via direnv:
```bash
brew install direnv
echo 'eval "$(direnv hook zsh)"' >> ~/.zshrc   # or your shell's hook
direnv allow
```
Without direnv, export the variables manually before running the app.

### 4. Docker socket for the PostgreSQL migration tests

`./mvnw verify` runs three Testcontainers integration tests that start a real
PostgreSQL 17 container (`*IT` classes under `src/test/java/org/maglez/eop/migration/`,
see [ADR-056](docs/adr/ADR-056-postgres-migration-tests-via-testcontainers.md)).
`./mvnw test` does **not** — the fast suite stays on in-memory H2 and needs no
container runtime at all.

So `colima start` must have been run before `./mvnw verify`. Two exports in `.envrc`
handle the rest, and both are guarded so they fire only when a default Colima socket
exists and you have not set `DOCKER_HOST` yourself:

| Variable | Why |
|---|---|
| `DOCKER_HOST` | Testcontainers does not read the Docker CLI *context*, and Colima creates no `/var/run/docker.sock`. Without this every migration IT fails with `Could not find a valid Docker environment` |
| `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` | Testcontainers bind-mounts the resolved socket path into its Ryuk cleanup sidecar. The macOS-side path does not exist inside the Colima VM and virtiofs cannot create it, so Ryuk fails to start with `operation not supported`. This names the in-VM path to mount instead |

Ryuk is deliberately left **enabled** — `TESTCONTAINERS_RYUK_DISABLED=true` clears that
second error only by giving up automatic cleanup of stray containers.

Because these live in `.envrc`, a shell without the direnv hook loaded will not have
them. If `./mvnw verify` fails on Docker discovery, either open a new shell or prefix
the command with `direnv exec .`.

If you use Docker Desktop elsewhere, a non-default Colima profile, or a remote daemon,
set `DOCKER_HOST` yourself and the guard leaves it alone.

## Running the Application

### Backend (from repo root)
```bash
./mvnw spring-boot:run
```

### Monitoring stack — Grafana + InfluxDB (from repo root)
`docker-compose.yml` lives at the **repo root**, not in `tools/monitoring`:
```bash
docker-compose up -d
```

**After changing any `GF_SECURITY_*` value:** Grafana's admin credentials are
seeded into `grafana_data` on first start only, so a changed value needs the
volume recreated. InfluxDB has no credentials to seed — it runs with HTTP
authentication disabled on a loopback-bound port (ADR-016), so nothing about it
requires this step.
```bash
docker-compose down -v   # wipes grafana_data AND influxdb_data
docker-compose up -d
```
> `-v` destroys your k6 metric history along with the Grafana volume, and the
> dashboard exists to show that history. Prefer `docker-compose down` without
> `-v` unless you specifically need to re-seed the Grafana admin account.

### Logging into Grafana
1. Open `http://localhost:3000/login`
2. Username: `GF_SECURITY_ADMIN_USER` (default `admin`)
3. Password: your `GF_SECURITY_ADMIN_PASSWORD` value
4. Changing the password in the UI afterwards is fine — it lives in
   Grafana's internal DB from then on (`.env` is only the first-boot seed).

## Security Notes — Accepted Risks (reviewed 2026-08-20)

The following items were reviewed and **accepted**; do not re-flag them in
future audits without new evidence.

### Production hardening applied
- **springdoc is disabled by default — no longer an accepted risk (resolved by
  EOP-38, ADR-049, 2026-08-22).** This entry used to read "`application-prod.yml`
  disables springdoc (`/v3/api-docs`, `/swagger-ui.html`). They remain enabled in
  the default profile for local development", and that was accepted on the grounds
  that the one deployment path sets `SPRING_PROFILES_ACTIVE=prod`. It is kept here,
  struck through in substance rather than deleted, because the register is also a
  record of what was once accepted and why it stopped being acceptable. The
  hardening now lives in the base `application.yml`, so **the default profile
  serves neither the schema nor the UI** — measured 404 on `/v3/api-docs`,
  `/swagger-ui/index.html` and `/swagger-ui.html`. Local development opts *in* with
  `SPRINGDOC_APIDOCS_ENABLED=true` and `SPRINGDOC_SWAGGERUI_ENABLED=true`, which
  `.env.example` carries and `.envrc`'s `dotenv` exports; `compose.app.yml` has no
  `env_file:`, so a container never sees them, and `application-prod.yml` pins both
  to `false` regardless as a second independent guard. Pinned by
  `SpringdocDisabledByDefaultIntegrationTest` and, so that "disabled" can never be
  satisfied by springdoc merely being broken, `SpringdocOptInIntegrationTest`.
- **Notificator plugin removed** — it shelled out to OS commands
  (`osascript`/`afplay`/`notify-send`) for desktop notifications; attack
  surface not justified by utility. See Blueprint §12.6.

### GitHub Actions supply chain (`.github/workflows/` — CI only, never in the shipped image)

*Recorded 2026-09-05 (EOP-174) as an accepted residual, and **closed 2026-09-07 by EOP-236**
([ADR-073](docs/adr/ADR-073-supply-chain-coverage-for-actions-and-browsers.md)). The
paragraphs below are kept because the reasoning that made the acceptance defensible is also
the reasoning that says what to do when the next action arrives — but the acceptance itself
is spent, and citing this section as a live control is now wrong.*

- **Every `uses:` reference is pinned to a 40-hex commit SHA, and an audit keeps it that way.**
  `.github/workflows/ci.yml` carries **29 `uses:` references across 9 distinct actions** —
  `actions/checkout`, `actions/setup-java`, `actions/setup-node`, `actions/cache`,
  `actions/upload-artifact`, `actions/download-artifact`, `docker/setup-buildx-action`,
  `docker/build-push-action` and `docker/login-action` — each written
  `owner/repo@<sha> # vX.Y.Z`, with the exact semver in the trailing comment because the SHA
  alone tells a reader nothing. Note the count of *references* is not the count of *actions*:
  `actions/checkout` alone appears nine times. The figure was **22** when this section was
  written; EOP-220's `e2e` job took it to 29, which is one reason a hand-maintained prose
  count was the wrong instrument.
- **`tools/supply-chain/audit-actions.sh` is the control now, and it is stronger than a
  baseline comparison.** It holds every reference against
  `tools/supply-chain/expected-actions.json` bidirectionally — an undeclared action fails, a
  declared action nobody references fails, one action pinned to two different commits fails,
  and a `reference_count` or `occurrences` disagreement fails. Crucially **one of its checks
  does not consult the baseline at all**: any `uses:` value that is not `owner/repo@<40 hex>`
  fails outright, so an action added by tag fails on its first commit rather than being
  quietly admitted and recorded. That is what makes this a policy rather than a tripwire.
- **Do not confuse this with the container pins in the same file.** The k6 and Trivy steps,
  the SonarQube scanner and the Playwright browser image are digest-pinned **container
  images** audited by `audit-containers.sh`, not `uses:` actions. Before EOP-236 no `uses:`
  line was SHA-pinned at all; an earlier reading that cited the containers as a SHA-pinned
  contrast was wrong, and the two mechanisms remain separate baselines on purpose.
- **What the pins cost, and the residual that replaces the old one.** A SHA pin freezes
  security fixes as well as attacks. There is no Dependabot configuration in this repository
  and the audit is deliberately network-free, so nothing tells us a newer release exists —
  the pins go stale silently and are moved by hand with
  `gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq .object.sha`, updating
  `expected-actions.json` in the same commit. **This is the accepted residual as of
  2026-09-07**, and its retiring condition is the adoption of an automated bump mechanism.
  It is a deliberate trade: a stale pin is a known-good commit, whereas a moving tag is an
  unknown one.
- **Why the original acceptance was defensible, and why it still governs new arrivals.**
  Every publisher is first-party — the `actions` and `docker` organisations, both
  GitHub-operated or GitHub-partnered — so a malicious release would have required
  compromising one of those organisations' release processes and would have been a broad
  ecosystem event rather than an attack on this repository. The workflow's default token is
  `permissions: contents: read`, with the single `contents: write` scoped to the `perf-trend`
  job, so the blast radius of a compromised action was bounded well short of the
  repository's contents. Both facts still hold; they are now belt as well as braces.
- **What would still change this assessment.** A third-party action entering the workflow —
  anything outside `actions/*` and `docker/*` — is a different risk class, because the
  publisher-trust argument above does not extend to it. SHA-pinning is now automatic for it
  (the audit permits nothing else), but the *decision to depend on it at all* needs an
  argument, and a first-party alternative should be preferred. The tj-actions/changed-files
  tag-retargeting incident of March 2025, which leaked runner memory into public build logs,
  is the concrete precedent.
- **The `supply-chain` job is still deliberately not a required status check.** The required
  gates on `main` are `build`, the two Sonar ratchets and `dependency-cve` — all of them
  about code that ships. A finding in a CI-only dependency surface is a thing to know about,
  not a reason to block an unrelated merge; the three audits carry `if: always()` so a reader
  gets all three verdicts from one run.

### OpenCode dev tooling (`.opencode/` — not shipped, never in production)
- **`tiktoken`** (transitive via `@anthropic-ai/tokenizer`) — offline WASM
  tokenizer, makes no network calls. No drop-in replacement exists without
  forking upstream plugins.
- **`@ai-sdk/openai-compatible`** — **removed.** It existed only to back a
  custom provider block in `.opencode/opencode.json`. That block was deleted
  when the project moved to OpenCode Zen, which is a built-in provider
  (id `opencode`) requiring no SDK dependency and no endpoint env var.
- **`npm audit` residual: 0 critical / 1 high / 1 moderate / 7 low, carrying
  14 distinct advisories** — *measured 2026-08-20 against the seven exact pins
  now current. It supersedes a same-day snapshot of 0/0/0/4 taken against the
  six pins that preceded `@tarquinen/opencode-smart-title`, which in turn
  superseded a 2026-07-27 snapshot of 5 high / 4 low.* Read the counts for what
  they are before drawing a conclusion: npm's `metadata.vulnerabilities` counts
  **packages at their worst severity**, not advisories, which is why one "high"
  package carries three high advisories and why 9 packages carry 14 findings.
  The residual splits into two groups, and only the first is gated.
  1. **Three high advisories against `undici@5.29.0`, allowlisted by ID.**
     [GHSA-vrm6-8vpv-qv8q](https://github.com/advisories/GHSA-vrm6-8vpv-qv8q),
     [GHSA-v9p9-hfj2-hcw8](https://github.com/advisories/GHSA-v9p9-hfj2-hcw8)
     and [GHSA-vxpw-j846-p89q](https://github.com/advisories/GHSA-vxpw-j846-p89q)
     arrived with smart-title, through `ai@^5.0.98` →
     `@ai-sdk/gateway@2.0.137` → `@ai-sdk/provider-utils@3.0.32` →
     `undici ^5.29.0`. **There is no in-range fix**: `3.0.32` is the newest
     `3.x` and wants that undici range, the gateway pin on it is *exact* so npm
     cannot lift it, and the `ai` range belongs to the plugin's own
     `package.json` rather than to us. All three defects are in undici's
     **WebSocket client**, which is unreachable here on two independent
     grounds — the only consumer destructures `{ Agent, fetch }` and never
     `WebSocket`, and the `createRequire(…)("undici")` that would load it sits
     behind an `isNodeRuntime()` test of `process.versions.bun == null`, false
     because OpenCode runs plugins under Bun. Recorded with that trace in
     `tools/supply-chain/accepted-advisories.json` and gated against it; see
     Blueprint §12.9.
  2. **The moderate and the sevens lows are ungated and unchanged in kind.**
     They are the pre-existing `@babel/core` "Arbitrary File Read via
     `sourceMappingURL` Comment" chain — reached through
     `@tarquinen/opencode-dcp` → `@opentui/solid` → `@opencode-ai/plugin`,
     whose only `fixAvailable` is a **downgrade** to
     `@tarquinen/opencode-dcp@3.1.12` flagged `isSemVerMajor`, i.e. giving up
     DCP and its OpenTUI runtime — plus undici's own HTTP-layer findings
     (request smuggling, CRLF injection, `Set-Cookie` and keep-alive) and one
     `@ai-sdk/provider-utils` resource-consumption advisory. Accepted on the
     same three grounds as before: no forward fix; exploitation needs
     untrusted input that these build-time paths never see; and none of it is
     a runtime dependency of the shipped Java application, which has no npm
     dependencies at all.

> **This check now runs itself: `tools/supply-chain/audit-plugins.sh`, wired
> into CI as the non-required `supply-chain` job.** Reproduce the figures above
> by running that script; it needs `node`, `npm` and `python3` and works inside
> `.tmp/supply-chain`. It fails on five conditions — a maintainer account
> change, a provenance change in *either* direction, a plugin spec that has
> lost its exact version, a high/critical advisory that is **not** on the
> allowlist, or an allowlisted advisory that is **no longer reported**. Low and
> moderate findings deliberately do not fail it, because the residual accepted
> above includes them and a job that goes red on a known accepted finding gets
> ignored. It compares against two baselines.
> `tools/supply-chain/expected-plugins.json` holds the roster, and
> `tools/supply-chain/accepted-advisories.json` holds the high/critical
> allowlist. Both are a **tripwire, not a policy**: they record what was true
> when a human last looked, so that a *change* becomes loud. Update them
> deliberately, in the same commit that moves a pin, and never to make a red
> job green.
>
> **An unreachable advisory endpoint is `exit 2` (could not run), never a
> pass.** When the npm registry returns a 503 or DNS fails, `npm audit --json`
> writes an error document — a JSON object with a top-level `"error"` key and
> no `"metadata.vulnerabilities"`. The script detects this shape and exits with
> code 2 rather than 0, so a network failure cannot masquerade as a clean tree.
> The staleness comparison is also skipped in this case: running it against an
> error document would falsely flag every allowlist entry as "no longer
> reported" and recommend deleting live accepted-risk documentation. Re-run the
> script once the endpoint is reachable; do not treat a `could not run` result
> as evidence of anything about the advisory state. The three exit codes are:
> `0` = clean, `1` = gate rejected (drift or unaccepted advisory), `2` = could
> not run (network, missing tool, or missing file).
>
> **The allowlist is a liability rather than a dismissal, which is why it fails
> in both directions.** An entry whose advisory has stopped being reported is a
> hard failure telling you to delete it, so exemptions cannot silently
> accumulate past their usefulness. What an entry records is only that a human
> traced a call path on a stated date — it cannot detect a later version bump
> that starts actually *calling* the vulnerable API, so re-verify reachability
> whenever one of the pins in the chain moves. Its scope is high and critical
> only, because those are the sole gated severities and a low or moderate entry
> could never fire.

>
> **Automating it was the actual finding of this exercise.** The trigger that
> preceded the script was a sentence saying "re-check whenever a pin moves",
> and it fired four times and was honoured once — the once being when a human
> asked directly. Twice it was missed while no JavaScript runtime was on `PATH`
> (`opencode-supermemory` 2.0.10 → 2.0.11 and `opencode-goal-plugin`
> 0.6.5 → 0.6.7), and once with no such excuse, when those two went
> 2.0.11 → 2.0.12 and 0.6.7 → 0.8.1 alongside `@tarquinen/opencode-dcp`
> 3.1.14 → 3.1.15 — an upgrade that *edited this very paragraph to record the
> trigger as unhonoured* without honouring it. Read that as evidence about
> prose, not about the risk. Prose does not run.
>
> The `supply-chain` job carries a **weekly cron** as well as running on push
> and pull request, and that is the point rather than a nicety: every other job
> in the workflow is a function of a commit, but an advisory is published by
> someone else against code that has not changed, so a check that only fires on
> push cannot observe one arriving. It is deliberately **not** a required
> status check — `build` stays about the code, and a fresh transitive advisory
> is something to know about rather than a reason to block an unrelated merge.
>
> **What the measurement covers, and what it cannot.** `npm audit` answers "is
> there a *published advisory* against these versions". It does not answer "was
> one of these releases backdoored", which is the failure mode that actually
> matters for plugins running in-process and unsandboxed with sight of every
> message and file. Three further checks address that as far as anything can,
> and all three are in the script: **maintainer continuity** — the loudest
> single compromise signal is an account handoff, and every pinned package's
> maintainer set is unchanged; **registry signatures** — 210 of 210 verified;
> and **SLSA provenance attestations** — 76 packages carry them, but only
> **three of the seven plugins** do (`@tarquinen/opencode-dcp`,
> `opencode-supermemory`, `@nick-vi/opencode-type-inject`). The four without
> are `opencode-vibeguard`, `opencode-scheduler`, `opencode-goal-plugin` and
> `@tarquinen/opencode-smart-title`.
> Correcting an earlier version of this note: goal-plugin is **not** uniquely
> unattested, and the three it shares that with are uncomfortable company —
> `opencode-vibeguard` is the secret-redaction plugin, so the component asked
> to keep credentials out of prompts is itself among the least verifiable,
> `opencode-scheduler` writes launchd/systemd units and therefore has the most
> persistent reach outside the editor process, and
> `@tarquinen/opencode-smart-title` reads every message of every session in
> order to title it. State this as *less to verify
> against*, never as evidence of a problem: nothing suspicious has been found
> in any of them. And provenance only ever proves a tarball came from the named
> repository's CI — never that the repository's code is benign.
>
> **Maintainer continuity is now a weaker signal than the package count
> suggests, because one npm account holds two of the seven pins.**
> `@tarquinen/opencode-dcp` and `@tarquinen/opencode-smart-title` are published
> by the same account (`tarquinen <dannysmo@gmail.com>`), so a single handoff or
> compromise reaches two plugins at once — and because the script checks
> maintainers **per package**, it would report that as two independent findings
> rather than as one event. Both baseline notes say so; read them together.

>
> **Plugin installation does not run npm lifecycle scripts. Verified, not
> assumed.** This closes a gap an earlier version of this note left open.
> OpenCode does not shell out to a package manager at all: `Npm.add()` in
> `packages/core/src/npm.ts` (read at tag `v1.18.19`, matching the installed
> binary) imports `@npmcli/arborist` in-process and constructs it with
> `ignoreScripts: true` placed *after* the spread of user npm config, so the
> flag cannot be overridden by an `.npmrc`; arborist's `rebuild.js` gates
> `preinstall`, `prepare`, `install` and `postinstall` on exactly that flag,
> and it is the only path reaching `@npmcli/run-script`. Three caveats keep
> this honest. `binLinks: true` is still passed, so `node_modules/.bin`
> symlinks *are* created — "no scripts" is not "no executables placed". The
> guarantee rests on `@npmcli/arborist` being pinned to exactly `9.4.0`, which
> reads the flag from its constructor options rather than per-call ones; an
> arborist that merged per-call options would silently re-admit the `.npmrc`
> value that OpenCode already forwards. And the scope is the *install* step
> only — the plugin's own module code executes at import time by design, which
> is a far larger surface than any postinstall hook. That is why the checks
> above are about *who published this* rather than about install-time
> execution.
>
> **One plugin can fetch an unpinned package at runtime, and none of the above
> would see it.** `@tarquinen/opencode-smart-title` depends on
> `@tarquinen/opencode-auth-provider@0.1.7`, which reaches Bedrock by
> `import("@ai-sdk/amazon-bedrock")` — a package it does not declare — and on
> failure falls through to `bun add --force --exact --cwd <cache> <pkg>@latest`.
> So a package resolved at *whatever `latest` is that day* may be installed into
> OpenCode's cache on a machine whose entire declared plugin roster is
> exact-pinned. `audit-plugins.sh` reads the `plugin` array of
> `.opencode/opencode.json` and cannot observe this, so it is the one hole in
> the exact-pinning guarantee. It is reached only on the first Bedrock title
> call and only if the bundled import fails; which way that import resolves is
> unverified. Accepted deliberately rather than overlooked — Blueprint §12.9
> holds the file and line citations.
>
> Drop each residual entry as its upstream fix lands, and delete an allowlisted
> advisory ID the moment the script reports it as no longer present.
