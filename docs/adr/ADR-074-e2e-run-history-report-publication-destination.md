# ADR-074: The E2E run-history report publishes to a subdirectory of the existing Pages branch, not an orphan branch

**Status:** Accepted (2026-09-07)

**Date:** 2026-09-07

**Deciders:** @tech-lead, @devops-engineer, @product-owner

## Context

`EOP-221` publishes a persistent, stakeholder-readable **E2E run-history report** to GitHub Pages. The ticket originally specified an orphan branch named `e2e-report`. That is not what was built, and this ADR records why — because the constraint that forced the change is exactly the kind of fact that gets painfully rediscovered.

### Constraint 1 — a repository has exactly one GitHub Pages site, and it was already claimed

Measured, verbatim:

```
$ gh api repos/maglez/eop-threat-modeling/pages
{"status":"built","build_type":"legacy","cname":null,"custom_404":false,
 "source":{"branch":"perf-history","path":"/"},
 "html_url":"https://maglez.github.io/eop-threat-modeling/",
 "public":true,"https_enforced":true}
```

With `build_type: legacy` the Pages source is a single branch + path pair. It was already bound to `perf-history` at root, serving the k6 CI performance trend page introduced by `EOP-169` under `ADR-055` §5. Pointing Pages at a new `e2e-report` branch would have taken that page offline. So the ticket's premise was **unsatisfiable as written**, not merely inconvenient.

## Decision

Keep Pages bound to `perf-history` at root. The new `e2e-report` CI job writes into an **`e2e/` subdirectory of that same branch**:

- `e2e/e2e-history.jsonl` — one flat JSON row per published run
- `e2e/index.html` — the tracked page, copied from `tools/e2e/report-page.html` on every publish

Served at **`https://maglez.github.io/eop-threat-modeling/e2e/`**. The perf trend page at the site root is untouched and still served at `https://maglez.github.io/eop-threat-modeling/`.

There is **no `e2e-report` branch**. The job is *named* `e2e-report`; that is a job name, not a branch name. Say so explicitly — the mismatch between the job name and the ticket's original branch name is itself a trap for a future reader.

### Alternatives considered and rejected

1. **A dedicated `site` branch** holding `/perf/` and `/e2e/`. Conceptually the cleanest layout. Rejected because it requires a **human** Pages settings change (an agent cannot make it) and because it **moves the already-published perf URL**, which is documented in `.opencode/rules/performance-testing.md` and inside `tools/perf/trend-page.html`'s own summary block. Worth recording as the option to revisit if a third series ever appears — at that point the rename is probably worth paying for once.

2. **Switch Pages to Actions deployment** (`build_type: workflow`, `actions/deploy-pages`). Rejected: rewires perf publication as collateral, needs `pages: write` plus `id-token: write`, needs a human settings change, and discards `ADR-055`'s deliberate choice to serve straight from a branch with no deploy step.

3. **`e2e-report` as a data-only branch**, with the page fetching the JSONL cross-branch from `raw.githubusercontent.com`. Rejected: two sources of truth plus a cross-origin runtime dependency, for no gain.

## Consequences

### Two populations, one branch — not a rule violation

`perf-history` now hosts two unrelated measurement populations, so its name is a mild misnomer. **This is not a breach of `.opencode/rules/performance-testing.md`'s "two populations of measurement, and never one series" rule.** That rule forbids mixing *metric series* — a local developer baseline and a CI canary figure in one trend line, where a hardware step change would read as a code regression. Here the two series stay two files in two directories with two pages and no shared axis. Say this explicitly and say why, so that shared branch tenancy is never later misread as merged populations, and so a future reader does not "fix" a violation that does not exist.

### Two jobs write the same branch — the retry loop is now load-bearing

Two jobs now write the same branch. `perf-trend` owns `ci-history.jsonl`, `index.html` and `.nojekyll` at the branch root; `e2e-report` owns `e2e/` only. They use deliberately *different* `concurrency` groups, so both can run on the same push and race. The race is reconciled by each job's 5-attempt clone-append-push retry loop rather than by serialising them — safe precisely because the write sets are disjoint, so a loser replays onto the new tip with nothing to merge. Neither job may ever force-push. Record that the retry loop is therefore now **load-bearing for correctness**, where in `perf-trend` alone it had only guarded against a concurrent human push.

### One `.nojekyll` at the branch root covers the whole branch

One `.nojekyll` at the branch root covers the whole branch, so `e2e/` needs no second copy. `perf-trend` already commits it; `e2e-report` only creates it if absent, which matters on the bootstrap path alone.

### Relationship to ADR-071

`ADR-071` constrains *what* may be published: the page is generated from `results.json` alone, and the Playwright `html` reporter's output never becomes that page — because decoding the `<template id="playwrightReportBase64">` element on a **fully passing** 15/15 run recovered 12 distinct live join codes. `ADR-074` constrains *where* it is published. **`ADR-074` does not supersede, amend or relax `ADR-071`** — `ADR-071`'s status stays Accepted and its text is not edited.

## Related

- `ADR-055` — k6 performance check in CI, which introduced the `perf-history` Pages branch
- `ADR-068` — Playwright E2E testing, the tier whose runs are now published
- `ADR-071` — E2E artefact publication boundary, constraining what may be published
- `ADR-072` — E2E CI integration, the job that runs the suite
- `EOP-221` — the ticket that adds the run-history report