# ADR-064: Digest-pinned containers are audited as a roster, not one image at a time

**Status:** Accepted

**Date:** 2026-09-04

**Deciders:** @tech-lead, @architecture-guardian, @security-auditor

## Context

[ADR-055](ADR-055-k6-performance-check-in-ci.md) introduced the first digest-pinned
external container into CI — `grafana/k6:2.2.0@sha256:9bd01d69…`. During that story's
Definition-of-Done round, @security-auditor observed that `tools/supply-chain/` audits
the seven OpenCode npm plugins baselined in `expected-plugins.json` and **nothing else**:
the container layer, the GitHub Actions layer and the whole Maven plugin layer are
uncovered. The finding was raised explicitly as non-blocking, and it was right to be —
the k6 image is digest-pinned and carries buildx provenance and SBOM attestations, which
is a *stronger* posture than the unpinned Actions already in the same workflow. EOP-159
is that observation as a story.

Two facts shape the decision.

**A digest pin is not an npm pin.** `audit-plugins.sh` earns its keep because an npm
version specifier names a mutable target: the registry can serve different bytes for the
same version, maintainership can change hands, and an advisory can be filed against a
version already installed. None of that applies to `repo@sha256:…`, which *is* the
content. So a container audit modelled on the plugin audit would be a tripwire for an
event that cannot happen, and describing it as compromise detection would be false
advertising.

**The real hazards here are procedural, and two of them have already occurred.** ADR-055
amendment 2 records two traps hit empirically while deriving that one pin: the tag is
`2.2.0` and not `v2.2.0`, and the pin must be the OCI **index** digest read from the
top-level `Digest:` of `docker buildx imagetools inspect` — never a per-platform
sub-manifest (pinning arm64 breaks `ubuntu-latest` with "no matching manifest for
linux/amd64") and never the output of `docker inspect` on a developer Mac, which reports
a third digest again. Both traps are recorded in prose. Prose does not run.

A third hazard was found while surveying the repository for this story, and it is live.
`sonarsource/sonar-scanner-cli` is pinned in **three** committed files —
`tools/sonar/scan-ui.sh`, `tools/sonar/sonar-ui-baseline.json` and
`tools/sonar/sonar-ui-report.json`, the latter two under the key `scannerImage`. Bumping
the digest in the script without rescanning leaves two committed files asserting a
scanner that was not used, and **nothing catches it**: `sonar-ratchet-ui`'s `sourceHash`
covers `ui/package.json`, `ui/tsconfig.json`, `ui/vite.config.ts` and `ui/src/**`, so an
edit to `scan-ui.sh` does not invalidate the report it produced.

## Decision

Add `tools/supply-chain/audit-containers.sh` and `tools/supply-chain/expected-containers.json`,
and run the script as a step in the existing non-required `supply-chain` CI job.

**Scope is every digest-pinned container in the repository, not the k6 image alone.**
There are three: `grafana/k6` (`.github/workflows/ci.yml`), `sonarqube`
(`compose.sonar.yml`) and `sonarsource/sonar-scanner-cli` (the three files above).
Covering one of three under a file named `expected-containers.json` would be a misleading
tripwire, by exactly the argument `audit-plugins.sh` already makes about roster drift: a
pin with no baseline entry is a pin nothing checks.

**What the audit checks**, in order of how much a failure would actually tell you:

1. **Roster drift, bidirectionally and hermetically.** Every `name@sha256:…` reference in
   a tracked file is discovered by regex; each distinct image must have exactly one
   baseline entry and each baseline entry must be pinned somewhere. The reverse direction
   also catches *unpinning* — replacing a digest reference with a bare tag orphans the
   baseline entry — and the failure text says so, because that is not the reading a
   reader reaches for first.
2. **Mirror agreement, hermetically.** Where an image is pinned in more than one file, all
   occurrences must carry the same digest, and the set of occurrence paths must equal the
   baseline's. This is the check that closes the `scannerImage` hole above.
3. **Pin form, hermetically.** `sha256:` plus 64 lowercase hex; a `latest` tag is rejected
   outright; the baseline's field set is enforced in both directions so a surplus or
   misspelled field fails rather than reading as a harmless extra.
4. **Registry shape, over the network.** `docker buildx imagetools inspect --raw` — chosen
   over hand-rolled registry auth *and* over `docker manifest inspect` so that the audit
   and ADR-055's pinning procedure name the same authoritative tool. The digest must still
   resolve; `mediaType`, the platform list and the attestation count must match the
   baseline. **`linux/amd64` must be reachable, but only for an image whose occurrences
   include a path under `.github/workflows/`** — that is ADR-055's trap 2 encoded for
   every future pin, and gating it on CI usage lets a local-only image be legitimately
   single-platform.
5. **Tag drift, reported and never gated.** Where a reference carries a tag, the tag's
   current digest is compared to the pin and any difference is printed. A mutable tag
   moving is ordinary upstream behaviour and is the entire reason the digest is there;
   failing on it would invert the mechanism. This is the inverse of the npm case, where
   the pin is an exact version and movement *is* the signal.

**`mediaType` is compared against the baseline rather than required to be an index.** The
survey found that `sonarsource/sonar-scanner-cli` is legitimately *not* an index — it is a
single-platform `application/vnd.docker.distribution.manifest.v2+json` with no `manifests`
array at all, and therefore no attestations, its platform readable only from the config
blob. A universal "must be an index" rule would have been wrong on a third of the roster.
Comparing to the baseline makes *drift* the failure, which is the correct tripwire posture
and matches how `expected-plugins.json` already treats `provenance: false`.

**Baseline fields**, seven per entry, all mandatory: `tag` (which **may be `null`** — two
of the three references are tagless, and mandatory-but-nullable is deliberately the
`expiry: null` idiom from the feature-flag registry), `digest`, `mediaType`, `platforms`,
`attestations`, `occurrences`, `note`.

**A registry failure exits 2, "could not run", not 0.** An unreachable registry, an
anonymous Docker Hub rate limit and an offline laptop are indistinguishable from inside
the script, and none of them is evidence a pin is wrong. Finding *zero* pinned references
also exits 2 rather than passing, because a clean scan of nothing is the failure mode a
discovery regex has.

**Wiring:** a new step in the existing `supply-chain` job, placed immediately after
checkout and *before* `Set up Node 22`, so container feedback does not wait on an npm
install it does not need; and `if: always()` added to the existing plugin-audit step so a
container failure cannot hide plugin drift. No new job — that would duplicate a checkout
for a five-second script, and the job name `supply-chain` already generalises.

### Scope deliberately not taken

- **The GitHub Actions layer.** Nine distinct actions over fifteen call sites, none
  digest-pinned. This is pre-existing posture and explicitly *not* an EOP-155 regression,
  and pinning them by digest without Dependabot to move the pins is a maintenance
  treadmill that would be abandoned. A separate story, honestly scoped, or not at all.
- **The Maven plugin layer.** Fifteen bound plugins and about thirty-one in the effective
  `pluginManagement`, most of their versions owned by the Spring Boot parent, so a parent
  bump moves about thirty in one line. That is the largest genuinely uncovered surface in
  the repository, and it is a different mechanism — a version range resolved at build
  time, not a content address. It needs its own decision.
- **Image CVE scanning.** Rejected reusing the argument `.github/workflows/ci.yml` already
  makes for keeping `dependency-cve` off `.opencode/` and `tools/`: two gating scanners
  over one tree means two allowlists to keep in step, and the first divergence would be
  silent. These three containers are CI and developer tooling and are not shipped, so
  ADR-050's scope is unchanged.

## Consequences

- The two pinning traps ADR-055 discovered are now enforced for every *future* pin rather
  than recorded for one past pin. The amd64 check was negative-tested against the real
  historical mistake — k6's genuine arm64 child digest, with a fully self-consistent
  baseline describing it accurately — and caught it on that check alone.
- The live `scannerImage` mirror hole is closed. Sixteen negative tests were run against
  the script and all sixteen fail with an accurate first finding; the mirror case was
  produced by editing the digest in `sonar-ui-report.json` only.
- **This audit cannot tell you an image went bad, and must never be described as though it
  could.** It reads no image contents, no CVE feed and no signatures. It answers three
  questions: is every pinned container declared, is each pin well-formed and usable where
  it is used, and do the mirrored copies of a digest still agree. A green run is not a
  statement about the software inside those images.
- The job now depends on the Docker Hub registry being reachable and on anonymous pull
  rate limits on shared runner IPs. `supply-chain` is not a required check, so a flake
  costs a re-run rather than a blocked merge — but it *is* a new source of red that is
  nobody's fault, and treating exit 2 as equivalent to exit 1 would train people to ignore
  the job.
- Bumping a container pin is now a two-file change: the pin and its baseline entry, in the
  same reviewed commit. Never the baseline alone to turn a red job green.
- Three pinned containers is a small enough roster that the baseline could be read by eye.
  The value is in the checks that are *not* eye-readable — registry shape and mirror
  agreement — and in the roster growing without anyone remembering to look.
- Two layers remain uncovered and are now named as uncovered rather than merely unnoticed,
  which is a smaller improvement than closing them but a real one.

### Amended 2026-09-04 (EOP-159, gate round) — the parser is the part that can fail silently

`@code-reviewer` rejected the first revision on the discovery regex, and the finding was
correct. The pattern could not match a registry-qualified reference carrying an explicit
port: `registry.example.com:5000/img@sha256:…` parsed as the image `5000/img`, because the
character class for a path component excludes `:` and so the host was consumed by the
optional-tag group. `ghcr.io/owner/name@sha256:…` was never affected — a dot is already
legal inside a path component, and only the colon is not.

The precise failure mode is worth recording, because it is narrower than it first looks and
the narrowness is what makes the *second* change below the important one. A misparse yields
a **wrong name**, not a silent miss: `5000/img` has no baseline entry, so roster drift fails
loudly, just with a finding naming an image that does not exist. The fix is an optional
leading `(?:[a-z0-9][a-z0-9.-]*:[0-9]+/)?` alternative, which demands `:<digits>/` and so
cannot swallow an ordinary first path component.

Two decisions follow from that.

**The regex now carries a self-test that runs before any file is opened.** `REF_CASES` pins
nine readings — the three port-qualified cases the old pattern failed, `ghcr.io/…`, a
tagless single-segment name, a tagged two-segment name, and a negative case proving a bare
`sha256:…` in a comment is *not* read as a reference (the form `compose.sonar.yml` and
ADR-055 both use for child-platform digests, so a regex that matched it would report
phantom images). The reasoning is the asymmetry above: every other check in this script
compares two things and reports a difference, so it cannot fail quietly, whereas a
reference the parser never sees is a pin the audit never mentions and the run still goes
green. That is the only silent-failure path in the design, and it is now closed by a check
that costs microseconds and fails the run before the discovery section prints anything.

**The self-test was proven to fire, not merely written.** The regex was reverted to the
defective form and the script run: it printed `=== REFERENCE PARSER SELF-TEST FAILED ===`
with the three per-case expected-vs-got lines and exited 1, before any image was listed.
This is the same standard ADR-055 amendment 6 arrived at the hard way — a check nobody has
watched fail is a check nobody knows works.

Two of the seven gates independently observed that the script has no automated test harness
and judged its hand-run negative suite an accepted limitation rather than a blocker. That
judgement stands for the checks that compare two artefacts. The self-test is not a
substitute for it; it is the one part of the script where hand-verification was genuinely
insufficient, because the thing being verified is what the script is able to *see*.

### Amended 2026-09-08 (EOP-229) — scope widens from "every pinned container" to "every compose image", and the audit becomes a policy

EOP-229 asked whether `e2e/` enters supply-chain and container-pin scope. Its CVE half was
already settled by [ADR-073](ADR-073-supply-chain-coverage-for-actions-and-browsers.md)
Decision 3, which exact-pinned `e2e/`'s three devDependencies and accepted non-scanning with
a named retiring condition; nothing here revisits it. Its container half was open, and
answering it surfaced a defect in *this* ADR's design rather than a gap in `e2e/`.

**The finding: the scope sentence above is not the scope the script had.** "Every
digest-pinned container in the repository" is *circular* — it defines its own subject, so it
stays true however few images are pinned, and would have stayed true at zero. In practice six
were audited and three were invisible, which is the same defect at a less embarrassing scale:
the discovery pass skips any tracked file not already containing a digest. Three compose
files therefore were not read by this script in any sense — `compose.app.yml`,
`compose.e2e.yml` and `docker-compose.yml` — and the roster check cannot see an image that
was **never** pinned, only one that *stopped* being pinned. Four references were floating:

- `postgres:17-alpine`, in `compose.app.yml` **and** `compose.e2e.yml`
- `influxdb:1.8`, in `docker-compose.yml`
- `grafana/grafana:latest`, in `docker-compose.yml`

The first is the one that matters. `compose.app.yml` is the **deployed** stack, so the only
unpinned image in a stack that reaches a user was the database — while three CI-only
containers were audited down to their platform lists and attestation counts. That inversion
is why this story widened the scope rather than treating the `e2e/` gap it was raised for as
the whole finding. It also means the container-pin half of EOP-229 was never a recorded
deferral: "Scope deliberately not taken" above defers the Actions layer, the Maven layer and
image CVE scanning, and unpinned compose images are in none of those three.

**All four are now pinned by digest**, each with a comment above the `image:` line, and each
with a `containers` entry: postgres at digest `sha256:18cfe3ef…` (OCI index, eight
platforms, eight attestations), influxdb at `sha256:299ebda2…` (OCI index, three platforms,
three attestations), grafana at `sha256:b0ae311a…` (Docker manifest list, three platforms,
no attestations). Every unpinned reference except the two in `docker-compose.yml` was written
`${VAR:-default}`, so the digest was pinned **on the default** and the operator override is
untouched.

**Grafana's tag changed, and that is the substance of that pin rather than the digest.** The
image `latest` resolved to matches no released version tag: comparing amd64 child digests
against `12.4.0`, `12.3.2`, `12.3.1`, `12.3.0`, `12.3` and `11.6.0` found no match, and the
image carries no version label — only `maintainer` and `org.opencontainers.image.source`. The
monitoring stack was running a Grafana nobody could name. Freezing that digest beside
`:latest` was not an option regardless, because check 3 rejects exactly that shape; so the
reference moved to the current stable `12.4.0`.

That move was verified behaviourally, and it is worth stating exactly how far the verification
goes. `docker compose up -d` pulled and started both services on their pinned digests,
`/api/health` reported `"version": "12.4.0"` with `"database": "ok"`, and the provisioning log
showed both datasources inserted (`InfluxDB`, `Loki`), `finished to provision dashboards` with
no error, and the search index built over `size=2` — the two dashboards in
`tools/monitoring/grafana/dashboards/`. So the container runs and Grafana 12.4.0 accepts this
repository's provisioning files. What was **not** done is rendering a dashboard in a browser:
the API rejected the configured admin credentials with a 401, because
`GF_SECURITY_ADMIN_PASSWORD` only takes effect when `grafana_data` is initialised and that
volume predates the current value. That is a pre-existing local-volume condition, unrelated to
the pin and unchanged by it, but it means the evidence here is "provisioning is accepted", not
"a human saw a panel draw".

**New check 3b: every compose `image:` line is digest-pinned or declared with a reason.**
This is a **policy**, not a tripwire, and it is the first check in this script that decides a
reference is wrong from the reference itself, consulting the baseline only for exemptions. It
is deliberately the same instrument as `audit-actions.sh`'s form check under ADR-073 — which
fails a bare `uses:` tag on its first commit rather than merely recording it — because the
argument transfers exactly: a tripwire tells you a declared thing moved, and neither can tell
you an undeclared thing exists. It runs at the hermetic/network seam, before the registry
round trips, so a policy violation fails in a second rather than in two minutes.

**Exemptions are declared, not pattern-matched.** A new top-level `unpinned_allowlist` key
carries an entry per exempt image with a mandatory non-empty `reason` and an `occurrences`
list compared for exact equality in both directions. Two entries exist, both images built
from this repository — `eop-threat-modeling` and `eop-ui` — for which no registry digest
exists at all; their supply-chain question is answered upstream by `pom.xml`, `ui/package-lock.json`
and the base images in the two Dockerfiles. A rule that quietly exempted anything without a
registry host in its name would have covered the same two images today and read as a
guarantee it was not making. Bidirectionality is what stops the allow-list rotting: an entry
whose image is no longer used unpinned fails, so pinning one later forces its deletion, and a
*new* unpinned use of an already-exempt image surfaces as an occurrences mismatch instead of
inheriting the exemption in silence. An **absent** `unpinned_allowlist` key exits 2 rather
than treating the exemption set as empty, because a baseline predating the policy must not
pass by default.

**A reference with no default — `${VAR}` or `${VAR:?msg}` — is a finding, not a skip.** Such a
line has no auditable value, so it requires an allow-list entry with a reason. None exists
today; the rule is preventive, and it keeps the policy total: every `image:` line in the
repository is either digest-pinned or explicitly declared.

**The 2026-09-04 amendment's claim that the parser's silent-failure path was closed was
premature.** `REF_CASES` closed it for the forms then in use, all of them plain. A digest
inside `${VAR:-default}` is a *different* misparse, because the `(?<![\w./@-])` lookbehind
excludes `-` and `${VAR:-` ends in one, so a match cannot begin at the image name. Two
readings result, and only one is the survivable kind that amendment described:
`${POSTGRES_IMAGE:-postgres:17-alpine@…}` yields the **wrong name** `17-alpine`, which fails
roster drift loudly; but a *tagless* reference in that form —
`${SCANNER_IMAGE:-sonarsource/sonar-scanner-cli@…}` — has no later colon to anchor on and
yields **no match at all**. That is a total silent miss, the exact failure the earlier
amendment identified as the only one worth engineering against. It was latent rather than
live, since no existing pin used the substituted form; the four references this story pins
all do, so pinning them without this fix would have activated it.

Python's `re` forbids the direct fix — a variable-width lookbehind alternative — so the blob
is **normalised** before the regex runs: `${VAR:-default}` is rewritten to `default` by
`expand_defaults()`, and the self-test now asserts through that normalisation rather than
against the bare pattern, with four cases added covering both misparse readings and the
no-default form. The same function is what check 3b needs, so the two share one
implementation and cannot drift apart. Check 3b carries **two** self-test tables of its own,
for the same reason and asserted before any file is opened: nine cases for `image:` line
extraction, including three near misses that must not match (`imagePullPolicy:`, a
commented-out `image:`, and `build:`), and six for image-name splitting, which restates
EOP-159's `host:port/path` trap because that parser splits on `:` independently of `REF`.
Lower-bound floors on compose files and `image:` lines found back the self-tests up, in the
`MINIMUM_SEQUENCE_DIAGRAMS` idiom, so a discovery rule that matches nothing cannot pass
green. They are lower bounds rather than exact counts on purpose: adding a stack or a service
must not turn the audit red.

**What is still uncovered, corrected.** The Actions layer is no longer the gap the header used
to name — ADR-073 closed it. The Maven plugin layer remains the largest genuinely uncovered
surface in the repository, unchanged and still needing its own decision.

**How check 3b was verified, and the limitation this amendment carries forward.** Nine mutation
cases were run by hand against the working tree, each reverted before the next, and each was
confirmed to fail with the message intended rather than merely to fail. Three exercise the new
policy positively: a brand-new floating image (`redis:7-alpine` added as a scratch service) is
reported at its file and line — the case that was **invisible** to this script before EOP-229 —
while deleting an `unpinned_allowlist` entry surfaces both of its sites, and adding an entry for
an image that *is* pinned fails in the reverse direction with an instruction to delete it. Two
exercise the exemption's own integrity: an empty `reason` fails, and removing the
`unpinned_allowlist` key altogether exits 2 rather than passing over an absent policy. Three
exercise the parsers rather than the data, by breaking the code and confirming the self-test
tables catch it: dropping the quote-stripping in `effective_ref`, reverting the
`expand_defaults()` normalisation in the discovery pass (which reproduced the total silent miss
described above, and named it), and removing the `"/" not in tail` guard from `image_name`
(which reproduced EOP-159's `host:port/path` trap). The ninth confirms the pre-existing roster
check still owns unpinning an image that *was* pinned, so 3b and check 1 do not overlap.

That is the same instrument ADR-064 originally used — sixteen hand-run negative cases — and it
carries the same limitation, now with more at stake: an automated harness in the style of
`test-audit-plugins.sh` and `test-scan-dependencies.sh` still does not exist, so nothing
re-runs those nine cases when this script is next edited. The case for building one is stronger
after this amendment than before it, because a policy check can be weakened into permanence in a
way a tripwire cannot: a tripwire that stops firing leaves an orphaned baseline entry behind,
whereas a policy check whose discovery regex stops matching a compose file simply reports fewer
image lines and passes. The `MINIMUM_COMPOSE_FILES` and `MINIMUM_IMAGE_LINES` floors are the
guard against exactly that, and they are lower bounds rather than a substitute for the harness.
Building it was judged out of scope here rather than unnecessary; it wants its own ticket, as the
EOP-147 CHANGELOG entry already says of this whole class of scripts.

**What the review round changed, and why the half-pin mattered more than the pin.** Three findings
from EOP-229's own Definition-of-Done gates were remediated before merge rather than deferred, and
one of them was a rejection.

The rejection was the important one, because the story had pinned the two Compose references and
stopped there. The Liquibase migration tests start their own PostgreSQL through Testcontainers, and
`PostgresTestContainer.java` still held the bare tag `postgres:17-alpine` while its javadoc claimed
parity with `compose.app.yml`. Before this story that claim was true; pinning the Compose side made
it false, and false in the quiet direction — `17-alpine` is rebuilt upstream, so the suite would have
gone on verifying the changelog against whatever the registry served that week while the stack ran a
fixed image, with nothing anywhere reporting the divergence. That is exactly the gap EOP-164 exists
to close, reintroduced in smaller form *by* a pin that only went half way, which is worth recording
as a general hazard: pinning one of several references to the same image is not a partial
improvement, it is a new and silent inconsistency. The constant is now pinned to the same digest,
that file is declared in the baseline's `occurrences` for `postgres` (which is why a `.java` file
appears there), and `PostgresImagePinTest` fails `./mvnw verify` if the three references and the
baseline entry ever disagree — the `PlaywrightImagePinTest` pattern applied to a second coupling.

Testcontainers refuses a digest-pinned reference outright, which is a sound refusal and worth
knowing before anyone repeats this: a digest is opaque, so it cannot infer that the image behind
`postgres@sha256:…` speaks PostgreSQL, and it asks the caller to assert compatibility rather than
guessing. `asCompatibleSubstituteFor("postgres")` supplies that assertion, and it is only honest
because the new build gate and this script between them hold the digest equal to the reviewed one
and confirm it still resolves.

The security review's finding was that `unpinned_allowlist` was weaker than it looked. Its only
integrity constraints were a non-empty free-text `reason` and a bidirectional `occurrences` match,
so an attacker with commit access had a cheaper route than unpinning an image outright: add a
third-party image to a compose file, add a sentence, pass. The exemption's premise — "this image is
built from this repository, so no registry digest exists to pin" — is a claim prose can only assert,
so it is now a mandatory `built_from` field naming a tracked file, and the audit fails if it does
not. That converts the premise from readable to *checkable*: a third-party image has no Dockerfile
here to name, so smuggling one in now takes committing a Dockerfile that builds it, which is a
visible act rather than a plausible sentence. It is deliberately not an expiry date, which the same
review also suggested — an image built from this repository will never acquire a registry digest, so
an expiry could only ever be re-dated, and ADR-050's own allowlist comment names an unfireable entry
rotting into a claim nobody checks as the failure to avoid.

The code review's finding was that check 3b's parser had two holes, both closed. An `image:` line
whose value resolved to nothing — `${VAR:-}` or `""` — returned `None` and was dropped from the
policy entirely, which is the one outcome a *total* policy cannot allow: every image line must end
up either pinned or declared, and "unreadable" has to fall on the reportable side of that line
rather than the exempt side. And an unresolved `${VAR:?msg}` was split on its last colon and
reported as `${APP_IMAGE`, naming something that appears in no file. Both now have self-test cases,
as do single-quoted references and `localhost:5000/img`.

`PostgresImagePinTest` was itself checked against three mutations rather than assumed: bumping the
digest in the test constant alone fails two of its three tests naming all three references and their
differing digests; reverting the constant to the bare tag reproduces the rejected state and fails;
and the `built_from` check was exercised by adding a floating `evil/image:latest` to a compose file
with an exemption claiming an untracked `Dockerfile.evil`, which fails with the reachability argument
rather than passing on the sentence.

## Related

- [ADR-073](ADR-073-supply-chain-coverage-for-actions-and-browsers.md) — closed the Actions
  gap this ADR deferred, settled EOP-229's CVE half, and supplied the form-check model for
  check 3b
- [ADR-055](ADR-055-k6-performance-check-in-ci.md) — introduced the k6 pin and recorded the
  two traps this script enforces
- [ADR-050](ADR-050-dependency-cve-scanning.md) — the Trivy CVE gate over shipped
  dependencies, whose scope this decision deliberately leaves alone
- [ADR-063](ADR-063-sonarqube-frontend-project.md) — introduced the `scannerImage` mirrors
  whose divergence check 2 closes
- [ADR-016](ADR-016-local-container-runtime.md) — Colima as the local container runtime
- `tools/supply-chain/audit-plugins.sh` — the npm-plugin audit this one is modelled on and
  deliberately differs from
