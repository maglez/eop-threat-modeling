# CI/CD Pipeline

One workflow, `.github/workflows/ci.yml`, holds every automated check this project runs. It is heavily commented at source; this page is the map, not a copy. **Read the workflow for the detail** — a second copy of the YAML in a Markdown file is a copy that goes stale, and this page carried exactly that mistake from the Walking Skeleton until 2026-09-07.

## Triggers

| Trigger | When | Notes |
|---|---|---|
| `push` | Every commit on `main` | The only event that publishes anything |
| `pull_request` | Every PR targeting `main` | Publishes nothing; `e2e` and `perf-trend` do not run |
| `schedule` | Nightly, `0 6 * * *` (06:00 UTC) | Was weekly until EOP-220 needed a nightly E2E cadence ([ADR-072](../adr/ADR-072-e2e-ci-integration.md)) |
| `workflow_dispatch` | On demand, any branch | No inputs, no branch restriction |

The whole graph runs on the schedule, not just the jobs that need it. That is deliberate: a nightly green on `build`, `ui` and `image` is a canary for breakage arriving from outside the repository — a yanked dependency, a base-image change, a new advisory — and nothing is published, because every publishing step is gated on `github.event_name == 'push'`.

Top-level `permissions` is `contents: read`. Two jobs widen it, and only as far as they must: `image` adds `packages: write` for GHCR, and `perf-trend` takes `contents: write` because it commits to an orphan branch. `permissions` is job-scoped with no per-step granularity, which is why `perf-trend` is a separate job rather than a step inside `image`.

The repository holds **zero secrets**. Everything above runs on the built-in `GITHUB_TOKEN`.

## Jobs

| Job | Depends on | Runs when | What it does |
|---|---|---|---|
| `build` | — | Always | `./mvnw verify --batch-mode`, then `tools/artifact/assert-no-h2-in-jar.sh`; uploads `app-jar` |
| `ui` | — | Always | In `ui/`: `npm ci`, then `typecheck`, `lint`, `coverage`, `build` as four separate steps so a failure names itself |
| `image` | `build`, `ui` | Always | Builds `eop-threat-modeling:ci` and `eop-ui:ci`, smoke-tests the composed stack, runs the k6 canary, and on a push publishes both images to GHCR |
| `e2e` | `image` | Not on `pull_request` | Loads the images `image` built, starts `compose.e2e.yml`, runs the Playwright suite, reports failing scenarios in the run summary |
| `perf-trend` | `image` | Push to `main` only | Appends one k6 trend point to the orphan `perf-history` branch and republishes the trend page |
| `sonar-ratchet` | — | Always | `tools/sonar/ratchet.sh` — three Java issue counts against `tools/sonar/sonar-baseline.json` |
| `sonar-ratchet-ui` | — | Always | `tools/sonar/ratchet-ui.sh` — the same three counts over `ui/src` against `sonar-ui-baseline.json` |
| `dependency-cve` | — | Always, unconditionally | Trivy over both dependency trees, gating on HIGH and CRITICAL |
| `supply-chain` | — | Always | `audit-containers.sh`, `audit-actions.sh` then `audit-plugins.sh` — three populations, each `if: always()` so no one finding hides another |

### Required status checks on `main`

Four, and only four: **`build`**, **`sonar-ratchet`**, **`sonar-ratchet-ui`** and **`dependency-cve`**.

`image` is deliberately not required yet — it landed as an ordinary job so its reliability could be observed, and promoting it is a separate branch-protection change. `dependency-cve` carries no `if:` condition at all, which is a precondition of being required rather than an incidental detail: a required check that can be skipped leaves a pull request permanently unmergeable.

`e2e` is not required and cannot become required without first changing its trigger — see below.

## The `image` job in more detail

1. **Build both images** with `docker/build-push-action@v6`, `load: true`, GHA layer cache scoped `app` and `ui`. The front-end build passes all three `VITE_*` flags as `true` build args, because `ui/Dockerfile` defaults them to `false` and they are resolved at build time (ADR-037).
2. **Save both images** as a `ci-images` artifact (`docker save | gzip`, three-day retention), for `e2e` to load. Not on a pull request.
3. **Smoke test** — `docker compose -f compose.app.yml up -d`, then poll `curl -fsSk https://localhost/health` for a body of exactly `OK`, check `GET /` is 200, and check the card catalogue reports `"totalElements":68`.
4. **k6 canary** — two scenarios against the relaxed CI thresholds in `test/k6/config/options-ci.js`, from a digest-pinned `grafana/k6` container joined to the Caddy container's network namespace. Metrics render into the run summary and upload as `k6-results`. This is a smoke canary, not a load test; its numbers are not comparable with the local baselines in `docs/performance/TRENDS.md` ([ADR-055](../adr/ADR-055-k6-performance-check-in-ci.md)).
5. **Tear down** by project name (`docker compose -p eop-app down --volumes`, no `-f`) — `compose.app.yml` uses the fail-hard `${VAR:?}` form, so passing `-f` would make Compose refuse to parse even for a `down`, and doing it this way keeps the database credentials out of the step.
6. **Publish to GHCR** — `if: github.event_name == 'push'` only, both images tagged `:${sha}` and `:latest`.

## The `e2e` job in more detail

It drives the shipped containers through a real browser — three of them, serially — against `compose.e2e.yml`, a standalone stack on host port 8443 with its own project name, subnet and volumes so it cannot disturb a developer's running `compose.app.yml`. See [ADR-068](../adr/ADR-068-playwright-e2e-testing.md) for the tier and [ADR-072](../adr/ADR-072-e2e-ci-integration.md) for this wiring.

**It never runs on a pull request.** That is how the merge stays unblocked — not by leaving a check off a protection list, where a future administrator could add it, but by there being no check on the pull request to add.

**A failed `image` job skips it rather than failing it.** `needs: [ image ]` supplies that, and the job's `if:` tests only the event name — no `always()`, no `failure()` — so GitHub's default skip survives. Honest reporting: the suite did not fail, it never got an artefact to run against.

**Both of those lines are held by the build.** `E2eJobInvariantTest` fails `./mvnw verify` if the `e2e` job's `if:` stops excluding `pull_request` or grows an `always()`/`failure()` term, if its `needs:` stops containing `image`, or if the `ci-images` artefact name stops agreeing between the `image` job's upload step and the `e2e` job's download step. Neither line is self-documenting at the point of edit, and neither is enforced by GitHub, so the gate is what makes removing one loud instead of silent ([ADR-006](../adr/ADR-006-build-quality-gates.md), amended 2026-09-07). It reads the workflow as text and never executes the job, so a green build is not evidence the suite ran.

**The browsers arrive in a digest-pinned container, not from a download.** The job does not run `npx playwright install --with-deps` — that `apt-get`ed system libraries as root and fetched three browsers from Microsoft's CDN with nothing verifying what arrived. Instead the suite itself runs inside `mcr.microsoft.com/playwright:v1.63.0-noble` pinned by digest, as a non-root uid, with `--network host` so the host-side health wait still reaches port 8443 and `--ipc host` so Chromium does not exhaust `/dev/shm`. `docker compose` stays on the runner, which is why this is a `docker run` rather than a `container:` job ([ADR-073](../adr/ADR-073-supply-chain-coverage-for-actions-and-browsers.md)).

**That pin is coupled to an npm version, and the coupling is held by the build.** `PlaywrightImagePinTest` fails `./mvnw verify` unless the image tag in the workflow, the `tag` field in `tools/supply-chain/expected-containers.json`, the `@playwright/test` specifier in `e2e/package.json` and the version resolved for it in `e2e/package-lock.json` all name the same release. Moving one without the others is not a version bump but a broken suite, and it fails in a misleading way — Playwright reports a missing browser executable, which points at the image rather than at the two files that disagree. It also fails on an unpinned mention and on a range operator, which is why the caret was dropped from that dependency. It cannot prove the digest is the one that tag resolves to; that needs the registry and belongs to `audit-containers.sh`.

**CI owns the stack.** The job runs `up -d --wait` itself and sets `E2E_REUSE_STACK=true`, so `e2e/global-setup.ts` skips its own `up` while still performing the host-side health wait, and `e2e/global-teardown.ts` skips `down -v`. Container logs therefore survive the tests and are collected as the `e2e-stack-logs` artifact; teardown happens in a final `if: always()` step.

**Failures reach a human through GitHub's own notification** for a failed workflow run — no email action, no `actions/github-script`, no secret. Alongside it the job writes a `$GITHUB_STEP_SUMMARY` table naming every scenario that did not pass first time (`file:line`, title, browser project, status) and emits `::error::` / `::warning::` annotations so the finding attaches to the commit. It reports identity, never assertion text: a failing matcher prints its received value, and that value can be a live join code ([ADR-071](../adr/ADR-071-e2e-artefact-publication-boundary.md)).

**Artefacts.** `e2e-results` (`results.json` — the only file ADR-071 clears for publication, and EOP-221's input), `e2e-playwright-report` (the HTML report and traces — Actions artifact only, never a published page), `e2e-stack-logs`. `E2eArtefactPublicationBoundaryTest` fails `./mvnw verify` if a workflow step ever names `playwright-report` alongside a Pages publisher.

**Two things a green `e2e` does not prove.** Rate limiting works — `compose.e2e.yml` raises both ceilings to `Integer.MAX_VALUE`. And that the run was clean — `retries: 1` under CI turns a first-attempt failure that passes on retry into a *flaky* result, which does not fail the job. The summary reports flaky counts and warns.

## What gates what, from a contributor's point of view

- **Before pushing:** `./mvnw verify` at the repository root, and `npm run verify` in `ui/` if you touched the front end. Both are what CI runs.
- **If you changed `pom.xml` or any `.java` under `src/`:** re-run `tools/sonar/scan.sh` with the local Sonar container up and commit both JSONs, or `sonar-ratchet` fails on a stale `sourceHash` before it compares a single count. `tools/sonar/scan-ui.sh` is the equivalent for `ui/src`.
- **If you changed a pinned plugin, container or GitHub Action:** run the matching `tools/supply-chain/audit-*.sh` — `audit-plugins.sh`, `audit-containers.sh`, `audit-actions.sh` — and update the baseline in the same commit, never to turn a red job green. Adding an action is the case to watch: `audit-actions.sh` rejects any `uses:` that is not `owner/repo@<40-hex-sha>` without consulting its baseline at all, so a bare tag fails on its first commit rather than waiting to be declared.
- **If you changed the E2E suite or `compose.e2e.yml`:** push the branch and use `workflow_dispatch`. It is not restricted to `main` for exactly this reason.

## Deployment

There is no deployment stage. Nothing in this workflow assumes a cloud role, runs infrastructure-as-code, or touches an environment outside the runner; the only artefacts that leave a run are the GHCR images and the two orphan branches. `SETUP.md` and the Blueprint describe the intended continuous-deployment target; this file describes what exists.

## Related

- [ADR-072: The end-to-end suite runs after the merge, not before it](../adr/ADR-072-e2e-ci-integration.md)
- [ADR-068: End-to-end testing with Playwright](../adr/ADR-068-playwright-e2e-testing.md)
- [ADR-071: The E2E artefact publication boundary](../adr/ADR-071-e2e-artefact-publication-boundary.md)
- [ADR-055: k6 performance check in CI](../adr/ADR-055-k6-performance-check-in-ci.md)
- [ADR-050: CVE scanning as a separate Trivy job](../adr/ADR-050-dependency-cve-scanning.md)
- [ADR-060: SonarQube issue ratchet](../adr/ADR-060-sonarqube-issue-ratchet.md)
- [Local Development](local-development.md)
- `e2e/README.md` — running the suite on your own machine
