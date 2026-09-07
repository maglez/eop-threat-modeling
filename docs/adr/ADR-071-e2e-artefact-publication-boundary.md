# ADR-071: The E2E page is built from `results.json`; the HTML report's residual exposure is accepted, not contained

**Status:** Accepted

**Date:** 2026-09-07

**Deciders:** @tech-lead, @security-auditor, @devops-engineer

## Context

`@security-auditor` raised a finding during EOP-217's Definition-of-Done round and it was
filed as EOP-228, blocking EOP-221. As filed, the finding was this: `e2e/playwright.config.ts`
captures diagnostics on failure — `trace: 'on-first-retry'`, `screenshot: 'only-on-failure'`,
`video: 'retain-on-failure'` — and a Playwright trace records DOM snapshots and storage state.
The happy-path reload scenario read `sessionStorage` key `eop_session`, whose value is
`{playerToken, playerId, sessionId}`, and the helper module reads the join code out of the DOM.
So a **failing** run could produce a trace embedding a live player token, session id and join
code. Artefacts are gitignored, so nothing reaches the repository; the exposure was entirely
prospective, arriving the moment EOP-221 publishes `playwright-report/` to GitHub Pages.

The ticket asked for one of three approaches — scrub, publish a summary only, or accept the
exposure with reasoning — and required that any scrubbing claim be verified against a
**deliberately failed** run, on the stated grounds that "a green run proves nothing, since it
generates none of the artefacts".

That verification was carried out, and it produced a finding materially broader than the one
filed. **The premise that this is a failure-conditional exposure is false.** It holds for player
tokens and not for join codes.

### What was measured

A temporary probe reproduced the realistic worst case with nothing planted: two seats, a real
`createSession`, a real `joinSession`, the exact `sessionStorage` read the happy-path scenario
performed, then a failing assertion with the lobby on screen. Run as
`CI=1 npx playwright test --project=chromium`, because locally `retries: 0` means
`trace: 'on-first-retry'` never fires. The green 15/15 baseline was preserved and decoded for
comparison. Four results matter.

**1. There are four artefact classes on failure, not the three the config governs.**
`test-results/` yielded `test-failed-1.png`, `test-failed-2.png`, `trace.zip` and
**`error-context.md`** — a Playwright-authored aria snapshot of the page, emitted by default and
governed by *none* of `trace`, `screenshot` or `video`. It contains the join code in plaintext
(`- strong: BHV5AB9Z` under `paragraph: "Share this code with other players:"`) and embeds the
entire test source file. Any control expressed as "turn off the three diagnostics settings"
would have missed it.

Separately, **no video was captured at all**, despite `video: 'retain-on-failure'`. `openSeat`
builds contexts by hand via `browser.newContext()`, and `use.video` governs only the context
Playwright's own fixture creates, so that setting is inert for every scenario in this suite.
Screencast frames still land *inside* the trace, so this is not grounds to relax anything.

**2. `trace.zip` yields live credentials verbatim.** 47 entries, 1,019,293 bytes uncompressed:
the whole of `game.ts`, the spec, `test.trace`, four per-context `*-trace.trace` / `.network` /
`.stacks` sets, 19 `screencast/page@*.jpeg` frames, and `resources/` holding the captured API
response bodies as JSON. Two player tokens were read out in plaintext — 43-character base64url
secrets, one per seat. The join code appeared in 9 files, `eop_session` and `playerToken` in 5
each.

**3. The HTML report is contaminated at its core, and plaintext grep cannot see it.** An early
reading of this evidence concluded that `index.html` was clean and that deleting the
`playwright-report/data/` attachment directory would suffice. That conclusion was wrong and is
retracted here. The `html` reporter embeds its entire step tree as a base64 zip inside a
`<template id="playwrightReportBase64">` element. Decoding it on the **failing** run recovered
`eop_session` 16 times, `playerToken` 4, and two live join codes.

**4. Decoding the same element on the fully passing run overturns the ticket's premise.**
The green 15/15 baseline — no failures, no `data/` directory, no trace, no screenshot, no video —
embeds 1,470,042 bytes of JSON containing **12 distinct live join codes**, each present through
two independent channels:

- **A custom `expect()` message becomes a step title, whether or not the assertion fails.**
  `game.ts` carried `` expect(joinCode, `join code ${joinCode} is not eight characters`) ``, and
  all 12 codes appear as titles of the form `join code SVK0X78M is not eight characters` on a run
  where that assertion passed every time.
- **`locator.fill()` records the filled value in its own step title** —
  `"title":"Fill \"SVK0X78M\"","subtitle":"locator('#join-code')"`. This is inherent to typing a
  join code into `#join-code`, which is exactly what the tier exists to do.

Player token *values* appear in neither report's embedded JSON (0 occurrences in both), only in
the trace. So the two secrets have different exposure profiles, and only one of them is
failure-conditional:

- **Join codes leak on every run, pass or fail**, via the embedded step tree.
- **Player tokens leak only on failure**, via `trace.zip`.

`results.json` was clean on both runs — 0 occurrences of `eop_session`, `playerToken` and every
join code observed. Part of that is structural: the `json` reporter records no step tree, whereas
the `html` reporter embeds the whole one. But it was **not** unconditionally clean, and an earlier
draft of this ADR said it was. `results.json` carries `errors[].message`, and a failing matcher
prints the value it received back into that message: `expect(joinCode, msg).toHaveLength(8)` emits
`Received string: "ABC"` alongside the custom message, proven directly against
`@playwright/test`'s `expect`. So the *old* `game.ts` assertion would have published a join code
through this supposedly clean channel on any run where the lobby rendered a malformed code. It is
clean **given** the source fix in layer 3, which asserts on `joinCode.length` — a number the
matcher may print freely — rather than on the string. That is why layer 3 is load-bearing and not
merely tidy.

### Why the exposure is bounded even so

Recording this keeps the response proportionate. There is no authentication anywhere in the
application (ADR-015); a player token authorises only actions within one game session; and
`global-teardown.ts` runs `down -v`, destroying the Postgres volume, so a leaked token names a
session that no longer exists by the time anyone reads it. This is a hygiene problem, not an
exploitable one — today. It becomes genuine the moment the E2E stack points at a durable
environment, and publishing credentials teaches the wrong reflex regardless.

## Decision

**The world-readable page EOP-221 builds is generated from `results.json` alone. The `html`
reporter's output never becomes that page — and because this repository is public, its remaining
exposure as a build artefact is explicitly accepted rather than contained.**

That second clause is a correction. An earlier draft of this ADR called `playwright-report/` a
"collaborator-only artefact" and treated the GitHub Actions artifact as a private destination.
**That was false.** `gh repo view --json isPrivate,visibility` returns
`{"isPrivate":false,"visibility":"PUBLIC"}` for `maglez/eop-threat-modeling`, and on a public
repository Actions artifacts and workflow logs are **world-readable to anyone with the URL**.
There is no private destination available in this pipeline at all. Pretending otherwise would have
made this ADR assert a control that does not exist, which is worse than admitting the exposure —
so the decision below takes EOP-228's *third* permitted option for everything the first two
layers do not remove.

Four layers. The first is a control; the second is an acceptance; the third and fourth are
hardening.

1. **Destination split by artefact, not by audience.** GitHub Pages is built only from
   `results.json`, which is measurably free of join codes and tokens given layer 3.
   `playwright-report/` in its entirety — `data/`, the bundled trace viewer and the embedded step
   tree — is never published as the page. This is the one layer that genuinely subtracts, and it
   removes 100% of the token material and the visual representations, because those exist only
   inside `trace.zip` and `data/`.
2. **Accepted residual exposure.** `playwright-report/` and `test-results/` are still uploaded as
   Actions artefacts so failures can be debugged, and on a public repository that upload is
   world-readable. The join codes in the embedded step tree and the tokens in `trace.zip` are
   therefore *published*, to anyone who looks. **This is accepted, for the reasons in "Why the
   exposure is bounded even so" above**: no authentication exists anywhere in the application
   (ADR-015), a player token authorises only actions inside one game session, and
   `global-teardown.ts` runs `down -v`, so the Postgres volume and every session in it are
   destroyed before the artefact is downloadable. What is published is a credential for a session
   that no longer exists. The acceptance is bounded and self-retiring: **it must be revisited the
   moment the E2E suite points at any environment whose data outlives the run**, at which point
   the only remaining options are a private repository, dropping the artefact upload, or a
   scrubber with the problems set out below.
3. **Source hardening**, closing the two avoidable channels. `game.ts` asserts on the join code's
   *length* rather than the string, which keeps the failing matcher from printing the code back as
   its received value and keeps the passing step title free of it. The happy-path reload scenario
   evaluates `sessionStorage.getItem('eop_session') !== null` in the browser rather than returning
   the value into the test process — non-nullness is all the assertion ever needed.
4. **A build gate**, `E2eArtefactPublicationBoundaryTest`, pinning the parts of this that are
   mechanically checkable so the decision cannot regress silently.

### Channels this decision does not close

Enumerating them is part of the acceptance. A reader who believes the list above is exhaustive
would draw a stronger conclusion than the evidence supports.

- **The `list` reporter's stdout.** `playwright.config.ts` keeps `['list']`, whose output is the
  CI job log — world-readable on this public repository, exactly like the artefacts. Measured
  mitigation: four real Playwright stdout logs from passing runs (18,085, 17,803, 7,369 and 2,307
  bytes) contain **0** occurrences each of `eop_session`, `playerToken`, `join code`, `Fill `,
  `fill(` and `Share this code`, and no 8-character join-code candidates. The `list` reporter
  prints test titles and statuses, not the step tree, which is why. That is a measurement over
  passing runs, not a guarantee: a *failing* assertion's message reaches stdout too, which is a
  second reason layer 3 matters.
- **Container and reverse-proxy access logs.** `E2E_REUSE_STACK` exists so CI owns the stack and
  can collect container logs *after* the tests. The join code is a URL path segment —
  `docs/api/openapi.yml` declares `/api/v1/sessions/{joinCode}/players`, and `ui/src/api.ts`
  fetches exactly that — so every Caddy access line for a join request carries a live join code.
  Any decision to publish those logs inherits this ADR's acceptance and should say so.
  That decision was taken on 2026-09-07 by [ADR-072](ADR-072-e2e-ci-integration.md), which
  publishes them as the `e2e-stack-logs` artifact and says so explicitly in its Consequences
  rather than leaning on this sentence. Note what this bullet does **not** say, because it has
  been misread once: it does not forbid publication and it does not require scrubbing. It
  delegates the decision, with a duty to record it.
- **`blob-report/` and `.last-run.json`.** Gitignored and not produced by the current
  configuration, but both are Playwright outputs and `blob-report/` is a merge-ready form of the
  same step tree. Neither may be published as the page.

None of these is closed here, and none is a reason to weaken layer 1. They are recorded so that
"EOP-228 is closed" is read as "the page is safe and the rest is accepted with reasons", not as
"no join code is ever visible".

### Why not scrubbing

The ticket offered scrubbing first, and it is the option that cannot be made trustworthy. The
join code and tokens exist in at least six independent representations: API response bodies in
the trace's `resources/*.json`, DOM snapshots in `*-trace.trace`, network logs in
`*-trace.network`, the aria-snapshot markdown, **visually rendered** in screencast JPEGs and
failure PNGs, and step titles in the embedded report JSON. No regex reaches the visual class at
all. The trace is a version-dependent internal zip format, so a scrubber written against
Playwright 1.63.0 would rot at the next upgrade with no signal — and a scrubber that silently
stops matching is worse than none, because the report keeps being published and the claim keeps
being made. Subtraction is verifiable where transformation is not: removing the destination
removes 100% of the recovered material, and the check is "did the public page receive only
`results.json`", which a human can confirm by looking.

### Why the `fill()` channel is left open

It cannot be closed without damaging the tier. Setting the input value through `page.evaluate`
would suppress the step title but stop exercising real user input, which is the entire reason
this tier exists rather than another integration test. So layer 3 deliberately closes one
channel and not the other. This is worth stating plainly: **the HTML report will always contain
join codes, by design, and there is no destination in this pipeline that keeps them private.** The
control is that the report is not the published page; the residual is accepted under layer 2 and
bounded by the session being destroyed before anyone can read the artefact.

## Consequences

**EOP-221 is now constrained rather than merely unblocked.** It may not publish
`playwright-report/` to Pages. Its report index page reads `results.json`, which the reporter
comment in `playwright.config.ts` already anticipated ("`json` as a machine-readable summary for
the report index page EOP-221 builds"), so this narrows an existing intent rather than inventing
a new one.

**No CI wiring changes here, because there is none to change.** `grep -n 'playwright\|e2e\|E2E'
.github/workflows/ci.yml` returns nothing; EOP-220 wires the suite in and EOP-221 publishes.
This ADR is therefore a constraint recorded *before* the mechanism exists — which is the whole
point of closing EOP-228 before EOP-221 rather than after.

**Three reporters stay exactly as they are.** `list`, `html` and `json` all remain. Nothing about
the local developer experience changes: `npm run report` still opens the full report with traces.

**The diagnostics settings stay on.** `trace`, `screenshot` and `video` are untouched — the
finding is about where artefacts go, not whether they exist. `video` remains inert for this
suite's hand-built contexts, which is recorded here so the next author does not read the setting
as working; fixing it is not required by this decision and would only add a fourth
representation of the same secrets to a channel whose residual is already accepted.

**What the build gate does and does not prove.** `E2eArtefactPublicationBoundaryTest` reads
repository files as text, in the tradition of the other seventeen classes in
`src/test/java/org/maglez/eop/docs/`. Five rules. It fails the build if a custom `expect()`
message in `e2e/` interpolates a join code or token; if an `expect()` is *asserted on* a secret
value, since a failing matcher prints its received value back; if the happy-path scenario returns
a raw `eop_session` value into the test process; if a workflow step is added that publishes
`playwright-report/` to the Pages branch; and if `e2e/README.md` stops recording the outcome. Each
rule was positive-controlled by reintroducing the regression and watching it fail, not merely
observed passing. It cannot prove that a future publication step is safe — CI does not exist yet,
and a sufficiently novel step will evade a text matcher. It converts the specific regressions that
have actually been measured here into build failures, and it makes the decision discoverable from
the test tree. The remaining enforcement is review, and EOP-221's own Definition-of-Done round is
where it lands.

**A green run is now evidence of something.** Before this change, the ticket's own instruction —
verify against a failed run, because a green run proves nothing — was sound advice built on a
false premise. After the source fix, decoding a passing run's embedded report is a meaningful
check on channel 1, and that decode is written down in `e2e/README.md` so the next author can
repeat it rather than rediscover it.
