# PRD / Plan: Make `tools/switch-provider.sh --free-only` discover current free Go models

**Status:** Implemented (2026-11-13)
**Date:** 2026-11-13
**Author:** `@product-owner` (plan) / `@tech-lead` (implementation, commits `[EOP-000]`)
**Scope:** Developer tooling only — no application code, no Jira story (per Prompter directive).

---

## 1. Problem statement

`tools/switch-provider.sh` toggles which provider block is active in `.env` by moving `#` markers. When invoked with `go --free-only` (or `go-free`), it uncomments the `# === Go Free ===` block and comments the rest.

Two problems:

1. **The script does not discover free models.** It holds no model IDs and never consults the live Go catalogue (`https://opencode.ai/inference/go/v1/models`). It assumes the `# === Go Free ===` block in `.env` already contains the right IDs.
2. **That assumption is already false.** The block in `.env.example` lists `crouton-alpha-free`, `beam-free`, `iceman-contributor-free`. Per the live catalogue and official docs (2026-11-13), the only zero-cost Go models are `beam-free`, `iceman-contributor-free`, and `longcat-2.5-free`. `crouton-alpha-free` is a Console free-tier model, not a Go free model, so the block is stale and would switch to a model that is not actually free on the Go route.

Consequence: `go --free-only` can silently select a non-free (or unavailable) model, and there is no mechanism to keep the free list current as OpenCode adds/removes free models.

## 2. Current behaviour (as-is)

- `switch-provider.sh` parses args, validates markers exist, then runs `sed` to comment/uncomment `MODEL_` lines inside marker-delimited ranges.
- It prints a hard-coded note: "only 3 free Go models exist at the time of writing".
- It never performs network I/O.
- `.env.example`'s Go Free block is hand-maintained and has drifted (see §1).

## 3. Desired behaviour (to-be)

When the user runs `./tools/switch-provider.sh go --free-only` (or `go-free`):

1. The script **discovers** the current set of free Go models from the authoritative source (the live catalogue endpoint), filtered to models whose cost is zero on input, cached read, and output.
2. It **rewrites** the `# === Go Free ===` block's seven `MODEL_*` lines with those discovered IDs (repeating to fill seven tiers, deterministically), so the block reflects reality at switch time.
3. It **toggles** markers as before, so only the Go Free block is active.
4. It **warns** about known constraints: region requirements (Global regions), the training-data trade-off for contributor models, and that family separation between authors and review gates is not met with only three models.
5. It **fails loudly** if discovery fails (network error, parse error, zero free models found) rather than silently using a stale list — unless an explicit offline fallback is requested.

## 4. Proposed approach

### 4.1 Discovery source

- Primary: `https://opencode.ai/zen/go/v1/models` (JSON; `https://opencode.ai/inference/go/v1/models` returns identical data). Filter entries where `cost.text.input.price == 0`, `cost.text.input.cache.read == 0` (defaulting to `0` when absent), and `cost.text.output.price == 0`. Sort IDs lexicographically for determinism.
- No snapshot file is committed. `--offline` skips the fetch and uses whatever the `# === Go Free ===` block already contains (and prints a "may be stale" warning); every other invocation fails loudly if the fetch, schema validation, or filtering yields zero IDs.

### 4.2 Tier assignment

Free models are fewer than seven. Assign deterministically:

- Sort discovered IDs.
- For tier `MODEL_A..MODEL_G`, use `discovered[i % count]`.
- This mirrors the existing repeat pattern in `.env.example`.

### 4.3 Rewriting the block

- Locate the `# === Go Free ===` … `# === Bedrock ===` range.
- Replace only lines matching `^#?MODEL_[A-G]=` within that range with the seven `MODEL_X=opencode-go/<id>` assignments, preserving the block's existing commented/active state (a `#` prefix if the block is currently commented, none if active).
- Preserve all other lines (comments, blanks) and markers.
- Use a temp file under `.tmp/` + atomic `mv`; never partial-write `.env`.

### 4.4 Modes

- Default `go-free`: discover + rewrite + toggle.
- `--offline`: skip the fetch, use the existing block as-is (prints a "may be stale" warning); mutually exclusive with `--refresh`.
- `--refresh`: fetch and rewrite the `# === Go Free ===` block in `.env` only, no toggle.
- `--dry-run`: print the discovered IDs and the intended assignments (or the existing block under `--offline`), touch nothing.

### 4.5 Validation & safety

- Require `curl` and `jq` on PATH; fail with a clear message if absent.
- Validate JSON shape before using it; fail on unexpected schema.
- Refuse to rewrite if the discovered count is 0.
- Keep the "holds no model IDs" invariant? This plan **breaks** that invariant by design. Update `.env.example` comments, the Blueprint §3.4 note, and ADR-046/ADR-059 references to state that the Go Free block is now generated/refreshed by the script rather than hand-maintained. The Zen/Go/Bedrock blocks remain hand-maintained.

### 4.6 Documentation updates

- `.env.example`: replace the stale Go Free IDs with the current zero-cost set (`beam-free`, `iceman-contributor-free`, `longcat-2.5-free`, round-robin) and note that the block is regenerated live, so the snapshot may lag.
- Blueprint §3.4: update the "switch-provider holds no model IDs" statement to "holds no model IDs except the generated Go Free block", and document `--refresh`, `--offline`, `--dry-run`.

## 5. Risks & open questions

- **Network dependency in a dev tool.** Mitigate with `--offline` (uses the existing block).
- **Schema drift** in the catalogue endpoint. Mitigate with strict validation (`jq -e '.data | type == "array"'`) and a pinned field list; fail loudly.
- **Region restrictions.** Some free models require Global regions; the script does not parse the region list and instead the `.env.example`/Blueprint comments point at the docs to re-check.
- **Family separation.** With 3 models repeated across 7 tiers, DoD gates cannot be family-independent. The script's warning must remain. This is acceptable for exploration only.
- **Concurrency / partial writes.** Use temp file + atomic rename.

## 6. Acceptance criteria

1. `./tools/switch-provider.sh go --free-only` fetches the live catalogue, filters zero-cost models, and rewrites the Go Free block before toggling. ✅ verified on a `.tmp/` fixture.
2. The resulting `.env` Go Free block contains only IDs present in the live catalogue's zero-cost set. ✅ (`beam-free`, `iceman-contributor-free`, `longcat-2.5-free`).
3. If the network call fails and `--offline` is not given, the script exits non-zero with a clear error; it does not silently use stale data. ✅ (`discover_free_models` fails loudly).
4. `--dry-run` prints the intended assignments and modifies no file. ✅ verified.
5. `--refresh` rewrites the Go Free block and exits without toggling. ✅ verified (Bedrock stays active).
6. `--offline` uses the existing block (and warns it may be stale). ✅ verified.
7. Existing marker validation and toggle behaviour remain unchanged (regression). ✅ verified (switching back to `go` works).
8. Documentation (`.env.example`, Blueprint, PRD) updated to reflect the generated block. ✅

## 7. Delivery note

Delivered as a single change with `[EOP-000]` commit prefix and a PR (no Jira story, per the Prompter's directive that this is an engineering-system change, not a cards-game change). The optional bats harness (Story 4) was **not** added — the script is verified by manual fixture runs; it has no in-repo shell-test harness.

## 8. Out of scope

- Changing the Zen/Go/Bedrock blocks (remain hand-maintained).
- Probing models for DoD gate compliance (ADR-046) — unchanged.
- Any application/Java code.

---

**Next step:** None — implemented. See the `[EOP-000]` commit and PR.
