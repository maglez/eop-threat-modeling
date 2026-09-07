#!/usr/bin/env bash
#
# CVE scan of the dependency trees this application actually ships.
#
# Why this exists: maven-enforcer-plugin already covers dependency convergence and banned
# coordinates, which is supply-chain hygiene, and tools/supply-chain/audit-plugins.sh covers
# the OpenCode plugin roster on a developer's machine. Neither is CVE detection against the
# application's own tree. Before EOP-89 a transitive dependency with a published critical CVE
# passed every gate this project had. It is not hypothetical: the first run of this script
# against main reported six findings, one HIGH, all of them in code that ships.
#
# What it scans, and what it deliberately does not:
#   pom.xml               -- the Java tree, resolved transitively. Trivy's pom analyzer walks
#                            the real graph, not just declared dependencies: the log4j-api
#                            finding above sat at depth 3, via Boot's logging starter.
#   ui/package-lock.json  -- the front-end tree. The lockfile, not package.json, because every
#                            version in package.json is a caret range and the lockfile is the
#                            only reproducible statement of what is installed.
#   .opencode/, tools/    -- SKIPPED. Developer tooling is audit-plugins.sh's subject, and it
#                            checks more there than CVEs alone (maintainer handoffs, SLSA
#                            provenance, registry signatures). Two scanners gating one tree
#                            would mean two allowlists to keep in step, and a reachability
#                            trace through a developer tool is not a trace through production.
#   target/               -- SKIPPED. Scanning the built fat jar instead of the manifest was
#                            tried under EOP-89 and does not work: `trivy fs` on the jar logs
#                            "Number of language-specific files num=0" and finds nothing, with
#                            or without --java-db-repository. Recorded so it is not retried
#                            hopefully. The cost is that this scans the resolved tree rather
#                            than the shipped set, so it does not honour ADR-047's exclusion
#                            of H2 from the artifact -- an H2 CVE would be gated here even
#                            though H2 does not ship. Failing loud on a dependency we do not
#                            ship is the safe direction of that error.
#   e2e/                  -- SKIPPED, and the decision is EOP-236's, not an oversight. Trivy
#                            detects e2e/package-lock.json and suppresses it today only
#                            because the gating pass omits dev dependencies; this entry makes
#                            the omission a recorded choice rather than a side effect. The
#                            tree is @playwright/test, typescript and @types/node -- three
#                            devDependencies, exact-pinned by EOP-236, none of which is
#                            compiled into the jar or bundled into ui/dist, so a finding
#                            there is NOT a shipped vulnerability and must never be read as
#                            a release blocker. It is the same call the supply-chain job's
#                            preamble in .github/workflows/ci.yml already made for the
#                            OpenCode plugin roster: an advisory against a developer tool is
#                            a thing to know about, not a reason to block a merge of
#                            unrelated application code. The bound that makes it safe is the
#                            e2e job's, not this script's -- contents: read and nothing
#                            else, no repository secrets to steal, and nothing published
#                            from a non-push event.
#                            RETIRING CONDITION, not an expiry date: bring e2e/ into a scan
#                            when either the repository gains a secret the e2e job can
#                            reach, or e2e/ gains a dependency that executes against
#                            production data or credentials. A date is the wrong instrument
#                            here -- it would force this paragraph to be re-argued on a
#                            calendar while the two things that actually change the
#                            calculus went unwatched. Whoever adds that secret owns
#                            revisiting this.
#
# Two passes, and only one of them gates. Trivy suppresses development and test dependencies
# by default, so the gating pass sees what reaches production -- Maven compile and runtime
# scope, npm dependencies. The second pass adds --include-dev-deps and is INFORMATIONAL: a
# vulnerability in vitest or in a test-scope artifact cannot be reached by a deployed request,
# and gating on ~330 front-end devDependencies would put this job at the mercy of the release
# cadence of the entire JavaScript tooling ecosystem. It is printed because it is worth
# knowing, and it is not a reason to block a merge.
#
# Severity policy: HIGH and CRITICAL gate, MEDIUM and LOW are printed. This is the same
# threshold audit-plugins.sh uses, and it is deliberately looser than the high/MEDIUM posture
# build-quality.md sets for SpotBugs. The asymmetry is defended in ADR-050: a SpotBugs medium
# is in our own code and is always actionable, whereas a medium CVE in somebody else's
# transitive dependency is frequently unreachable, often has no in-range fix, and arrives on
# a schedule nobody here controls.
#
# The gate parses --json rather than using `trivy --exit-code 1 --severity HIGH,CRITICAL`, for
# the same reason audit-plugins.sh parses `npm audit --json` rather than using --audit-level:
# a severity threshold can only threshold. It cannot express "this specific advisory is
# unreachable in this repository, here is the trace, and here is when that claim expires".
#
# Usage:  tools/supply-chain/scan-dependencies.sh
# Exit:   0 = clean, 1 = a gating finding or allowlist drift, 2 = could not run.
#         That contract holds on every reachable path, and the informational pass cannot
#         contribute to it at all: it can only warn, never change the code. Guarding that
#         was EOP-146 -- under `set -e` a malformed report made python3 exit 1 from the
#         informational block, which reads identically to a CVE, and the final
#         `exit "$gate_status"` was never reached. The shell-level cases in
#         test-scan-dependencies.sh hold this.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

allowlist="tools/supply-chain/accepted-cves.json"
# Inside the worktree on purpose: .tmp/ is gitignored and needs no external_directory
# grant, unlike /tmp. See AGENTS.md.
workdir=".tmp/dependency-cve"

command -v trivy >/dev/null 2>&1 || {
    echo "FATAL: trivy is not on PATH. Install it with 'brew install trivy'."
    exit 2
}
command -v python3 >/dev/null 2>&1 || { echo "FATAL: python3 is not on PATH"; exit 2; }
[[ -f "$allowlist" ]] || { echo "FATAL: $allowlist not found"; exit 2; }
[[ -f pom.xml ]] || { echo "FATAL: pom.xml not found -- wrong working directory?"; exit 2; }
[[ -f ui/package-lock.json ]] || {
    echo "FATAL: ui/package-lock.json not found. The front-end tree is only reproducible"
    echo "       through its lockfile; scanning package.json's caret ranges would report"
    echo "       findings against versions nobody has installed. Run 'npm install' in ui/."
    exit 2
}

# Guarded because a permissions failure here is could-not-run, not a clean tree. Under
# `set -e` an unguarded failure exits with the shell's own code -- typically 1, which this
# script's contract reserves for a gating finding -- so an unwritable workspace would read
# as a CVE. audit-plugins.sh and audit-containers.sh carry the same guards for the same
# reason; the three were fixed together rather than one at a time (EOP-146).
rm -rf "$workdir" || { echo "FATAL: could not remove $workdir"; exit 2; }
mkdir -p "$workdir" || { echo "FATAL: could not create $workdir"; exit 2; }

# Skipped directories are argued for in the header. Keep the two invocations below identical
# apart from --include-dev-deps, so that any difference in their output is attributable to
# that flag alone.
run_trivy() {
    local out="$1"
    shift
    trivy fs \
        --scanners vuln \
        --format json \
        --output "$out" \
        --skip-dirs target \
        --skip-dirs '**/node_modules' \
        --skip-dirs .tmp \
        --skip-dirs .opencode \
        --skip-dirs tools \
        "$@" \
        . 2>&1 | sed 's/^/  trivy: /'
}

echo "=== Trivy version ==="
trivy --version | head -1

echo
echo "=== Pass 1 of 2: shipped dependencies (this pass gates) ==="
if ! run_trivy "$workdir/ships.json"; then
    echo "FATAL: trivy failed, so nothing above is a result. Two network causes account for"
    echo "       almost every instance, and neither is a pass:"
    echo "         - the vulnerability database could not be fetched, so there was nothing"
    echo "           to scan against;"
    echo "         - Maven Central refused to serve a parent POM or BOM. Trivy resolves the"
    echo "           Maven tree with its own parser, which keeps no cache and re-fetches the"
    echo "           whole set every run, so a shared CI IP address can earn a 429 with a"
    echo "           long Retry-After. Populate the local Maven repository first"
    echo "           (./mvnw -B dependency:resolve) and Trivy reads ~/.m2/repository"
    echo "           instead of the network. Do NOT reach for --offline-scan: an"
    echo "           unresolvable parent POM would silently shrink the tree trivy believes"
    echo "           exists, turning an under-report into a clean bill of health."
    echo "       Either way this is exit 2, not a pass."
    exit 2
fi

echo
echo "=== Pass 2 of 2: including build-time dependencies (informational only) ==="
# This pass never gates, so its failure must not pre-empt the gating verdict below. Trivy
# keeps no cache and re-resolves the whole Maven tree on every invocation, so a second fetch
# can earn a 429 the first did not; exiting 2 here would let that transient redden the job
# over findings that are explicitly not merge blockers (EOP-146).
informational_ok=1
if ! run_trivy "$workdir/all.json" --include-dev-deps; then
    echo "WARNING: trivy failed on the informational pass. The gating verdict below still"
    echo "         stands -- it is computed from pass 1 alone -- but build-time findings"
    echo "         are unknown for this run."
    informational_ok=0
fi

echo
echo "=== Findings in shipped dependencies ==="
gate_status=0
python3 - "$workdir/ships.json" "$allowlist" <<'PY' || gate_status=$?
import datetime
import json
import sys

report_path, allowlist_path = sys.argv[1], sys.argv[2]

GATING = {"HIGH", "CRITICAL"}
REQUIRED_FIELDS = [
    "module", "severity", "installed", "fixed_version", "title", "introduced_by",
    "reachability", "no_fix_available", "reviewed", "reviewed_under", "expires",
]

report = json.load(open(report_path))
allowed = json.load(open(allowlist_path))["advisories"]

# Trivy omits "Vulnerabilities" entirely on a clean target rather than emitting an empty
# list, so a plain .get() with a default is load-bearing and not defensive noise. The key
# can also be present and explicitly null.
found = []
targets = []
for result in report.get("Results") or []:
    targets.append("%s (%s)" % (result.get("Target"), result.get("Type")))
    for vuln in result.get("Vulnerabilities") or []:
        found.append({
            "id": vuln.get("VulnerabilityID", "?"),
            "module": vuln.get("PkgName", "?"),
            "severity": (vuln.get("Severity") or "UNKNOWN").upper(),
            "installed": vuln.get("InstalledVersion", ""),
            "fixed": vuln.get("FixedVersion", ""),
            "title": (vuln.get("Title") or "").strip(),
            "target": result.get("Target", "?"),
            "url": vuln.get("PrimaryURL", ""),
        })

if not targets:
    sys.exit(
        "FATAL: trivy analysed no dependency manifests at all. It should have found at\n"
        "least pom.xml and ui/package-lock.json. A scan with no targets reports no\n"
        "findings, which is indistinguishable from a clean tree and must never be\n"
        "mistaken for one -- check the --skip-dirs list."
    )

print("Manifests analysed:")
for t in targets:
    print("  - %s" % t)
print()

failures, notes = [], []
today = datetime.date.today()

gating_found = [f for f in found if f["severity"] in GATING]
other_found = [f for f in found if f["severity"] not in GATING]

# --- gating severities ------------------------------------------------------
seen_keys = set()
if not gating_found:
    print("No HIGH or CRITICAL findings.")
for f in gating_found:
    key = "%s@%s" % (f["id"], f["module"])
    seen_keys.add(key)
    print("  %-8s %s" % (f["severity"], key))
    print("           installed %s, fixed in %s" % (f["installed"] or "?", f["fixed"] or "no fix published"))
    print("           %s" % (f["title"] or f["url"]))

    entry = allowed.get(key)
    if entry is None:
        failures.append(
            "%s (%s in %s): a %s finding with no entry in %s.\n"
            "    Either move off the dependency or upgrade it -- every finding closed under\n"
            "    EOP-89 was closed by a patch-level version override -- or trace the\n"
            "    vulnerable code path and record why it is unreachable here. Do not add an\n"
            "    entry to turn this red job green." % (
                key, f["installed"] or "?", f["target"], f["severity"], allowlist_path)
        )
        continue

    missing = [k for k in REQUIRED_FIELDS if k not in entry]
    if missing:
        failures.append(
            "%s: allowlist entry is missing required field(s): %s. The shape is not\n"
            "    decoration -- an entry without a reachability trace, a reviewer and an\n"
            "    expiry is a bare suppression wearing the file's clothes."
            % (key, ", ".join(missing))
        )
        continue

    if entry["module"] != f["module"]:
        failures.append(
            "%s: allowlist says module %r, scanner reports %r."
            % (key, entry["module"], f["module"])
        )
    if str(entry["severity"]).upper() != f["severity"]:
        failures.append(
            "%s: allowlist says severity %r, scanner now reports %r. A severity that has\n"
            "    been re-scored is a re-argued advisory: re-read it rather than editing the\n"
            "    field to match." % (key, entry["severity"], f["severity"])
        )

    try:
        expires = datetime.date.fromisoformat(str(entry["expires"]))
    except ValueError:
        failures.append(
            "%s: 'expires' is %r, which is not a YYYY-MM-DD date."
            % (key, entry["expires"])
        )
        continue
    if expires <= today:
        failures.append(
            "%s: the suppression expired on %s. The reachability trace has to be re-argued,\n"
            "    not re-dated. If it still holds, say so with a fresh 'reviewed' date and a\n"
            "    new horizon; if the dependency can now be upgraded, delete the entry."
            % (key, expires.isoformat())
        )
    else:
        notes.append(
            "%s: suppressed until %s (reviewed %s under %s)."
            % (key, expires.isoformat(), entry["reviewed"], entry["reviewed_under"])
        )

# --- anti-rot: an entry that no longer fires --------------------------------
for key in sorted(set(allowed) - seen_keys):
    failures.append(
        "%s: allowlisted but no longer reported by the scan. DELETE the entry.\n"
        "    Leaving it is not harmless: it is an unverifiable claim about a code path that\n"
        "    may no longer exist, and it will silently suppress the advisory if it returns\n"
        "    against a future version whose reachability nobody has looked at." % key
    )

# --- non-gating severities --------------------------------------------------
print()
if other_found:
    print("Not gated (%d finding(s)). These are reported, not suppressed: there is no"
          % len(other_found))
    print("allowlist entry to write for one, and writing one is an error the gate rejects.")
    for f in sorted(other_found, key=lambda x: (x["severity"], x["id"])):
        print("  %-8s %s in %s (installed %s, fixed in %s)" % (
            f["severity"], f["id"], f["module"], f["installed"] or "?",
            f["fixed"] or "no fix published"))
else:
    print("No MEDIUM or LOW findings either.")

if notes:
    print()
    print("=== Active suppressions ===")
    for n in notes:
        print("  %s" % n)

if failures:
    print()
    print("=== FAILURES (%d) ===" % len(failures))
    for f in failures:
        print("  - %s" % f)
    sys.exit(1)

print()
print("Shipped dependency trees: clean, or fully accounted for.")
PY

echo
echo "=== Build-time dependencies (informational, never gates) ==="
if [[ "$informational_ok" -eq 0 ]]; then
    echo "Skipped: the informational pass produced no report. See the WARNING above."
    exit "$gate_status"
fi

# `|| true` on purpose. This block is informational, so a malformed report or any unhandled
# exception in it must not pre-empt $gate_status: under `set -e` the script would exit here
# with python3's own code -- 1, which the contract reserves for a gating finding -- and the
# final `exit "$gate_status"` would never run. A gating finding must never be reported by
# the same number as a crash in a block that cannot gate (EOP-146).
python3 - "$workdir/all.json" "$workdir/ships.json" <<'PY' || true
import json
import sys


def findings(path):
    out = {}
    for result in json.load(open(path)).get("Results") or []:
        for v in result.get("Vulnerabilities") or []:
            out["%s@%s" % (v.get("VulnerabilityID"), v.get("PkgName"))] = (
                (v.get("Severity") or "UNKNOWN").upper(),
                v.get("InstalledVersion", ""),
                v.get("FixedVersion", ""),
            )
    return out


everything = findings(sys.argv[1])
shipped = findings(sys.argv[2])
build_only = {k: v for k, v in everything.items() if k not in shipped}

if not build_only:
    print("No additional findings in development or test scope.")
    sys.exit(0)

order = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "UNKNOWN": 4}
print("%d finding(s) reachable only from build-time dependencies. Worth fixing when a"
      % len(build_only))
print("fix is available; not a merge blocker, because no deployed request can reach them.")
for key in sorted(build_only, key=lambda k: (order.get(build_only[k][0], 9), k)):
    severity, installed, fixed = build_only[key]
    print("  %-8s %s (installed %s, fixed in %s)"
          % (severity, key, installed or "?", fixed or "no fix published"))
PY

exit "$gate_status"
