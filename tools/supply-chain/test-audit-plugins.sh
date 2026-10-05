#!/usr/bin/env bash
#
# Unit tests for the advisory-parsing logic in audit-plugins.sh.
#
# These tests exercise the Python heredoc in isolation using fixture JSON files,
# so no npm network calls are made and no real install is required.
#
# Exit: 0 = all tests passed, 1 = one or more tests failed.
#
# Run:  tools/supply-chain/test-audit-plugins.sh

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

allowlist="tools/supply-chain/accepted-advisories.json"
workdir=".tmp/test-audit-plugins"
rm -rf "$workdir"
mkdir -p "$workdir"

pass=0
fail=0

# ---------------------------------------------------------------------------
# Helper: run the advisory-parsing Python block against a given audit.json
# fixture and assert the expected exit code.
# ---------------------------------------------------------------------------
run_advisory_check() {
    local label="$1"
    local audit_fixture="$2"
    local expected_exit="$3"

    local actual_exit=0
    python3 - "$audit_fixture" "$allowlist" <<'PY' >"$workdir/out.txt" 2>"$workdir/err.txt" || actual_exit=$?

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

for ghsa in sorted(set(accepted) - set(found)):
    failures.append(
        f"{ghsa}: allowlisted in {allowlist_path} but no longer reported by npm audit. "
        f"Either it was fixed upstream or the dependency is gone -- in both cases "
        f"DELETE the entry. Leaving it in place means the next advisory to reuse that "
        f"reasoning inherits an approval nobody granted."
    )

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

    if [[ "$actual_exit" -eq "$expected_exit" ]]; then
        echo "  PASS  [$label] (exit $actual_exit as expected)"
        (( pass++ )) || true
    else
        echo "  FAIL  [$label] expected exit $expected_exit, got $actual_exit"
        echo "        stdout: $(cat "$workdir/out.txt")"
        echo "        stderr: $(cat "$workdir/err.txt")"
        (( fail++ )) || true
    fi
}

# ---------------------------------------------------------------------------
# Fixture 1: npm error document (simulates 503 / unreachable advisory endpoint)
# Shape: top-level "error" key, no "metadata", no "vulnerabilities".
# This is what npm --json emits when the registry is unreachable.
# ---------------------------------------------------------------------------
cat > "$workdir/fixture-error-503.json" <<'JSON'
{
  "error": {
    "code": "E503",
    "summary": "Service Unavailable",
    "detail": "The npm advisory endpoint returned HTTP 503. Try again later."
  }
}
JSON

# ---------------------------------------------------------------------------
# Fixture 2: npm document with metadata but no "vulnerabilities" sub-key
# (another malformed / partial response shape).
# ---------------------------------------------------------------------------
cat > "$workdir/fixture-missing-metadata-vulns.json" <<'JSON'
{
  "metadata": {
    "npmVersion": "10.0.0",
    "nodeVersion": "22.12.0"
  },
  "vulnerabilities": {}
}
JSON

# ---------------------------------------------------------------------------
# Fixture 3: clean audit — no findings, all four allowlisted GHSAs present
# in the report so the staleness check passes.
# Shape mirrors a real npm audit --json output with zero vulnerabilities.
# ---------------------------------------------------------------------------
cat > "$workdir/fixture-clean-with-allowlisted.json" <<'JSON'
{
  "auditReportVersion": 2,
  "vulnerabilities": {
    "undici": {
      "name": "undici",
      "severity": "high",
      "via": [
        {
          "source": 1099519,
          "name": "undici",
          "dependency": "undici",
          "title": "undici WebSocket DoS via unconstrained resource accumulation",
          "url": "https://github.com/advisories/GHSA-vrm6-8vpv-qv8q",
          "severity": "high",
          "cwe": ["CWE-400"],
          "cvss": {"score": 7.5, "vectorString": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"},
          "range": ">=6.21.0 <6.21.1"
        },
        {
          "source": 1099520,
          "name": "undici",
          "dependency": "undici",
          "title": "undici WebSocket DoS via memory exhaustion",
          "url": "https://github.com/advisories/GHSA-v9p9-hfj2-hcw8",
          "severity": "high",
          "cwe": ["CWE-400"],
          "cvss": {"score": 7.5, "vectorString": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"},
          "range": ">=6.21.0 <6.21.1"
        },
        {
          "source": 1099521,
          "name": "undici",
          "dependency": "undici",
          "title": "undici WebSocket DoS via unbounded frame accumulation",
          "url": "https://github.com/advisories/GHSA-vxpw-j846-p89q",
          "severity": "high",
          "cwe": ["CWE-400"],
          "cvss": {"score": 7.5, "vectorString": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"},
          "range": ">=6.21.0 <6.21.1"
        }
      ],
      "effects": [],
      "range": ">=6.21.0 <6.21.1",
      "nodes": ["node_modules/undici"],
      "fixAvailable": true
    },
    "@fastify/busboy": {
      "name": "@fastify/busboy",
      "severity": "high",
      "via": [
        {
          "source": 1099522,
          "name": "@fastify/busboy",
          "dependency": "@fastify/busboy",
          "title": "@fastify/busboy vulnerable to Denial of Service via prototype-named multipart part header",
          "url": "https://github.com/advisories/GHSA-x8mw-p69m-v3mx",
          "severity": "high",
          "cwe": ["CWE-400"],
          "cvss": {"score": 7.5, "vectorString": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"},
          "range": ">=1.0.0 <3.2.1"
        }
      ],
      "effects": [],
      "range": ">=1.0.0 <3.2.1",
      "nodes": ["node_modules/@fastify/busboy"],
      "fixAvailable": true
    }
  },
  "metadata": {
    "vulnerabilities": {
      "info": 0,
      "low": 0,
      "moderate": 0,
      "high": 4,
      "critical": 0,
      "total": 4
    },
    "dependencies": {
      "prod": 7,
      "dev": 0,
      "optional": 0,
      "peer": 0,
      "peerOptional": 0,
      "total": 7
    }
  }
}
JSON

# ---------------------------------------------------------------------------
# Fixture 4: audit with an unaccepted high advisory (gate should fail: exit 1)
# ---------------------------------------------------------------------------
cat > "$workdir/fixture-unaccepted-high.json" <<'JSON'
{
  "auditReportVersion": 2,
  "vulnerabilities": {
    "some-pkg": {
      "name": "some-pkg",
      "severity": "high",
      "via": [
        {
          "source": 9999999,
          "name": "some-pkg",
          "dependency": "some-pkg",
          "title": "Remote code execution in some-pkg",
          "url": "https://github.com/advisories/GHSA-xxxx-xxxx-xxxx",
          "severity": "high",
          "cwe": ["CWE-94"],
          "cvss": {"score": 9.8, "vectorString": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"},
          "range": ">=1.0.0 <2.0.0"
        }
      ],
      "effects": [],
      "range": ">=1.0.0 <2.0.0",
      "nodes": ["node_modules/some-pkg"],
      "fixAvailable": true
    }
  },
  "metadata": {
    "vulnerabilities": {
      "info": 0,
      "low": 0,
      "moderate": 0,
      "high": 1,
      "critical": 0,
      "total": 1
    },
    "dependencies": {
      "prod": 1,
      "dev": 0,
      "optional": 0,
      "peer": 0,
      "peerOptional": 0,
      "total": 1
    }
  }
}
JSON

# ---------------------------------------------------------------------------
# Run the tests
# ---------------------------------------------------------------------------
echo "=== audit-plugins.sh advisory-parsing tests ==="
echo

echo "--- Transport failure detection ---"
run_advisory_check \
    "503 error document exits 2, not 0 (silent pass)" \
    "$workdir/fixture-error-503.json" \
    2

run_advisory_check \
    "metadata present but no metadata.vulnerabilities exits 2" \
    "$workdir/fixture-missing-metadata-vulns.json" \
    2

echo
echo "--- Valid audit documents ---"
run_advisory_check \
    "all four allowlisted GHSAs present: exits 0 (clean)" \
    "$workdir/fixture-clean-with-allowlisted.json" \
    0

run_advisory_check \
    "unaccepted high advisory exits 1 (gate fail, not could-not-run)" \
    "$workdir/fixture-unaccepted-high.json" \
    1

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
total=$(( pass + fail ))
echo "=== Results: $pass/$total passed ==="
if [[ "$fail" -gt 0 ]]; then
    echo "FAIL: $fail test(s) failed"
    exit 1
fi
echo "PASS: all tests passed"
