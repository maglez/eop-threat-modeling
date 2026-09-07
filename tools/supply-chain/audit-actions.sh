#!/usr/bin/env bash
#
# audit-actions.sh -- hold every third-party GitHub Action reference in this
# repository against tools/supply-chain/expected-actions.json.
#
# WHY THIS EXISTS. `uses: actions/checkout@v4` follows a tag, and a tag is a
# pointer its owner can move onto any commit at any time with no diff for a
# reviewer to read and no version number changing. In March 2025
# tj-actions/changed-files had exactly that done to it and leaked CI runner
# memory into build logs worldwide. A 40-hex commit SHA is the documented
# mitigation; this script is what stops the repository drifting back off it.
# See docs/adr/ADR-073-supply-chain-pin-actions-and-browsers.md.
#
# WHAT IT CHECKS. Six things, all of them able to fail:
#
#   1. Every `uses:` reference in every workflow file is `owner/repo@<40 hex>`.
#      A bare tag, a branch name, a short SHA or a 39-character typo fails.
#      This check does NOT consult the baseline, which is the point: a NEW
#      action added by tag fails on its first commit rather than passing
#      silently because nobody remembered this file existed.
#   2. Every referenced action is declared in the baseline.
#   3. Every referenced action's SHA equals the baseline's SHA, at every site.
#      One action pinned to two different commits fails as well.
#   4. reference_count agrees with the number of sites found.
#   5. occurrences agrees BIDIRECTIONALLY -- a listed file that no longer
#      carries the reference fails, and so does a workflow file carrying a
#      reference that its action's occurrences list does not name.
#   6. Every declared action is actually referenced. A baseline entry for an
#      action nobody invokes is stale, and stale is how a baseline stops
#      being read.
#
# It also rejects a baseline that is missing any mandatory field, so a
# hand-edit cannot drop `occurrences` and thereby turn check 5 off.
#
# WHAT IT DELIBERATELY DOES NOT CHECK. It is NETWORK-FREE. It does not ask
# GitHub whether the tag recorded beside a SHA still points at it, and it does
# not ask whether a newer release exists. Both are deliberate: this runs in the
# `supply-chain` job before Node is even installed and needs to report in
# seconds, and a pin's whole purpose is that the registry's current opinion is
# irrelevant to what executes. The cost is that nothing here enforces an
# upgrade cadence -- ADR-073 accepts that residual and names its retiring
# condition.
#
# POSITIVE CONTROL. This script is only worth running if it can fail, so prove
# that before trusting a green result:
#
#   sed -i.bak 's|actions/checkout@11d5960a326750d5838078e36cf38b85af677262|actions/checkout@v4|' \
#       .github/workflows/ci.yml
#   tools/supply-chain/audit-actions.sh   # must exit 1 naming an unpinned reference
#   mv .github/workflows/ci.yml.bak .github/workflows/ci.yml
#
# Exit codes: 0 clean, 1 findings, 2 could not run (missing file, bad JSON).

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

baseline="tools/supply-chain/expected-actions.json"
workflow_dir=".github/workflows"

if [[ ! -f "$baseline" ]]; then
    echo "FATAL: baseline not found: $baseline" >&2
    exit 2
fi

if [[ ! -d "$workflow_dir" ]]; then
    echo "FATAL: no workflow directory at $workflow_dir" >&2
    exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "FATAL: python3 is required" >&2
    exit 2
fi

echo "=== Pinned GitHub Actions audit ==="
echo "baseline: $baseline"
echo

python3 - "$baseline" "$workflow_dir" <<'PY'
import json
import pathlib
import re
import sys

baseline_path = pathlib.Path(sys.argv[1])
workflow_dir = pathlib.Path(sys.argv[2])

try:
    baseline = json.loads(baseline_path.read_text())
except (OSError, json.JSONDecodeError) as exc:
    print(f"FATAL: cannot read {baseline_path}: {exc}", file=sys.stderr)
    raise SystemExit(2)

declared = baseline.get("actions")
if not isinstance(declared, dict) or not declared:
    print("FATAL: baseline has no non-empty 'actions' object", file=sys.stderr)
    raise SystemExit(2)

MANDATORY = ("sha", "tag", "major", "reference_count", "occurrences", "note")

findings = []


def finding(message):
    findings.append(message)


# --- the baseline itself must be well formed, or later checks are vacuous ----
for action, spec in sorted(declared.items()):
    if not isinstance(spec, dict):
        finding(f"baseline: '{action}' is not an object")
        continue
    for field in MANDATORY:
        if field not in spec:
            finding(f"baseline: '{action}' is missing mandatory field '{field}'")
    surplus = sorted(set(spec) - set(MANDATORY))
    if surplus:
        finding(f"baseline: '{action}' carries unknown field(s) {surplus}")
    sha = spec.get("sha")
    if isinstance(sha, str) and not re.fullmatch(r"[0-9a-f]{40}", sha):
        finding(
            f"baseline: '{action}' sha is not 40 lower-case hex characters: "
            f"{sha!r} ({len(sha)} chars)"
        )
    tag = spec.get("tag")
    if isinstance(tag, str) and not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        finding(
            f"baseline: '{action}' tag is not an exact semver release: {tag!r} "
            "-- a major alias such as 'v4' is what the pin exists to replace"
        )
    if not isinstance(spec.get("occurrences"), list) or not spec.get("occurrences"):
        finding(f"baseline: '{action}' occurrences must be a non-empty list")

if findings:
    for message in findings:
        print(f"FINDING: {message}")
    print()
    print(f"{len(findings)} finding(s) in the baseline -- refusing to audit against it.")
    raise SystemExit(2)

# --- collect every reference from every workflow file -----------------------
# Matches both spellings GitHub accepts:
#     - uses: owner/repo@ref
#       uses: owner/repo@ref
# `#` starts a comment, so the reference ends at whitespace or `#`.
USES = re.compile(r"^\s*(?:-\s+)?uses:\s*(?P<ref>[^\s#]+)")

workflow_files = sorted(
    p for p in workflow_dir.iterdir()
    if p.is_file() and p.suffix in (".yml", ".yaml")
)

if not workflow_files:
    print(f"FATAL: no .yml/.yaml files under {workflow_dir}", file=sys.stderr)
    raise SystemExit(2)

# action -> list of (file, line, sha)
found = {}
site_count = 0
skipped = []

for path in workflow_files:
    for lineno, line in enumerate(path.read_text().splitlines(), start=1):
        match = USES.match(line)
        if not match:
            continue
        ref = match.group("ref")

        # A local action is this repository's own code, already reviewed by
        # whatever gate covers the file. A container action names an image and
        # belongs to expected-containers.json, not here.
        if ref.startswith("./") or ref.startswith("docker://"):
            skipped.append(f"{path}:{lineno} {ref}")
            continue

        site_count += 1

        pinned = re.fullmatch(
            r"(?P<action>[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(?:/[^@\s]+)?)"
            r"@(?P<sha>[0-9a-f]{40})",
            ref,
        )
        if not pinned:
            finding(
                f"{path}:{lineno} is not pinned to a 40-hex commit SHA: {ref!r} "
                "-- a tag or branch can be moved onto any commit by its owner"
            )
            continue

        action = pinned.group("action")
        found.setdefault(action, []).append((str(path), lineno, pinned.group("sha")))

# --- compare what was found against what was declared ----------------------
for action in sorted(found):
    sites = found[action]
    if action not in declared:
        where = ", ".join(f"{f}:{n}" for f, n, _ in sites)
        finding(
            f"{action} is referenced ({where}) but not declared in the baseline"
        )
        continue

    spec = declared[action]

    for path, lineno, sha in sites:
        if sha != spec["sha"]:
            finding(
                f"{path}:{lineno} {action} is pinned to {sha} but the baseline "
                f"records {spec['sha']}"
            )

    if len(sites) != spec["reference_count"]:
        finding(
            f"{action} is referenced at {len(sites)} site(s) but the baseline "
            f"records reference_count {spec['reference_count']}"
        )

    expected_files = set(spec["occurrences"])
    actual_files = {path for path, _, _ in sites}
    for missing in sorted(expected_files - actual_files):
        finding(
            f"{action}: baseline lists occurrence {missing} but no reference "
            "was found there"
        )
    for undeclared in sorted(actual_files - expected_files):
        finding(
            f"{action}: referenced in {undeclared}, which the baseline's "
            "occurrences list does not name"
        )

for action in sorted(set(declared) - set(found)):
    finding(
        f"{action} is declared in the baseline but referenced nowhere -- "
        "delete the entry rather than leaving it to rot"
    )

# --- report ----------------------------------------------------------------
print(f"workflow files:        {len(workflow_files)}")
for path in workflow_files:
    print(f"  {path}")
print(f"third-party sites:     {site_count}")
print(f"distinct actions:      {len(found)}")
print(f"declared in baseline:  {len(declared)}")
if skipped:
    print(f"skipped (local/image): {len(skipped)}")
    for entry in skipped:
        print(f"  {entry}")
print()

for action in sorted(declared):
    spec = declared[action]
    sites = found.get(action, [])
    print(
        f"  {action:<32} {spec['sha'][:12]}...  {spec['tag']:<8} "
        f"{len(sites)} site(s)"
    )
print()

if findings:
    for message in findings:
        print(f"FINDING: {message}")
    print()
    print(f"{len(findings)} finding(s). Every `uses:` reference must name a")
    print("40-hex commit SHA declared in the baseline, and the baseline must be")
    print("updated in the same reviewed commit as the pin it describes -- never")
    print("to silence a red build.")
    raise SystemExit(1)

print("OK: every third-party action is pinned to a declared commit SHA.")
PY
