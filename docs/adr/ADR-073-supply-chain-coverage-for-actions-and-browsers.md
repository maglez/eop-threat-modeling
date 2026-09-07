# ADR-073: Supply-chain audit covers GitHub Actions and Playwright browser binaries

**Status:** Accepted

**Date:** 2026-09-07

**Deciders:** @tech-lead, @security-auditor, @dependency-vulnerability

## Context

During the EOP-220 Definition-of-Done gate round, two gates independently observed that three supply-chain surfaces fell outside every existing audit:

1. **GitHub Actions.** All 29 `uses:` references in `.github/workflows/ci.yml` were pinned to a moving major tag — `actions/checkout@v4` and so on. A tag is mutable by its owner, so a compromised or malicious maintainer can retarget it onto any commit and every consumer picks it up on the next run with no diff anywhere. The precedent is the **March 2025 tj-actions/changed-files incident**, where an attacker retargeted existing tags onto a commit that dumped runner memory — including secrets — into publicly readable build logs.

2. **Playwright browser binaries.** The `e2e` job ran `npx playwright install --with-deps`, downloading Chromium, Firefox and WebKit from Microsoft's CDN at job time and `apt-get install`ing system libraries **as root**. Nothing pinned a browser build and nothing verified what arrived. The story calls it the highest-privilege unpinned download in the pipeline. Playwright does pin exact browser *revisions* per package release in its own `browsers.json`, but it does **not** checksum-verify the CDN download — which is exactly why a version number alone is not a pin.

3. **`e2e/package-lock.json`.** `tools/supply-chain/scan-dependencies.sh` gates the trees that ship — `pom.xml` and `ui/package-lock.json`. Trivy detects `e2e/package-lock.json` but the gating pass omits dev dependencies, so it was suppressed as a side effect rather than by decision. And `@playwright/test` was specified `^1.63.0`, so the tree drifted on any regenerated lockfile.

The acceptance criteria for this story were explicit: **a decision must be recorded for each surface — pin it, or accept it with a written reachability argument. Silence is not one of the options.**

## Decision

### Decision 1 — GitHub Actions: PIN, with a new baseline and a new audit

Every third-party action is now pinned to a 40-hex commit SHA with the exact release recorded in a trailing comment, in the form `owner/repo@<40 hex> # v<MAJOR.MINOR.PATCH>`. The nine distinct actions and their pins:

- `actions/checkout` `11d5960a326750d5838078e36cf38b85af677262` v4.4.0 — 9 sites
- `actions/setup-java` `cf277c60eb25467037889841efdb72551f06f6c3` v4.9.1 — 2
- `actions/cache` `0057852bfaa89a56745cba8c7296529d2fc39830` v4.3.0 — 3
- `actions/upload-artifact` `ea165f8d65b6e75b540449e92b4886f43607fa02` v4.6.2 — 6
- `actions/setup-node` `49933ea5288caeca8642d1e84afbd3f7d6820020` v4.4.0 — 3
- `actions/download-artifact` `d3f86a106a0bac45b974a628896c90dbdf5c8093` v4.3.0 — 2
- `docker/setup-buildx-action` `8d2750c68a42422c14e847fe6c8ac0403b4cbd6f` v3.12.0 — 1
- `docker/build-push-action` `10e90e3645eae34f1e60eeb005ba3a3d33f178e8` v6.19.2 — 2
- `docker/login-action` `c94ce9fb468520275223c153574b00df6fe4bcc9` v3.7.0 — 1

A new baseline `tools/supply-chain/expected-actions.json` and a new audit `tools/supply-chain/audit-actions.sh` are wired into the existing non-required `supply-chain` CI job as an `if: always()` step placed after the container audit and before the Node setup.

**This baseline is a policy, not merely a tripwire — the inverse of what `expected-containers.json` says about itself.** A container pin names a digest, and a digest *is* the content, so that file cannot tell you an image went bad. Here the situation differs in one decisive way: a git SHA is content-addressed like a digest, **but the thing normally written in a workflow — a tag — is mutable by default**. So this audit can make the *unpinned form itself* a failure, which neither of the other two can. The check that every `uses:` reference is `owner/repo@<40 hex>` **does not consult the baseline at all**, so a tenth action added by tag fails on its first commit rather than after somebody remembers to declare it. That is precisely acceptance criterion 3 — the next bare tag reference fails an audit instead of passing silently.

The audit's six failure modes, all demonstrated to fire: (1) any `uses:` reference not pinned to 40 hex; (2) a referenced action absent from the baseline; (3) a SHA mismatch at any site, so one action pinned to two commits fails; (4) `reference_count` disagreement; (5) bidirectional `occurrences` disagreement; (6) a declared action no longer referenced anywhere, so a stale entry fails. It additionally validates the baseline's own shape first and exits **2** rather than 1 if the baseline is malformed — mandatory fields present, no surplus fields, `sha` 40 lower-case hex, `tag` matching `v\d+\.\d+\.\d+` so a major alias is rejected, `occurrences` a non-empty list. Local refs (`./…`) and container refs (`docker://…`) are skipped with a stated reason.

**The residual is accepted honestly.** A SHA pin freezes security fixes as well as attacks. There is no Dependabot configuration in this repository, and the audit is deliberately network-free, so nothing tells us a newer release exists — the pins go stale silently and are moved by hand with `gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq .object.sha`. The named retiring condition is the adoption of an automated bump mechanism. The audit is in the `supply-chain` job, which is deliberately **not** a required status check — the required gates on `main` are `build`, the two Sonar ratchets and `dependency-cve`, all of which concern shipped code.

### Decision 2 — Playwright browser binaries: PIN, via a digest-pinned container

The `npx playwright install --with-deps` step is deleted. The suite now runs inside the digest-pinned official image:

```
mcr.microsoft.com/playwright:v1.63.0-noble@sha256:eff16c30e6f3f4af0a03fa4b706120d5e9b0891c344a27d64559aff5900a4a27
```

invoked from the runner as a single `docker run` (`--rm --network host --ipc host -u "$(id -u):$(id -g)" -e HOME=/tmp -e E2E_REUSE_STACK --mount type=bind,source="$(pwd)",target=/e2e --workdir /e2e`). The digest appears exactly once in the repository.

**Why a `docker run` from the runner rather than a `container:` job.** The `e2e` job drives `docker compose -f compose.e2e.yml up -d --wait` on the runner itself to bring up the application, the UI and the database; a container job has no shared daemon, so the compose lifecycle would have to move. `--network host` is what makes this work: on a Linux runner it lets the suite's health gate reach `https://localhost:8443/health` on the host's published port exactly as before. `--ipc host` is the flag Playwright recommends to avoid Chromium shared-memory crashes. Only `e2e/` is bind-mounted, which is sufficient because `npm ci` has already populated `e2e/node_modules` on the runner. `-u` with the runner's uid keeps `results.json`, `playwright-report/` and `test-results/` owned by the runner user that the three `actions/upload-artifact` steps then read.

**What this buys.** No root `apt-get` anywhere in the pipeline. The browser binaries are now content-addressed: the pinned image was verified to contain browser revisions `chromium-1243`, `chromium_headless_shell-1243`, `ffmpeg-1011`, `firefox-1543` and `webkit-2359` under `/ms-playwright`. And the surface joins the **existing** `expected-containers.json` audit as its sixth entry rather than needing a baseline of its own.

**One surprise worth recording.** The image ships its own Node — 24.20.0 at this digest — while `actions/setup-node` installs Node 22 on the runner. So `npm ci` and `npm run typecheck` execute under Node 22 and the suite executes under Node 24. That is safe only because `e2e/`'s three devDependencies are pure JavaScript and the browsers come from the image, so nothing is compiled against the installing Node, and `engines.node >= 22.12` is satisfied by both. A dependency with a native component would break on exactly this seam.

**The coupling, and its enforcement.** The image tag must name the same release as `@playwright/test`, or Playwright refuses to start reporting that the browser executable does not exist. A new build gate, `src/test/java/org/maglez/eop/docs/PlaywrightImagePinTest.java`, holds four artefacts in agreement on `1.63.0`: the tag in `.github/workflows/ci.yml`, the `tag` field in `tools/supply-chain/expected-containers.json`, the spec in `e2e/package.json` and the resolved version in `e2e/package-lock.json`. It also fails on an unpinned mention (tag with no digest) and on a range operator in the manifest. Its stated bound is that it cannot prove the digest corresponds to the tag — that needs the network and is `audit-containers.sh`'s job.

### Decision 3 — `e2e/package-lock.json`: exact-pin, then ACCEPT non-scanning with a named retiring condition

First, the drift is **closed by a pin, not accepted**: all three `e2e/` devDependencies are now exact — `@playwright/test 1.63.0`, `@types/node 22.20.1`, `typescript 5.9.3` — and the lockfile's root range block was regenerated to match with no resolved version changing. The exact pin on `@playwright/test` was required anyway by the container coupling in decision 2.

Second, the tree is **deliberately not brought into any gating scan**, and that choice is now recorded as an aligned entry in the `scan-dependencies.sh` header list "What it scans, and what it deliberately does not", beside the existing entries for `pom.xml`, `ui/package-lock.json`, `.opencode/`+`tools/` and `target/`.

**Why accept rather than scan.** This is consistent with a decision the repository has already made, not a shortcut. The `supply-chain` job's own preamble argues that the required gates "are all about the code that ships: a new advisory published against a transitive dependency of a developer tool is a thing to know about, not a reason to block a merge of unrelated application code" — a statement about blast radius. `e2e/`'s three devDependencies are compiled into neither the jar nor `ui/dist`, so a finding there is not a shipped vulnerability and must never be read as a release blocker. The bound is the `e2e` job's own: `permissions: contents: read` only, no repository secrets at all, and nothing published from a non-push event.

**The retiring condition.** A date would force the argument to be re-made on a calendar while the two things that actually change the calculus went unwatched. The retiring condition is: bring `e2e/` into a scan when **either** the repository gains a secret the `e2e` job can reach, **or** `e2e/` gains a dependency that executes against production data or credentials.

**The rejected alternative.** A second, informational Trivy pass over `e2e/` inside `scan-dependencies.sh`, with the acceptance held in a new `accepted-non-scanning.json` carrying `expires: 2027-12-31`. It was rejected on four grounds. It contradicted the decision above. It added a `FATAL` preflight requiring `e2e/package-lock.json` to exist, making the gate over the *shipped* trees abort when a *test* tree's lockfile was missing — a real blast-radius regression in the one script whose job is gating what ships. Its expiry could not fire: the check only printed a warning, which is exactly the failure mode `accepted-cves.json`'s own `_comment` names ("an unfireable entry rots into a claim nobody checks"). And none of its 115 lines could fail, so it was pure reporting bolted onto the one script that gates, muddying its purpose for zero enforcement.

## Consequences

- `tools/supply-chain/` now audits **three** populations: the seven OpenCode npm plugins (`expected-plugins.json`), the digest-pinned containers (`expected-containers.json`, now **six** entries — `grafana/k6`, `grafana/loki`, `grafana/promtail`, `sonarqube`, `sonarsource/sonar-scanner-cli` and `mcr.microsoft.com/playwright`), and the pinned GitHub Actions (`expected-actions.json`, nine actions across 29 references). Note that AGENTS.md and ADR-064 both still say "three digest-pinned containers", a count that was already stale at five before this story — the Tech Lead is correcting that separately.
- The Maven plugin layer remains uncovered by any of the three, exactly as ADR-064 already recorded.
- The `supply-chain` job is still not a required status check, so none of these findings blocks a merge.

## Verification

- `./mvnw clean verify` → **BUILD SUCCESS, exit 0**, 4:13 min. surefire `Tests run: 1481, Failures: 0, Errors: 0`; failsafe `Tests run: 13, Failures: 0, Errors: 0`. enforcer, checkstyle, spotbugs, javadoc (cold path) and jacoco all ran clean.
- `tools/supply-chain/audit-actions.sh` → exit 0, `OK: every third-party action is pinned to a declared commit SHA.` — 1 workflow file, 29 third-party sites, 9 distinct actions, 9 declared.
- `tools/supply-chain/audit-containers.sh` → exit 0, `=== PASS: every pinned container is declared, correctly shaped and consistently recorded ===`, six entries, and the tag-drift section confirms `mcr.microsoft.com/playwright:v1.63.0-noble still resolves to the pinned digest`.
- Four positive controls on `audit-actions.sh`, all exit 1 with naming findings: a bare `@v4` tag; a 39-hex truncated SHA; an undeclared action added by SHA (which also tripped the `reference_count` check independently); and a tenth `actions/checkout` site against a baseline recording nine.
- Three positive controls on `PlaywrightImagePinTest`, all failing with informative messages: image tag moved to `v1.62.0-noble`; the caret restored on `@playwright/test`; the digest dropped leaving a bare tag.
- A local container smoke run proved the non-root uid works (`uid=1593830243`), `/ms-playwright` is readable with the five browser revisions listed above, an explicit writable `HOME` suffices, and `npx playwright --version` resolves `1.63.0` from the mounted `e2e/node_modules`.

## Related

- [ADR-064](ADR-064-pinned-container-audit-coverage.md) — the container audit this extends with a sixth entry
- [ADR-050](ADR-050-dependency-cve-scanning.md) — the Trivy CVE gate, whose scope this decision deliberately leaves alone
- [ADR-072](ADR-072-e2e-ci-integration.md) — the E2E CI integration this decision builds on
- `tools/supply-chain/audit-actions.sh` — the new Actions audit
- `tools/supply-chain/expected-actions.json` — the new Actions baseline
- `tools/supply-chain/audit-containers.sh` — the container audit, now covering six entries
- `tools/supply-chain/expected-containers.json` — the container baseline, now covering six entries
- `src/test/java/org/maglez/eop/docs/PlaywrightImagePinTest.java` — the build gate enforcing the Playwright image coupling