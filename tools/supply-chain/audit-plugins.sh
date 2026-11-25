#!/usr/bin/env bash
#
# Supply-chain audit for the OpenCode plugin roster.
#
# Why this exists: SETUP.md used to carry a prose instruction to re-run `npm audit`
# "whenever a pin moves". Over four pin movements it was honoured once, and only
# because a human asked -- one of those movements was a commit that edited the very
# paragraph recording the trigger as unhonoured, without honouring it. Prose does not
# run. This script does.
#
# What it checks, in order of how much it would actually tell you:
#   1. Roster drift    -- every spec in opencode.json has a baseline entry, and vice
#                         versa. Without this, a newly added plugin would skip every
#                         check below and the audit would pass while covering less.
#   2. Maintainers     -- an npm account handoff is the loudest single compromise
#                         signal there is; it preceded most well-known takeovers.
#   3. Provenance      -- whether the pinned version carries an SLSA attestation.
#                         Absence is tolerated (half the roster lacks it); CHANGE is
#                         not, in either direction, because the pins are exact and a
#                         published version's attestation is immutable.
#   4. Advisories      -- every high/critical `npm audit` finding must either be new
#                         (fail) or carry a reachability trace in
#                         tools/supply-chain/accepted-advisories.json. An allowlisted
#                         advisory that is no longer reported ALSO fails, so the list
#                         cannot outlive what it suppresses. Low and moderate findings
#                         are printed and not gated. Plus registry signature
#                         verification over the whole transitive tree.
#
# What it does NOT check, and must never be described as checking: whether a release
# has been backdoored. Advisories answer "is there a published CVE", signatures answer
# "did this tarball come from the registry unmodified", provenance answers "was it
# built by the named repository's CI". None of them answers "is the code benign", and
# that is the failure mode that matters for plugins running unsandboxed inside
# OpenCode with sight of every message and file.
#
# Usage:  tools/supply-chain/audit-plugins.sh
# Exit:   0 = clean, 1 = drift or a high/critical advisory, 2 = could not run.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

config=".opencode/opencode.json"
baseline="tools/supply-chain/expected-plugins.json"
allowlist="tools/supply-chain/accepted-advisories.json"
# Inside the worktree on purpose: .tmp/ is gitignored and needs no external_directory
# grant, unlike /tmp. See AGENTS.md.
workdir=".tmp/supply-chain"

command -v npm >/dev/null 2>&1 || { echo "FATAL: npm is not on PATH"; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "FATAL: python3 is not on PATH"; exit 2; }
[[ -f "$config" ]] || { echo "FATAL: $config not found"; exit 2; }
[[ -f "$baseline" ]] || { echo "FATAL: $baseline not found"; exit 2; }
[[ -f "$allowlist" ]] || { echo "FATAL: $allowlist not found"; exit 2; }

# Guarded because a permissions failure here is could-not-run, not a clean tree. Under
# `set -e` an unguarded failure exits with the shell's own code -- typically 1, which this
# script's contract reserves for drift or a gating advisory -- so an unwritable workspace
# would read as a finding. scan-dependencies.sh and audit-containers.sh carry the same
# guards for the same reason; the three were fixed together (EOP-146).
rm -rf "$workdir" || { echo "FATAL: could not remove $workdir"; exit 2; }
mkdir -p "$workdir" || { echo "FATAL: could not create $workdir"; exit 2; }

echo "=== Plugin specs declared in $config ==="
python3 - "$config" "$workdir/specs.txt" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
# A plugin entry is either "name@version" or ["name@version", {options}].
raw = cfg.get("plugins", cfg.get("plugin", []))
specs = []
for entry in raw:
    if isinstance(entry, str):
        spec = entry
    elif isinstance(entry, dict):
        spec = entry.get("package") or entry.get("path")
    else:
        spec = None
    if not spec:
        continue
    # Local plugins (paths, not npm specs) are out of scope for this audit.
    if spec.startswith((".", "/", "file:")):
        continue
    specs.append(spec)
if not specs:
    sys.exit("FATAL: no plugin specs found -- has the config shape changed?")
for s in specs:
    if "@" not in s.lstrip("@"):
        sys.exit(f"FATAL: plugin spec is not version-locked: {s!r}. "
                 "An unpinned spec resolves to whatever npm 'latest' is at load time, "
                 "which is exactly what this audit exists to prevent.")
    print(s)
open(sys.argv[2], "w").write("\n".join(specs) + "\n")
PY

echo
echo "=== Registry metadata for each pinned spec ==="
# One `npm view` per spec, cached to disk so the comparison step is pure.
while read -r spec; do
    [[ -z "$spec" ]] && continue
    safe="${spec//\//_}"
    if ! npm view "$spec" --json > "$workdir/view-$safe.json" 2>"$workdir/view-$safe.err"; then
        echo "FATAL: npm view failed for $spec"
        cat "$workdir/view-$safe.err"
        exit 2
    fi
    echo "  fetched $spec"
done < "$workdir/specs.txt"

python3 - "$baseline" "$workdir" <<'PY'
import json, os, sys

baseline_path, workdir = sys.argv[1], sys.argv[2]
expected = json.load(open(baseline_path))["plugins"]
specs = [l for l in open(os.path.join(workdir, "specs.txt")).read().split("\n") if l]

failures, notes = [], []

# --- 1. roster drift -------------------------------------------------------
declared = {}
for spec in specs:
    at = spec.rindex("@")
    declared[spec[:at]] = spec[at + 1:]

for name in sorted(set(declared) - set(expected)):
    failures.append(
        f"{name}: declared in opencode.json but absent from the baseline. A plugin "
        f"with no baseline entry is a plugin nothing checks -- add it to "
        f"{baseline_path} in the same commit that adds the pin."
    )
for name in sorted(set(expected) - set(declared)):
    failures.append(
        f"{name}: present in the baseline but no longer declared in opencode.json. "
        f"If the plugin was removed deliberately, remove its baseline entry too."
    )

# --- 2 & 3. maintainers and provenance ------------------------------------
for name in sorted(set(declared) & set(expected)):
    version = declared[name]
    view = json.load(open(os.path.join(workdir, f"view-{(name + '@' + version).replace('/', '_')}.json")))
    exp = expected[name]

    actual_maintainers = sorted(
        m if isinstance(m, str) else m.get("name", "?") for m in view.get("maintainers", [])
    )
    if actual_maintainers != sorted(exp["maintainers"]):
        failures.append(
            f"{name}: MAINTAINER CHANGE.\n"
            f"      expected: {sorted(exp['maintainers'])}\n"
            f"      registry: {actual_maintainers}\n"
            f"      This is the strongest compromise signal npm offers. Do not update "
            f"the baseline to clear it until a human has established that the handoff "
            f"was legitimate."
        )

    has_provenance = bool(view.get("dist", {}).get("attestations"))
    if has_provenance != exp["provenance"]:
        direction = "GAINED" if has_provenance else "LOST"
        failures.append(
            f"{name}@{version}: provenance {direction} relative to the baseline "
            f"(expected {exp['provenance']}, registry {has_provenance}). The pin is an "
            f"exact version and a published version's attestation is immutable, so "
            f"either direction means the version was republished. Investigate before "
            f"updating the baseline."
        )
    elif not has_provenance:
        notes.append(f"{name}@{version}: no SLSA provenance attestation (known, accepted)")

print("\n=== Maintainer and provenance comparison ===")
for n in notes:
    print(f"  note: {n}")
attested = sum(1 for n in declared if expected.get(n, {}).get("provenance"))
print(f"  {attested} of {len(declared)} pinned plugins carry a provenance attestation")

if failures:
    print("\n=== DRIFT DETECTED ===")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("  no drift against the baseline")
PY

echo
echo "=== Advisory and signature audit over the pinned tree ==="
# A throwaway manifest, deliberately NOT .opencode/package.json -- that file declares
# only the @opencode-ai/plugin typings used to author .opencode/plugins/graphify.js,
# so auditing it would audit the authoring toolchain and not the plugins that load.
python3 - "$workdir/specs.txt" "$workdir/package.json" <<'PY'
import json, sys
deps = {}
for spec in open(sys.argv[1]).read().split():
    at = spec.rindex("@")
    deps[spec[:at]] = spec[at + 1:]
json.dump(
    {
        "name": "eop-plugin-supply-chain-audit",
        "version": "0.0.0",
        "private": True,
        "description": "Generated by tools/supply-chain/audit-plugins.sh. Not a real package.",
        "dependencies": deps,
    },
    open(sys.argv[2], "w"),
    indent=2,
)
PY

# --ignore-scripts mirrors how OpenCode itself installs plugins: Npm.add() drives
# @npmcli/arborist in-process with ignoreScripts: true, so no lifecycle script runs
# on the real install path either. Verified against anomalyco/opencode at v1.18.19.
( cd "$workdir" && npm install --ignore-scripts --no-fund --no-audit --loglevel=error )

audit_status=0
# --json rather than the human-readable table because the gate is per-advisory now:
# npm's own --audit-level only thresholds an exit code, and cannot express "this
# specific GHSA is unreachable here". npm exits non-zero whenever anything is found,
# so its status is not the signal -- the parse below is.
( cd "$workdir" && npm audit --json > audit.json ) || audit_status=$?
if [[ ! -s "$workdir/audit.json" ]]; then
    echo "FATAL: npm audit produced no JSON (exit $audit_status). Network problem?"
    exit 2
fi

audit_status=0
python3 - "$workdir/audit.json" "$allowlist" <<'PY' || audit_status=$?
import json, sys

report = json.load(open(sys.argv[1]))
allowlist_path = sys.argv[2]
accepted = json.load(open(allowlist_path))["advisories"]

# Guard: a successful npm audit always emits a top-level "metadata" key with a
# "vulnerabilities" sub-key. Its absence means the advisory endpoint was unreachable
# (503, DNS failure, etc.) and npm wrote an error document instead of an audit report.
# A top-level "error" key is the other canonical shape for that case.
# Either way this is could-not-run (exit 2), not a clean tree (exit 0).
# Reaching the staleness comparison on an error document would falsely flag every
# allowlist entry as "no longer reported" and recommend deleting live accepted-risk
# documentation -- the exact failure mode this guard prevents.
if "error" in report or "vulnerabilities" not in report.get("metadata", {}):
    err = report.get("error", {})
    code = err.get("code", "unknown")
    detail = err.get("summary", err.get("detail", "no detail in document"))
    print(
        f"FATAL: npm audit returned an error document rather than an audit report.\n"
        f"  error.code:    {code}\n"
        f"  error.detail:  {detail}\n"
        f"  This is a transport or registry failure, not a clean tree.\n"
        f"  Re-run the audit once the advisory endpoint is reachable.\n"
        f"  The allowlist has NOT been checked for staleness -- do not delete entries\n"
        f"  based on this run.",
        file=sys.stderr,
    )
    sys.exit(2)

# npm nests one advisory object per finding under vulnerabilities[pkg].via; entries
# that are plain strings are just "this package is affected because of that one" and
# carry no advisory of their own.
found = {}
for pkg in report.get("vulnerabilities", {}).values():
    for via in pkg.get("via", []):
        if not isinstance(via, dict):
            continue
        ghsa = via.get("url", "").rsplit("/", 1)[-1]
        if ghsa:
            found[ghsa] = via

totals = report.get("metadata", {}).get("vulnerabilities", {})
print(f"  npm totals: " + ", ".join(f"{k}={v}" for k, v in totals.items()))
print(f"  {len(found)} distinct advisories across the pinned tree")

gating = {"high", "critical"}
failures = []

print("\n=== High and critical advisories ===")
blocking = {g: v for g, v in found.items() if v.get("severity") in gating}
if not blocking:
    print("  none")
for ghsa in sorted(blocking):
    via = blocking[ghsa]
    entry = accepted.get(ghsa)
    label = "accepted" if entry else "NOT ACCEPTED"
    print(f"  [{label}] {via['severity']:8} {ghsa}  {via['name']} {via.get('range')}")
    print(f"            {via.get('title')}")
    if not entry:
        failures.append(
            f"{ghsa} ({via['severity']}, {via['name']} {via.get('range')}): "
            f"{via.get('title')}\n"
            f"      No entry in {allowlist_path}. A high or critical finding needs a "
            f"decision, not a baseline update: either move off the dependency, or "
            f"trace the vulnerable code path and record why it is unreachable here. "
            f"Do not add an entry to turn this red job green."
        )
        continue
    # An entry describes one advisory against one module at one severity. If any of
    # that has moved, the recorded reachability trace is no longer known to apply.
    if entry.get("module") != via["name"]:
        failures.append(
            f"{ghsa}: allowlisted against module {entry.get('module')!r} but reported "
            f"against {via['name']!r}. Re-verify the trace before touching the entry."
        )
    if entry.get("severity") != via["severity"]:
        failures.append(
            f"{ghsa}: allowlisted at severity {entry.get('severity')!r}, now reported "
            f"as {via['severity']!r}. A re-scored advisory is a fresh decision -- "
            f"re-read it rather than editing the severity field to match."
        )

# The anti-rot half. A suppression list whose entries are never checked for relevance
# stops being a record of decisions and becomes a place findings go to disappear.
for ghsa in sorted(set(accepted) - set(found)):
    failures.append(
        f"{ghsa}: allowlisted in {allowlist_path} but no longer reported by npm audit. "
        f"Either it was fixed upstream or the dependency is gone -- in both cases "
        f"DELETE the entry. Leaving it in place means the next advisory to reuse that "
        f"reasoning inherits an approval nobody granted."
    )

# Printed, never gated. They are here so that a drift in the shape of the low/moderate
# tail is visible in the job log instead of being summarised away to a count.
print("\n=== Low and moderate advisories (not gated) ===")
tail = {g: v for g, v in found.items() if v.get("severity") not in gating}
if not tail:
    print("  none")
for ghsa in sorted(tail, key=lambda g: (tail[g].get("severity", ""), g)):
    via = tail[ghsa]
    print(f"  {via['severity']:8} {ghsa}  {via['name']} {via.get('range')}")

if failures:
    print("\n=== FAIL: advisory gate ===")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("\n  no unaccepted high/critical advisory, no stale allowlist entry")
PY

echo
echo "=== Registry signature verification ==="
# Needs a real install: with only a lockfile npm reports "found no dependencies to
# audit that were installed from a supported registry".
( cd "$workdir" && npm audit signatures )

if [[ "$audit_status" -eq 2 ]]; then
    echo
    echo "=== COULD NOT RUN: advisory endpoint unreachable ==="
    echo "npm audit returned an error document instead of an audit report."
    echo "This is a transport or registry failure -- the advisory gate has NOT run."
    echo "An unreachable endpoint is never a clean audit; re-run once the endpoint"
    echo "is reachable. The allowlist has NOT been checked for staleness."
    exit 2
elif [[ "$audit_status" -ne 0 ]]; then
    echo
    echo "=== FAIL: the advisory gate above rejected this tree ==="
    echo "Low and moderate findings do not fail this job; SETUP.md records the"
    echo "accepted residual and the reasoning. A high or critical finding needs a"
    echo "decision, and a stale allowlist entry needs deleting."
    exit 1
fi

echo
echo "=== PASS: no roster drift, no maintainer change, no unaccepted high/critical advisory ==="
