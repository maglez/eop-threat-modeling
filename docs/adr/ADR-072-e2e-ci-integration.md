# ADR-072: The end-to-end suite runs after the merge, not before it

**Status:** Accepted (2026-09-07)
**Date:** 2026-09-07
**Deciders:** @tech-lead, @devops-engineer, @architecture-guardian

## Context

[ADR-068](ADR-068-playwright-e2e-testing.md) added a Playwright tier that drives the shipped containers — the production `Dockerfile` app image behind the production `ui/Dockerfile` with `ui/Caddyfile` baked in — through `compose.e2e.yml`. [ADR-071](ADR-071-e2e-artefact-publication-boundary.md) then settled which of its artefacts may be published and to whom. Neither said how the suite runs in continuous integration; `e2e/README.md` recorded that gap as owed to EOP-220, and until this decision the tier ran only when a developer remembered to run it.

Four facts constrain the answer.

**The suite is slow, and slow for a reason that is not going away.** `e2e/playwright.config.ts` sets `fullyParallel: false` and `workers: 1`, so the three browser projects run serially and wall clock is roughly their sum. The serialisation is not laziness: there is one app instance and one database, the leaderboard reads the whole session history (ADR-030), and both rate limiters key on the client address — which is identical for every browser on the runner. A local three-browser run measures ~86 s of test time on top of an image build, a stack start and a browser install. That is an order of magnitude more than any existing job contributes to a pull request.

**A required check that cannot run is worse than no check.** The `dependency-cve` job carries no `if:` precisely because a required check which is skipped leaves a pull request permanently unmergeable. The same mechanism read in the other direction says that anything conditional must stay out of branch protection.

**An unnoticed post-merge failure is worse than no test at all.** A tier that runs but that nobody looks at converts a real regression into a false sense of coverage. Whatever schedule we choose has to end at a human.

**The images under test must be the images the pipeline built.** Rebuilding from source inside a second job would test a second build, not the artefact `image` smoke-tested and pushed. `compose.e2e.yml` deliberately has no `build:` section for that reason and takes `APP_IMAGE` and `UI_IMAGE` instead.

## Decision

A single new `e2e` job in `.github/workflows/ci.yml`, with `needs: [ image ]` and `if: github.event_name != 'pull_request'`.

**It never runs on a pull request.** This is how "does not block the merge" is achieved — not by leaving a check off a protection list, where a future administrator could add it, but by there being no check on the pull request to add. The job cannot become a required check without first changing its trigger, which is a visible edit to this file.

**It runs on every push to `main`, nightly, and on demand.** The nightly cadence comes from changing the workflow's existing `schedule` cron from `'0 6 * * 1'` to `'0 6 * * *'` rather than adding a second entry. One cron is enough because `needs: [ image ]` means `e2e` can only run inside a graph that has already built the images, so an E2E-specific schedule would have to reproduce the whole graph. Every other scheduled job gains frequency; none loses any. Actions minutes are free on a public repository, so the cost of the extra runs is wall clock on a machine nobody is waiting for.

**`needs: [ image ]` also supplies the skip semantics.** GitHub skips a job whose dependency failed, and the `if:` here tests only the event name — no `always()`, no `failure()` — so that default survives. A broken build therefore reports `e2e` as *skipped*, which is honest: the suite did not fail, it never got an artefact to run against.

**The images travel as a workflow artifact, not through GHCR.** `image` gains two steps, gated `if: github.event_name != 'pull_request'`, which `docker save | gzip` both `:ci` tags into a `ci-images` artifact with three-day retention; `e2e` downloads and `docker load`s them. GHCR is not usable as the vehicle because its push steps are gated on `github.event_name == 'push'`, so on a nightly or a dispatch run `e2e` would pull the previous commit's `:latest` and test a stale artefact while reporting green against today's tree — the exact failure mode this tier exists to catch.

**CI owns the stack, and the suite does not.** The job runs `docker compose -f compose.e2e.yml up -d --wait` itself and sets `E2E_REUSE_STACK=true`, the escape hatch `e2e/stack.ts` already provides. `global-setup.ts` then skips its own `up` but still performs the host-side health wait — polling `https://localhost:8443/health` until it answers 200 with a trimmed body of exactly `OK` — and `global-teardown.ts` skips `down -v`. That inversion exists so container logs survive the tests: a teardown inside the suite deletes the evidence before the job can collect it. Teardown happens in a final `if: always()` step instead.

**Notification is GitHub's own, and no step produces one.** GitHub notifies the actor of a failed workflow run through the same mechanism it uses for every other failing job, so a merge whose `e2e` run fails reaches the person who merged without a third-party email action, an `actions/github-script` step, or a secret. The job additionally writes a `$GITHUB_STEP_SUMMARY` block naming the scenarios that did not pass first time, and emits `::error::` / `::warning::` annotations so the finding attaches to the commit rather than only to the run.

**The summary reports identity, never assertion text.** The step reads `e2e/results.json` and renders `file:line`, test title, browser project and status. It does not copy failure messages, because ADR-071 established that a failing matcher prints its received value and that value can be a live join code or player token. `results.json` is the one artefact ADR-071 clears as secret-free, which is why it is both the summary's input and its own separately named artifact — EOP-221 publishes that file alone. `playwright-report/` and `test-results/` are uploaded as an ordinary Actions artifact and go nowhere near a page.

**A missing `results.json` fails the step.** k6's precedent in this workflow is that a tool can exit 0 having written nothing; silence is the failure mode to design against, so an absent or empty file is an error with its own summary block rather than a quiet skip.

## Consequences

**A regression can reach `main` and stay there for the length of one CI run.** This is the deliberate trade and it should be stated plainly rather than softened: the tier is a post-merge detector, not a gate. Trunk-based development with continuous deployment means a failing nightly may describe an artefact that is already deployed. The mitigation is that the run starts within seconds of the merge and notifies the merger, not that the window is closed.

**Notification reaches the merger, not necessarily the author.** GitHub notifies the actor who triggered the run — for a squash-merge that is whoever pressed the button, which under this project's human-merge rule is the reviewing engineer rather than the agent that opened the pull request. The `::error::` annotations narrow the gap, since an annotation attaches to the commit and so surfaces in the commit view for anyone who looks. Watchers of the repository receive the failure too. This does not fully satisfy the letter of EOP-220's fifth acceptance criterion, which says "the PR author"; closing it properly would need a bot that resolves the merge commit back to a pull request and posts a comment, which is exactly the tooling the criterion's own rationale ruled out. Accepted as-is, and recorded here so the next person does not read the criterion as met.

**Nightly runs cost six extra scheduled executions a week of the whole graph, not just of `e2e`.** Seven a week where there was one, so six more. `build`, `ui`, `image`, `supply-chain`, `dependency-cve` and both Sonar ratchets now run daily instead of weekly. On a public repository this is free and the extra canary coverage is a benefit, but it is a real increase in the number of runs a human may have to triage, and a flaky job anywhere in the graph now produces seven times as much noise as it did.

**`workflow_dispatch` is deliberately not restricted to `main`,** contrary to EOP-220's technical notes. Dispatching from a topic branch is how a change to the suite or to `compose.e2e.yml` gets exercised before it merges, nothing is published from a non-push event, and a branch guard would silently skip the job for whoever dispatched it — the worst of the available behaviours. The deviation is recorded on the ticket.

**Publishing the container logs is a new exposure, and this ADR adjudicates it rather than inheriting an acceptance.** ADR-071 enumerated container and Caddy access logs among the channels it *did not close*, but it never published them anywhere — this job does, as the `e2e-stack-logs` artifact, and on a public repository that artifact is world-readable. The join code is a URL path segment (`/api/v1/sessions/{joinCode}/players`), so every Caddy access line for a join contains a live one, and `docker compose logs` will therefore carry several per run. That is accepted here on the same bounded reasoning ADR-071 used for the HTML report and no wider: `docker compose -p eop-e2e down --volumes` runs in an `if: always()` step, so by the time the artifact is downloadable the Postgres volume holding those sessions no longer exists and the codes name nothing. The acceptance is not transitive — it self-retires the moment the `e2e` job points at a durable environment instead of a disposable stack, at which point the logs must stop being published or must be scrubbed, and ADR-071's rejection of scrubbing as unverifiable applies to them too. The alternative of not collecting them was weighed and declined: the whole reason CI sets `E2E_REUSE_STACK=true` and owns the lifecycle is that a failure in a browser test is usually undiagnosable without the server side of it.

**The suite still proves nothing about rate limiting.** `compose.e2e.yml` raises both limits to `Integer.MAX_VALUE`, and running it in CI does not change that. A green `e2e` must never be cited as evidence the shipped ceilings hold.

**Retries mean a green run is not always a clean one.** `retries: 1` under `CI` turns a first-attempt failure that passes on retry into a *flaky* result, which does not fail the job. The summary reports flaky counts and raises a `::warning::` so the signal is not lost, but a reader who looks only at the job's conclusion will miss it.

**`e2e/` remains outside both SonarQube ratchets.** `tools/sonar/scan.sh` sources `src/main/java` and `src/test/java`; `scan-ui.sh` sources `ui/src`. Wiring the suite into CI does not bring its TypeScript into either scope. `EOP-222` tracks whether a third ratchet is worth its two committed JSON files.

**The image artifact is a third copy of the same bits.** Both `:ci` images are already in the runner's daemon and, on a push, in GHCR; the artifact adds a compressed third copy with a three-day life. That is the price of a job boundary, which is itself the price of `permissions` being job-scoped — the same reason `perf-trend` exists as its own job.

## Related

- [ADR-068: End-to-end testing with Playwright over the shipped containers](ADR-068-playwright-e2e-testing.md) — the tier this decision schedules
- [ADR-071: The E2E artefact publication boundary](ADR-071-e2e-artefact-publication-boundary.md) — why the summary reports identity rather than assertion text, and why `results.json` is uploaded separately
- [ADR-061: Two new Definition-of-Done gates](ADR-061-two-new-dod-gates-sonar-ratchet-and-cve.md) — the `dependency-cve` precedent that a required check must never be skippable
- [ADR-055: The k6 performance canary in CI](ADR-055-k6-performance-check-in-ci.md) — the two-populations rule and the `$GITHUB_STEP_SUMMARY` reporting pattern this job copies
- [ADR-050: Dependency CVE gating](ADR-050-dependency-cve-scanning.md)
- `docs/devops/ci-cd-pipeline.md` — the pipeline as it now stands
- `e2e/README.md` — how to run the suite locally, and the escape hatches CI uses
