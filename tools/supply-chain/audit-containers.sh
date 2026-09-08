#!/usr/bin/env bash
#
# Audits the digest-pinned container images this repository runs.
#
# Why this exists. EOP-155 put the first pinned external container into CI, and the review that
# followed noted that tools/supply-chain/ covered the seven OpenCode npm plugins and nothing else --
# so the container layer was pinned carefully by hand and then watched by nobody. Prose in an ADR
# recorded how to derive a container pin correctly. Prose does not run. This script does.
#
# What it checks, in order of how much it would actually tell you:
#
#   0. ITS OWN PARSER, before it opens a single file. Every other check here compares two things and
#      reports the difference, so it fails loudly. The reference parse is the one step that can fail
#      SILENTLY: a pin the regex never matches is a pin this script never mentions, and the run goes
#      green. So REF_CASES pins nine readings -- including a registry host with a port, which an
#      earlier revision misparsed, and a bare "sha256:..." in a comment, which must NOT be read as a
#      reference. A mismatch fails the run before any discovery output is printed.
#
#   1. ROSTER DRIFT, bidirectionally, and without touching the network. Every "name@sha256:..."
#      reference in a tracked file must have a baseline entry, and every baseline entry must still
#      be referenced. The second direction is not symmetry for its own sake: replacing a digest with
#      a floating tag ORPHANS the baseline entry, so unpinning fails here rather than passing
#      quietly, which is the failure mode a pin exists to prevent.
#
#   2. MIRROR AGREEMENT, also hermetic. A digest that appears in more than one file must be the
#      same digest in all of them. This closes a hole that is live today rather than theoretical:
#      sonarsource/sonar-scanner-cli appears in tools/sonar/scan-ui.sh and in the two committed
#      JSONs that record which scanner produced them, and sonar-ratchet-ui's sourceHash covers only
#      ui/package.json, ui/tsconfig.json, ui/vite.config.ts and ui/src/** -- so bumping the scanner
#      without rescanning leaves the report naming a scanner that never ran, and nothing else in
#      the build notices.
#
#   3. PIN FORM. sha256 plus 64 hex, and the tag beside it -- if there is one -- is not "latest".
#      A digest beside :latest is not wrong, but it is a pin whose author was thinking about a tag.
#
#   3b. PIN COVERAGE over every compose file, and this one is a POLICY rather than a tripwire: it
#      decides that a reference is wrong from the reference itself and consults the baseline only
#      for the exemptions, so a bare tag fails on its FIRST commit instead of merely being
#      recorded. Checks 1 to 3 cannot see an image that was never pinned -- discovery skips a file
#      with no "@sha256:" in it -- which is how postgres:17-alpine sat floating in the DEPLOYED
#      stack, unwatched, while three CI-only containers were audited to the attestation count.
#      EOP-229 closed that and widened ADR-064's scope to every stack. An image genuinely unable to
#      carry a registry digest (the two built from this repository) is declared in the baseline's
#      unpinned_allowlist with a reason AND a built_from path this script checks against the tracked
#      tree, so the exemption's premise is proved rather than asserted. Compared bidirectionally, so
#      pinning one later forces its exemption to be deleted. Same shape as audit-actions.sh's form
#      check (ADR-073), deliberately.
#
#   4. REGISTRY SHAPE, over the network. The digest must still resolve, and its mediaType, platform
#      list and attestation count must match the baseline. For an image CI runs, linux/amd64 must
#      be reachable at that digest -- this is the ADR-055 trap that cost a red build once: pinning
#      a per-platform sub-manifest from an arm64 developer Mac breaks ubuntu-latest with "no
#      matching manifest for linux/amd64". Encoding it here means the next pin cannot repeat it.
#
#   5. TAG DRIFT, over the network, reported and NEVER gated. Where a reference carries a tag, the
#      tag's current digest is compared to the pin. A mutable tag moving is ordinary upstream
#      behaviour and is precisely what a digest defends against, so this is news, not a failure.
#      Note this is the inverse of the npm case, where a pin names an exact version and a moved
#      version IS the incident.
#
# What it does NOT check, and must never be described as checking. It does not tell you an image is
# safe, and it cannot. A digest is content-addressed, so there is no container equivalent of the npm
# maintainer handoff that audit-plugins.sh watches for -- nobody can alter what a pinned digest
# resolves to. This is a COVERAGE AND PROCEDURE gate: it proves every pinned container is declared,
# shaped correctly for where it runs, and consistently recorded. It scans no image contents, reads
# no CVE feed and verifies no signature. Image CVE scanning was considered and rejected in ADR-064:
# it would mean a third gating scanner and a third allowlist over this tree, and ci.yml's own
# argument for keeping dependency-cve narrow applies unchanged -- two allowlists to keep in step,
# and the first divergence is silent.
#
# It also covers containers only, and only where a container is declared in a compose file or
# invoked from a tracked script. The GitHub Actions layer is no longer the gap this line used to
# name -- ADR-073 brought every "uses:" in .github/workflows/ under audit-actions.sh, with a form
# check that fails a bare tag outright, which is the instrument check 3b above is modelled on. The
# whole Maven plugin layer IS still unwatched: fifteen bound plugins and thirty-one under
# pluginManagement, most of their versions arriving from the Spring Boot parent, which ADR-064 named
# as the largest genuinely uncovered surface in the repository and deferred to its own decision.
#
# Usage: tools/supply-chain/audit-containers.sh
# Exit:  0 = clean, 1 = drift, a malformed pin or an unpinned image, 2 = could not run.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

baseline="tools/supply-chain/expected-containers.json"
workdir=".tmp/supply-chain-containers"

# Inside the worktree on purpose: .tmp/ is gitignored and needs no external_directory grant,
# unlike /tmp. See AGENTS.md. Guarded because a permissions failure here is could-not-run,
# not a clean tree: under `set -e` an unguarded failure exits with the shell's own code --
# typically 1, which this script's contract reserves for drift or a malformed pin -- so an
# unwritable workspace would read as a finding. scan-dependencies.sh and audit-plugins.sh
# carry the same guards for the same reason; the three were fixed together (EOP-146).
rm -rf "$workdir" || { echo "FATAL: could not remove $workdir"; exit 2; }
mkdir -p "$workdir" || { echo "FATAL: could not create $workdir"; exit 2; }

command -v git >/dev/null 2>&1 || { echo "FATAL: git is not on PATH"; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "FATAL: python3 is not on PATH"; exit 2; }
command -v docker >/dev/null 2>&1 || { echo "FATAL: docker is not on PATH"; exit 2; }
docker buildx version >/dev/null 2>&1 || {
    echo "FATAL: 'docker buildx' is unavailable. This audit reads manifests with"
    echo "       'docker buildx imagetools inspect', which ADR-055 names as the authoritative"
    echo "       source for a container digest. Do not substitute 'docker inspect' -- on a"
    echo "       developer machine it reports a different digest entirely."
    exit 2
}
[[ -f "$baseline" ]] || {
    echo "FATAL: baseline $baseline is missing. It is the only record of what these pins"
    echo "       resolved to when they were last reviewed; without it there is nothing to"
    echo "       compare against and the audit must not pass by default."
    exit 2
}

# ---------------------------------------------------------------------------------------------
echo "=== Discovering digest-pinned container references in tracked files ==="

git ls-files -z > "$workdir/tracked.z"

python3 - "$baseline" "$workdir" <<'PY'
import json
import re
import sys
from pathlib import Path

baseline_path, workdir = sys.argv[1], sys.argv[2]

# A reference is name[:tag]@sha256:<64 hex>. The negative lookbehind stops a longer path being
# clipped mid-token. Bare "sha256:..." digests are deliberately NOT matched: compose.sonar.yml and
# ADR-055 both quote child platform digests that way, and they are records of what an index
# contains rather than references anything runs.
#
# The leading "host:port/" alternative is load-bearing and was added after review (EOP-159). A
# registry host is otherwise indistinguishable from a path component -- "ghcr.io/owner/name" needs
# no special case, because a dot is already legal in a path component -- but a host carrying an
# explicit PORT does, because the colon is not. Without that alternative,
# "registry.example.com:5000/img@sha256:..." parsed as image "5000/img" with no tag: a wrong name
# rather than a silent miss, so the roster check would have failed loudly on an image nobody
# declared, but with a finding naming something that does not exist. The alternative demands
# ":<digits>/" so it can never swallow an ordinary first path component: "grafana/k6" has no colon
# and is unaffected. REF_CASES below pins every one of these readings.
REF = re.compile(
    rb"(?<![\w./@-])"
    rb"((?:[a-z0-9][a-z0-9.-]*:[0-9]+/)?"
    rb"[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*)"
    rb"(?::([A-Za-z0-9_][A-Za-z0-9._-]*))?"
    rb"@(sha256:[0-9a-f]{64})"
)

# A reference is written directly in most files, but a compose file writes it as a shell-style
# default: "image: ${POSTGRES_IMAGE:-postgres:17-alpine@sha256:...}". REF cannot read that, and the
# way it fails is the one failure mode this audit is built to avoid (EOP-229). The "${VAR:-" prefix
# ends in a hyphen, which the negative lookbehind excludes, so a match cannot begin at the image
# name. What happens next depends on the reference:
#
#   ${POSTGRES_IMAGE:-postgres:17-alpine@sha256:...}   parses as image "17-alpine"  -- a WRONG NAME
#   ${SCANNER_IMAGE:-sonarsource/sonar-scanner-cli@sha256:...}   does NOT MATCH AT ALL -- a SILENT MISS
#
# The wrong name is survivable: the roster check fails loudly on an image nobody declared. The
# silent miss is not -- a tagless reference offers no later colon for the regex to restart at, so
# the pin is invisible and the run goes green. A variable-width lookbehind alternative would be the
# natural fix and Python's re module forbids one, so the substitution is expanded to its default
# BEFORE matching instead. That is also exactly the reading the unpinned-image policy check below
# needs -- pinning the default is what leaves ${VAR} overridable -- so the two share one function.
#
# ${VAR} and ${VAR:?msg} are deliberately NOT expanded: neither carries a default, so there is no
# reference here to audit. The policy check treats such a line as a finding rather than skipping it.
SUBST = re.compile(rb"\$\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)\}")


def expand_defaults(blob):
    """Rewrite ${VAR:-default} to default so a reference inside one is readable."""
    return SUBST.sub(rb"\1", blob)


# The parse is the one part of this audit that can fail SILENTLY -- every other check compares two
# things and says so when they differ, but a reference the regex does not see is a pin the audit
# never mentions. So the readings are pinned here as a table and asserted on every run, before any
# file is opened. Cheap enough to be unconditional, and unconditional is the point: a table that
# only runs when someone remembers to ask is a table that rots.
_D = b"sha256:" + b"0" * 64
REF_CASES = (
    # blob                                              image                            tag
    (b"    image: sonarqube@" + _D,                      "sonarqube",                     None),
    (b"  grafana/k6:2.2.0@" + _D,                        "grafana/k6",                    "2.2.0"),
    (b'IMG="ghcr.io/maglez/eop@' + _D + b'"',            "ghcr.io/maglez/eop",            None),
    (b'IMG="ghcr.io/maglez/eop:1.2.3@' + _D + b'"',      "ghcr.io/maglez/eop",            "1.2.3"),
    (b"  registry.example.com:5000/img@" + _D,           "registry.example.com:5000/img",  None),
    (b"  registry.example.com:5000/ns/img:v1@" + _D,     "registry.example.com:5000/ns/img", "v1"),
    (b"  localhost:5000/img@" + _D,                      "localhost:5000/img",            None),
    (b"  a/b/c/d@" + _D,                                 "a/b/c/d",                       None),
    # Compose's ${VAR:-default} form. All three were broken before EOP-229: the first two parsed
    # as "17-alpine" and "12.4.0", the third did not match at all. See expand_defaults above.
    (b"    image: ${POSTGRES_IMAGE:-postgres:17-alpine@" + _D + b"}", "postgres",          "17-alpine"),
    (b"    image: ${GRAFANA_IMAGE:-grafana/grafana:12.4.0@" + _D + b"}", "grafana/grafana", "12.4.0"),
    (b'IMG="${SCANNER_IMAGE:-sonarsource/sonar-scanner-cli@' + _D + b'}"', "sonarsource/sonar-scanner-cli", None),
    # ${VAR} and ${VAR:?msg} carry no default, so there is no reference to read.
    (b"    image: ${APP_IMAGE}",                          None,                            None),
    # A bare child-platform digest, as compose.sonar.yml and ADR-055 quote them: not a reference.
    (b"  #   linux/arm64 -> " + _D,                      None,                            None),
)
_self_test_failures = []
for _blob, _want_image, _want_tag in REF_CASES:
    _m = REF.search(expand_defaults(_blob))
    _got_image = _m.group(1).decode() if _m else None
    _got_tag = _m.group(2).decode() if (_m and _m.group(2)) else None
    if (_got_image, _got_tag) != (_want_image, _want_tag):
        _self_test_failures.append(
            f"reference parse regressed on {_blob.decode()!r}: "
            f"expected image={_want_image!r} tag={_want_tag!r}, got image={_got_image!r} tag={_got_tag!r}"
        )
if _self_test_failures:
    print("=== REFERENCE PARSER SELF-TEST FAILED ===")
    for _f in _self_test_failures:
        print(f"  - {_f}")
    print()
    print("  The discovery regex no longer reads references the way this audit documents. Every")
    print("  other check is downstream of it, so a PASS below would mean nothing. Fix the regex")
    print("  or, if a reading genuinely changed, change REF_CASES in the same reviewed commit.")
    sys.exit(1)

# docs/jira-export/ is a frozen third-party dump of the decommissioned Jira instance; it is history,
# not configuration. The baseline itself is skipped so that a note may quote a full reference as
# prose without the audit reading its own documentation as a pin site.
SKIP_PREFIXES = ("docs/jira-export/",)
SKIP_EXACT = {baseline_path}

paths = [p for p in Path(workdir, "tracked.z").read_bytes().split(b"\x00") if p]

found = {}   # image -> {"tags": set, "digests": set, "occurrences": set}
for raw in paths:
    rel = raw.decode("utf-8", "replace")
    if rel in SKIP_EXACT or rel.startswith(SKIP_PREFIXES):
        continue
    try:
        blob = Path(rel).read_bytes()
    except (OSError, IsADirectoryError):
        continue
    if b"@sha256:" not in blob:
        continue
    for m in REF.finditer(expand_defaults(blob)):
        image = m.group(1).decode()
        tag = m.group(2).decode() if m.group(2) else None
        digest = m.group(3).decode()
        e = found.setdefault(image, {"tags": set(), "digests": set(), "occurrences": set()})
        e["tags"].add(tag)
        e["digests"].add(digest)
        e["occurrences"].add(rel)

out = {
    img: {
        "tags": sorted(t for t in e["tags"] if t is not None),
        "tagless": None in e["tags"],
        "digests": sorted(e["digests"]),
        "occurrences": sorted(e["occurrences"]),
    }
    for img, e in found.items()
}
Path(workdir, "found.json").write_text(json.dumps(out, indent=2, sort_keys=True))

if not out:
    print("  found no digest-pinned references at all -- that is not credible for this")
    print("  repository, so treating it as a broken scan rather than a clean result.")
    sys.exit(2)

for img in sorted(out):
    e = out[img]
    tag = e["tags"][0] if e["tags"] else "(tagless)"
    print(f"  {img}  tag={tag}  {len(e['occurrences'])} occurrence(s)")
PY

# ---------------------------------------------------------------------------------------------
echo ""
echo "=== Roster, occurrence and pin-form drift against the baseline ==="

python3 - "$baseline" "$workdir" <<'PY'
import json
import re
import sys
from pathlib import Path

baseline_path, workdir = sys.argv[1], sys.argv[2]
expected = json.loads(Path(baseline_path).read_text())["containers"]
found = json.loads(Path(workdir, "found.json").read_text())

DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
REQUIRED = {"tag", "digest", "mediaType", "platforms", "attestations", "occurrences", "note"}

failures = []

for img in sorted(set(found) - set(expected)):
    failures.append(
        f"{img} is pinned in the repository but absent from {baseline_path}. A container with "
        f"no baseline entry is a container nothing checks -- add it in the same commit that "
        f"adds the pin, deriving its values with "
        f"'docker buildx imagetools inspect {img}@<digest>'."
    )

for img in sorted(set(expected) - set(found)):
    failures.append(
        f"{img} is in the baseline but no longer pinned by digest anywhere. Either it was "
        f"removed deliberately -- in which case remove its baseline entry too -- or a digest "
        f"was replaced by a floating tag, which is an unpinning and must be reverted."
    )

for img in sorted(set(expected) & set(found)):
    exp, act = expected[img], found[img]

    missing = REQUIRED - set(exp)
    surplus = set(exp) - REQUIRED
    if missing:
        failures.append(f"{img}: baseline entry is missing required field(s) {sorted(missing)}.")
    if surplus:
        failures.append(
            f"{img}: baseline entry carries unrecognised field(s) {sorted(surplus)}. A field "
            f"nothing reads is a field that looks enforced while doing nothing."
        )
    if missing:
        continue

    if not DIGEST.match(str(exp["digest"])):
        failures.append(f"{img}: baseline digest {exp['digest']!r} is not sha256 plus 64 hex.")

    # One digest across every occurrence. This is the mirror check.
    if len(act["digests"]) > 1:
        failures.append(
            f"{img} is pinned to {len(act['digests'])} different digests across its "
            f"occurrences: {act['digests']}. The copies have diverged -- reconcile them, and if "
            f"one of them is a committed scan report, re-run the scan rather than editing the "
            f"digest by hand."
        )
    elif act["digests"][0] != exp["digest"]:
        failures.append(
            f"{img} is pinned to {act['digests'][0]} but the baseline records "
            f"{exp['digest']}. If the pin moved deliberately, re-verify it with "
            f"'docker buildx imagetools inspect' and update the baseline in this same commit."
        )

    exp_tag = exp["tag"]
    if exp_tag is None:
        if act["tags"]:
            failures.append(
                f"{img}: baseline says tagless but the repository pins it as "
                f"{act['tags'][0]}@<digest>. Record the tag or drop it, but do not leave the "
                f"two disagreeing."
            )
    else:
        if act["tagless"]:
            failures.append(
                f"{img}: baseline records tag {exp_tag!r} but at least one occurrence carries "
                f"no tag. Note the field is mandatory-but-nullable on purpose -- set it to null "
                f"if tagless is intended."
            )
        if act["tags"] and act["tags"] != [exp_tag]:
            failures.append(
                f"{img}: baseline records tag {exp_tag!r}, repository carries {act['tags']}."
            )
        if exp_tag == "latest":
            failures.append(
                f"{img}: pinned beside the tag 'latest'. The digest still governs what runs, so "
                f"this is not a live hazard, but it is a pin whose author was thinking about a "
                f"tag -- name the version instead."
            )

    if sorted(act["occurrences"]) != sorted(exp["occurrences"]):
        extra = sorted(set(act["occurrences"]) - set(exp["occurrences"]))
        gone = sorted(set(exp["occurrences"]) - set(act["occurrences"]))
        if extra:
            failures.append(
                f"{img} now appears in {extra}, which the baseline does not list. Every place a "
                f"digest is written is a place it can go stale -- list it, or remove the "
                f"duplication."
            )
        if gone:
            failures.append(
                f"{img} no longer appears in {gone}, which the baseline still lists. If the "
                f"reference moved or was deleted, update occurrences in this same commit."
            )

if failures:
    print("=== DRIFT DETECTED ===")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

print("  no drift against the baseline")
PY

# ---------------------------------------------------------------------------------------------
echo ""
echo "=== Every compose image: line is digest-pinned or declared (POLICY) ==="

# This is the one check here that does not consult the baseline to decide whether something is
# WRONG -- it decides that from the reference itself, and consults the baseline only for the
# exemptions. That inversion is the point. Every check above is a TRIPWIRE: it records what a pin
# resolved to and tells you when the record and the tree disagree, which means an image that was
# NEVER pinned is invisible to it. The discovery pass skips any file with no "@sha256:" in it at
# all, so before EOP-229 compose.app.yml, compose.e2e.yml and docker-compose.yml were not read by
# this script in any sense -- and postgres:17-alpine was floating in the DEPLOYED stack, unwatched,
# while three CI-only containers were audited to the platform and attestation count.
#
# So this check fails on a bare tag on its FIRST commit rather than merely being recorded, which is
# what makes the container layer a policy and not just a tripwire. It is deliberately the same shape
# as audit-actions.sh's form check (ADR-073): a "uses:" written as a bare tag fails there for the
# same reason, and the two now agree about what pinning means.
#
# The exemption list is bidirectional like everything else here. An unpinned image with no entry
# fails; an entry that no longer matches any unpinned reference ALSO fails, so pinning an image
# later forces its exemption to be deleted rather than left behind as a standing licence.
python3 - "$baseline" "$workdir" <<'PY'
import json
import re
import sys

baseline_path, workdir = sys.argv[1], sys.argv[2]

# A compose file by name, matching the two conventions in the tree: the historical
# docker-compose.yml and the compose.<stack>.yml family.
COMPOSE = re.compile(r"^(?:.*/)?(?:docker-)?compose(?:\.[A-Za-z0-9_-]+)*\.ya?ml$")

# An "image:" mapping. Anchored at the key so a commented-out line and an unrelated key such as
# imagePullPolicy cannot match. A "#" cannot appear inside an image reference, so stripping a
# trailing comment is safe rather than a guess.
IMAGE_LINE = re.compile(r"^\s*image:\s*(.+?)\s*(?:#.*)?$")

# The same normalisation the discovery pass uses, for the same reason: the reference that actually
# runs when nobody sets the variable is the default inside ${VAR:-default}. Pinning THAT leaves the
# operator override intact, which is why every compose reference here is written in this form.
SUBST = re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)\}")

DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")


def effective_ref(line):
    """The image reference a compose line resolves to, or None if the line is not an image."""
    m = IMAGE_LINE.match(line)
    if not m:
        return None
    captured = m.group(1).strip()
    ref = captured
    if len(ref) >= 2 and ref[0] == ref[-1] and ref[0] in "\"'":
        ref = ref[1:-1].strip()
    # Falling back to the captured text rather than None is load-bearing. An "image:" line whose
    # value resolves to nothing -- an empty default (${VAR:-}) or an empty string ("") -- is still
    # an image line, and returning None would drop it from the policy entirely. That is the one
    # outcome a TOTAL policy cannot allow: every image line must end up either digest-pinned or
    # declared, and "unreadable" has to fall on the reportable side of that line, never the
    # exempt side. So the unresolved text is returned and reported as unpinned.
    return SUBST.sub(r"\1", ref) or captured or None


def image_name(ref):
    """The name part of a reference: no digest, no tag, registry host and port preserved."""
    # An unresolved substitution is not a name, and splitting one on its last ":" mangles it --
    # "${APP_IMAGE:?must be set}" would yield "${APP_IMAGE". Return it whole so the finding quotes
    # what the file actually says. ${VAR:-default} never reaches here; SUBST expanded it already.
    if "${" in ref:
        return ref
    ref = ref.split("@", 1)[0]
    head, sep, tail = ref.rpartition(":")
    if sep and "/" not in tail:
        return head
    return ref


# Check 0 again, for this parser. The reasoning is the header's: a discovery rule that matches
# nothing passes green, and this one decides whether a line is subject to the policy at all. A
# regex that silently stopped matching "image:" would turn a gate into a no-op with no output
# change. Every case below is a line that exists in this tree or a near miss that must not match.
_D = "@sha256:" + "0" * 64
LINE_CASES = [
    ("    image: postgres:17-alpine", "postgres:17-alpine"),
    ("    image: ${POSTGRES_IMAGE:-postgres:17-alpine" + _D + "}", "postgres:17-alpine" + _D),
    ("    image: ${UI_IMAGE:-eop-ui:e2e}", "eop-ui:e2e"),
    ("      image: \"influxdb:1.8\"", "influxdb:1.8"),
    ("      image: 'influxdb:1.8'", "influxdb:1.8"),
    ("    image: grafana/grafana:12.4.0" + _D + "   # trailing comment", "grafana/grafana:12.4.0" + _D),
    # No default, so nothing can be read out of it -- a finding, never a skip. None exist today.
    ("    image: ${APP_IMAGE}", "${APP_IMAGE}"),
    ("    image: ${APP_IMAGE:?must be set}", "${APP_IMAGE:?must be set}"),
    # Resolves to nothing. Must still be READ as an image line so the policy can report it: dropping
    # it would exempt it, and an exemption nobody declared is the failure this check exists to stop.
    ("    image: ${APP_IMAGE:-}", "${APP_IMAGE:-}"),
    ("    image: \"\"", "\"\""),
    # Near misses that must NOT be read as image lines.
    ("    imagePullPolicy: Always", None),
    ("    # image: postgres:17-alpine", None),
    ("    build: ./ui", None),
]
NAME_CASES = [
    ("postgres:17-alpine" + _D, "postgres"),
    ("grafana/grafana:12.4.0", "grafana/grafana"),
    ("sonarqube" + _D, "sonarqube"),
    ("eop-threat-modeling:local", "eop-threat-modeling"),
    # A registry host carrying a port must not be mistaken for a tag -- the trap EOP-159 found in
    # the reference regex above, restated here because this parser splits on ":" independently.
    ("registry.example.com:5000/team/app", "registry.example.com:5000/team/app"),
    ("registry.example.com:5000/team/app:1.2.3", "registry.example.com:5000/team/app"),
    ("localhost:5000/img", "localhost:5000/img"),
    # An unresolved substitution is returned whole rather than split into nonsense.
    ("${APP_IMAGE}", "${APP_IMAGE}"),
    ("${APP_IMAGE:?must be set}", "${APP_IMAGE:?must be set}"),
]
_self = []
for _line, _want in LINE_CASES:
    _got = effective_ref(_line)
    if _got != _want:
        _self.append(f"effective_ref({_line!r}) returned {_got!r}, expected {_want!r}")
for _ref, _want in NAME_CASES:
    _got = image_name(_ref)
    if _got != _want:
        _self.append(f"image_name({_ref!r}) returned {_got!r}, expected {_want!r}")
if _self:
    print("=== FATAL: this check's own parser is wrong ===")
    for _f in _self:
        print(f"  - {_f}")
    print("  Every finding below this point is derived from these two functions, so a parser that")
    print("  misreads a line makes the whole policy silently permissive. Fix the parser, or fix")
    print("  the case if the case is what is wrong -- never delete a case to get a green run.")
    sys.exit(1)

with open(baseline_path, encoding="utf-8") as fh:
    doc = json.load(fh)

if "unpinned_allowlist" not in doc:
    print(f"FATAL: {baseline_path} has no 'unpinned_allowlist' key. It is where an image that")
    print("       genuinely cannot carry a registry digest is declared with its reason. An")
    print("       absent key is not an empty one: it means this baseline predates the policy")
    print("       check, and passing by default is the one outcome that must not happen.")
    sys.exit(2)
allowlist = doc["unpinned_allowlist"]

with open(f"{workdir}/tracked.z", "rb") as fh:
    tracked = [p.decode("utf-8") for p in fh.read().split(b"\0") if p]

# Every path git tracks, so an exemption's built_from claim can be checked against the tree rather
# than believed. Reading it from git rather than from the filesystem is deliberate: an untracked
# Dockerfile is not part of the repository and must not be able to justify an exemption in it.
tracked_paths = set(tracked)

compose_files = sorted(p for p in tracked if COMPOSE.match(p))

# Anti-vacuity floors, as a backstop to the parser self-test rather than the main instrument. The
# self-test proves the parsers read a line correctly; these prove they were pointed at the tree.
# Both are lower bounds, so adding a stack or a service never trips them -- only losing one does,
# and that is worth a deliberate, reviewed edit. Same idiom as MINIMUM_SEQUENCE_DIAGRAMS.
MINIMUM_COMPOSE_FILES = 5
MINIMUM_IMAGE_LINES = 11

pinned, unpinned = [], {}
image_line_count = 0
for path in compose_files:
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, start=1):
            ref = effective_ref(line.rstrip("\n"))
            if ref is None:
                continue
            image_line_count += 1
            if DIGEST.search(ref):
                pinned.append((path, lineno, ref))
            else:
                unpinned.setdefault(image_name(ref), []).append((path, lineno, ref))

print(f"  {len(compose_files)} compose file(s), {image_line_count} image: line(s)")
for path in compose_files:
    print(f"    {path}")

failures = []
if len(compose_files) < MINIMUM_COMPOSE_FILES:
    failures.append(
        f"found {len(compose_files)} compose file(s), expected at least "
        f"{MINIMUM_COMPOSE_FILES}. A policy that examines nothing passes green, so this floor "
        f"fails instead. If a stack was deliberately removed, lower the floor in the same commit."
    )
if image_line_count < MINIMUM_IMAGE_LINES:
    failures.append(
        f"found {image_line_count} image: line(s), expected at least {MINIMUM_IMAGE_LINES}. "
        f"Either a service was removed -- lower the floor in the same commit -- or the line "
        f"parser has stopped matching a form it used to read."
    )

for name in sorted(unpinned):
    sites = unpinned[name]
    where = ", ".join(f"{p}:{n}" for p, n, _ in sites)
    if name not in allowlist:
        failures.append(
            f"{name} is used without a digest at {where}. A tag is a mutable pointer: the image "
            f"that ran yesterday and the image that runs today can differ with no change to this "
            f"repository. Pin it as name:tag@sha256:<64 hex>, add its entry to the 'containers' "
            f"baseline in the same commit, and derive the digest with 'docker buildx imagetools "
            f"inspect' -- never 'docker inspect', which reports a different digest on a "
            f"developer machine (ADR-055). If the image is built from this repository and has no "
            f"registry digest to pin, declare it in 'unpinned_allowlist' with a reason and a "
            f"'built_from' naming the tracked Dockerfile that builds it."
        )
        continue
    entry = allowlist[name]
    missing = {"reason", "built_from", "occurrences"} - set(entry)
    surplus = set(entry) - {"reason", "built_from", "occurrences"}
    if missing:
        failures.append(
            f"unpinned_allowlist entry {name} is missing {sorted(missing)}. All three fields are "
            f"mandatory: an exemption with no reason is indistinguishable from an oversight."
        )
    if surplus:
        failures.append(
            f"unpinned_allowlist entry {name} carries unexpected field(s) {sorted(surplus)}. A "
            f"misspelled field must fail rather than read as a harmless extra."
        )
    if "reason" in entry and not str(entry["reason"]).strip():
        failures.append(
            f"unpinned_allowlist entry {name} has an empty reason. The reason is the whole "
            f"content of the exemption."
        )
    # built_from is what stops this allowlist being a general-purpose bypass, and it is the ONE
    # field here a reviewer cannot be talked out of. The exemption's whole premise is "this image
    # is built from this repository, so no registry digest exists to pin" -- a claim prose can
    # assert and a path can PROVE. A third-party image has no Dockerfile here to name, so moving
    # one in now takes committing a Dockerfile that builds it, which is a visible act rather than
    # a plausible sentence. Raised in EOP-229's security review, which found the reason field
    # alone left an attacker with commit access a cheaper route than unpinning an image outright.
    if "built_from" in entry:
        declared = str(entry["built_from"]).strip()
        if not declared:
            failures.append(
                f"unpinned_allowlist entry {name} has an empty built_from. It must name the "
                f"tracked Dockerfile that builds this image."
            )
        elif declared not in tracked_paths:
            failures.append(
                f"unpinned_allowlist entry {name} claims to be built from {declared!r}, which is "
                f"not a tracked file. The exemption rests entirely on the image being built here "
                f"rather than pulled, so that claim is checked and not merely read. If the image "
                f"comes from a registry it must be pinned by digest, not exempted."
            )

    if "occurrences" in entry:
        actual = sorted({p for p, _, _ in sites})
        if sorted(entry["occurrences"]) != actual:
            failures.append(
                f"unpinned_allowlist entry {name} lists occurrences "
                f"{sorted(entry['occurrences'])} but is used unpinned in {actual}. Keeping this "
                f"exact means a new unpinned use of an already-exempt image still surfaces here "
                f"instead of inheriting the exemption silently."
            )

for name in sorted(set(allowlist) - set(unpinned)):
    failures.append(
        f"unpinned_allowlist entry {name} matches no unpinned reference. If it was pinned, "
        f"DELETE the entry in the same commit -- a stale exemption is a standing licence to "
        f"unpin it again with nothing to notice. If it was removed, delete the entry too."
    )

if failures:
    print("=== POLICY VIOLATION ===")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

for path, lineno, ref in pinned:
    print(f"  pinned    {path}:{lineno}  {image_name(ref)}")
for name in sorted(unpinned):
    sites = unpinned[name]
    where = ", ".join(f"{p}:{n}" for p, n, _ in sites)
    print(f"  declared  {where}  {name} -- {allowlist[name]['reason']}")
print("  every image: line is either digest-pinned or declared with a reason")
PY

# ---------------------------------------------------------------------------------------------
echo ""
echo "=== Resolving each pinned digest at the registry ==="

python3 - "$baseline" "$workdir/images.txt" <<'PY'
import json
import sys
from pathlib import Path

expected = json.loads(Path(sys.argv[1]).read_text())["containers"]
Path(sys.argv[2]).write_text(
    "".join(f"{img}\t{e['digest']}\t{e['tag'] or ''}\n" for img, e in sorted(expected.items()))
)
PY

while IFS=$'\t' read -r image digest tag; do
    [[ -n "$image" ]] || continue
    slug="${image//\//__}"
    echo "  inspecting ${image}@${digest:0:19}..."

    if ! docker buildx imagetools inspect --raw "${image}@${digest}" \
            > "$workdir/${slug}.manifest.json" 2> "$workdir/${slug}.manifest.err"; then
        echo "FATAL: could not resolve ${image}@${digest} at the registry."
        sed 's/^/       /' "$workdir/${slug}.manifest.err"
        echo "       Treating this as 'could not run' rather than as drift: an unreachable"
        echo "       registry, an anonymous rate limit or an offline machine all look identical"
        echo "       from here, and none of them is evidence the pin is wrong. Re-run when the"
        echo "       network is available. If the digest genuinely no longer exists, that IS a"
        echo "       finding -- but confirm it by hand before touching the baseline."
        exit 2
    fi

    # For a single-platform manifest the platform lives in the config blob rather than the
    # manifest, so it has to be resolved separately. Only the '{{json .Image}}' form works --
    # '{{.Image.Os}}' errors with "can't evaluate field Os in type *v1.Image".
    docker buildx imagetools inspect --format '{{json .Image}}' "${image}@${digest}" \
        > "$workdir/${slug}.image.json" 2>/dev/null || echo 'null' > "$workdir/${slug}.image.json"
done < "$workdir/images.txt"

echo ""
echo "=== Registry shape against the baseline ==="

python3 - "$baseline" "$workdir" <<'PY'
import json
import sys
from pathlib import Path

baseline_path, workdir = sys.argv[1], sys.argv[2]
expected = json.loads(Path(baseline_path).read_text())["containers"]
found = json.loads(Path(workdir, "found.json").read_text())

ATTESTATION = "vnd.docker.reference.type"
failures = []


def platform_string(p):
    s = f"{p.get('os', '?')}/{p.get('architecture', '?')}"
    if p.get("variant"):
        s += "/" + p["variant"]
    return s


for image in sorted(expected):
    exp = expected[image]
    slug = image.replace("/", "__")
    manifest = json.loads(Path(workdir, f"{slug}.manifest.json").read_text())

    media = manifest.get("mediaType", "(absent)")
    if media != exp["mediaType"]:
        failures.append(
            f"{image}: registry serves mediaType {media!r} at this digest, baseline records "
            f"{exp['mediaType']!r}. A digest is immutable, so this means the baseline was "
            f"derived from a different reference than the one pinned -- most likely a "
            f"per-platform sub-manifest instead of the index."
        )

    children = manifest.get("manifests")
    attestations = 0
    if children is None:
        # Single-platform manifest. Resolve os/architecture from the config blob.
        cfg = json.loads(Path(workdir, f"{slug}.image.json").read_text())
        if not isinstance(cfg, dict) or not cfg.get("architecture"):
            failures.append(
                f"{image}: is a single-platform manifest and its platform could not be resolved "
                f"from the config blob, so the baseline's platforms list cannot be verified."
            )
            platforms = []
        else:
            platforms = [platform_string({
                "os": cfg.get("os"),
                "architecture": cfg.get("architecture"),
                "variant": cfg.get("variant"),
            })]
    else:
        platforms = []
        for child in children:
            if (child.get("annotations") or {}).get(ATTESTATION) == "attestation-manifest":
                attestations += 1
                continue
            p = child.get("platform") or {}
            if p.get("os") == "unknown":
                continue
            platforms.append(platform_string(p))

    if sorted(platforms) != sorted(exp["platforms"]):
        failures.append(
            f"{image}: registry reports platforms {sorted(platforms)}, baseline records "
            f"{sorted(exp['platforms'])}."
        )

    if attestations != exp["attestations"]:
        failures.append(
            f"{image}: {attestations} buildx attestation manifest(s) at this digest, baseline "
            f"records {exp['attestations']}. Zero is not a finding in itself -- it is simply "
            f"less to verify against, and provenance proves origin, never benignity. A CHANGE "
            f"is the finding, because it means this is not the artefact that was reviewed."
        )

    # The ADR-055 trap, encoded. Gated only where it bites: a container CI runs on
    # ubuntu-latest, which is amd64. A local-only image is free to be arm64-only.
    runs_in_ci = any(o.startswith(".github/workflows/") for o in found.get(image, {}).get("occurrences", []))
    if runs_in_ci and "linux/amd64" not in platforms:
        failures.append(
            f"{image} is referenced from .github/workflows/ but linux/amd64 is not reachable at "
            f"this digest (platforms: {sorted(platforms)}). GitHub's ubuntu-latest runners are "
            f"amd64, so this pin will fail there with 'no matching manifest for linux/amd64'. "
            f"This is ADR-055's trap 2: take the top-level 'Digest:' from "
            f"'docker buildx imagetools inspect {image}:<tag>', never a per-platform child."
        )

if failures:
    print("=== DRIFT DETECTED ===")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

for image in sorted(expected):
    e = expected[image]
    print(f"  {image}: {e['mediaType']}")
    print(f"    platforms {sorted(e['platforms'])}, "
          f"{e['attestations']} attestation manifest(s) -- as recorded")
print("  no drift against the baseline")
PY

# ---------------------------------------------------------------------------------------------
echo ""
echo "=== Tag drift (reported, never gated) ==="

reported_any=0
while IFS=$'\t' read -r image digest tag; do
    [[ -n "$image" ]] || continue
    if [[ -z "$tag" ]]; then
        echo "  ${image}: tagless pin, nothing to compare"
        continue
    fi
    reported_any=1
    slug="${image//\//__}"
    if ! docker buildx imagetools inspect "${image}:${tag}" > "$workdir/${slug}.tag.txt" 2>&1; then
        echo "  ${image}:${tag}: could not resolve the tag -- reported, not failed. A tag being"
        echo "    deleted upstream is exactly the event a digest pin makes survivable."
        continue
    fi
    current="$(awk '/^Digest:/ { print $2; exit }' "$workdir/${slug}.tag.txt")"
    if [[ "$current" == "$digest" ]]; then
        echo "  ${image}:${tag} still resolves to the pinned digest"
    else
        echo "  ${image}:${tag} now resolves to ${current}"
        echo "    The pin is ${digest}, so nothing changed about what runs. A mutable tag moving"
        echo "    is ordinary upstream behaviour and is the whole reason the digest is here."
        echo "    Upgrading is a deliberate, reviewed edit -- and for sonarqube or the scanner it"
        echo "    invalidates the committed SonarQube baselines, so re-derive them in the same"
        echo "    commit. Never bump a pin to make this line quieter."
    fi
done < "$workdir/images.txt"
[[ "$reported_any" == "1" ]] || echo "  (every pin here is tagless, so there is no tag to drift)"

echo ""
echo "=== PASS: every pinned container is declared, correctly shaped and consistently recorded ==="
