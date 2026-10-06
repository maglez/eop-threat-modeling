# ADR-075: LSP language server support is project-scoped, with jdtls pre-seeded and pinned rather than auto-downloaded

**Status:** Accepted (2026-10-05)

**Date:** 2026-10-05

**Deciders:** @tech-lead

## Context

OpenCode's LSP support is enabled by `"lsp": true` in `opencode.json`, which turns on every built-in language server. Two properties of that default make it a poor fit for this repository.

**The built-in Java server is `jdtls` (Eclipse JDT Language Server), and opencode auto-downloads it.** The download is guarded by an existence check on `~/.opencode/bin/jdtls/plugins`; if that directory is absent, opencode fetches `https://www.eclipse.org/downloads/download.php?file=/jdtls/snapshots/jdt-language-server-latest.tar.gz` — a `snapshots/` channel, a filename of `latest` — then `tar -xzf` and execute. There is **no checksum, no signature and no version** between download and run. The same is true of the `bash`, `yaml` and `terraform` built-ins, which auto-download `bash-language-server`, `yaml-language-server` and `terraform-ls` from their respective release channels.

This repository's supply-chain story is deliberately narrow and heavily gated: 29 GitHub Actions `uses:` references pinned to 40-hex commit SHAs ([ADR-073](ADR-073-supply-chain-coverage-for-actions-and-browsers.md)), six digest-pinned containers ([ADR-064](ADR-064-pinned-container-audit-coverage.md)), seven pinned OpenCode plugins, and a CVE advisory allowlist with reachability traces ([ADR-050](ADR-050-dependency-cve-scanning.md)). A blanket `"lsp": true` would introduce four binaries with **no baseline, no audit step and no integrity check**, into exactly the mechanism three ADRs hardened. The `typescript` and `eslint` servers are the exception — both are already pinned in `ui/package-lock.json` and CVE-scanned, so enabling them is free.

**`jdtls` is the only Java language server opencode offers.** There is no JetBrains/IntelliJ server in the built-in list. This is not a limitation of the preference for IntelliJ as a *human* IDE — the LSP server is not the editor, and enabling it does not switch the human's editor. IntelliJ's own LSP support runs the opposite direction (plugins *inside* the IDE), so it is not a substitute here. The `lsp` object accepts a custom `{command: [...]}` entry if a third-party server is ever wanted, but no official JetBrains headless Java LSP exists to point it at.

## Decision

Enable LSP through a **scoped `lsp` object**, not `"lsp": true`. The object is a per-server override map: unlisted servers keep their built-in default (enabled when their prerequisites are met), and listed servers are either disabled or given a custom command.

```json
"lsp": {
  "bash": { "disabled": true },
  "yaml": { "disabled": true },
  "terraform": { "disabled": true }
}
```

- **`typescript` and `eslint`** are left unlisted, so they start at their built-in defaults. Both are already pinned in `ui/package-lock.json` and CVE-scanned.
- **`jdtls`** is left unlisted, so it starts at its built-in default — but its binary is **pre-seeded** by `tools/jdtls/install-jdtls.sh` so the auto-download never runs.
- **`bash`, `yaml`, `terraform`** are disabled, which suppresses their unpinned auto-downloads. The repository has 17 `.sh`, 14 `.yml` and 6 `.tf` files, but no gate depends on LSP diagnostics for any of them, and `oxlint` is absent from `ui/package.json` so that server would not start anyway.

`permission.lsp` is set to `"allow"`. The four `expert-*` advisers carry a `"*": deny` catch-all, so they do not receive LSP — correct for advisory agents that only read and grep, and a decision made explicitly rather than inherited.

### The pre-seed

`tools/jdtls/install-jdtls.sh` downloads a **pinned, SHA-256-verified** build of `jdtls` 1.61.0 from Eclipse's `milestones/` channel (not `snapshots/`), verifies the checksum against a literal in the script *before* untarring, and installs to `~/.opencode/bin/jdtls/` — the existence check opencode runs before downloading. The script is idempotent (no-op at the pinned version, `--force` to reinstall) and fails closed on a checksum mismatch. The version, URL and SHA-256 are three adjacent variables at the top of the script; `SETUP.md` and this ADR cite the script rather than restating the hash, so there is one source of truth.

### Diagnostics are advisory

`AGENTS.md` records that LSP diagnostics are **not** authoritative. For Java, `./mvnw verify` (Checkstyle, SpotBugs, Javadoc, JaCoCo) is the source of truth. `jdtls` uses m2e's view of the classpath, which can diverge from Maven's effective view — most sharply through the enforcer's dependency-convergence rule and the Boot 4.1 parent-managed tree. An authoritative-looking wrong diagnostic is an edit input for the agent system, not noise, so the rule is never to "fix" a Java file to satisfy a `jdtls` diagnostic without confirming against `./mvnw verify`.

## Consequences

### The pin is a rot liability

The `jdtls` pin is a rot liability in the same sense as the four CVE-driven version overrides in [ADR-050](ADR-050-dependency-cve-scanning.md): it will go stale, and nothing automated will say so. The upstream `.sha256` file is published beside the tarball, so a future bump is a three-line change to the script (version, artifact, hash) plus a re-run. Recorded as a known cost rather than a hidden one.

### The pre-seed depends on a reverse-engineered existence check

The mechanism relies on opencode checking `~/.opencode/bin/jdtls/plugins` before downloading. That check was read out of the installed binary, not documented. If a future OpenCode release changes the path or the check, the pre-seed silently stops working and the auto-download returns. The fallback is a custom `lsp` entry with an explicit `command` pointing at the pinned install — documented config, more robust, and not dependent on reading minified code. The behavioural verification (a fresh `opencode run` with a `src/main/java` file open, confirming no download occurred) is the only proof the pre-seed works, per Blueprint §7.8.

### Two classpaths, not zero

The original objection to `jdtls` — that it would not resolve the classpath on a Maven project without Eclipse `.project`/`.classpath` files — was **wrong on both halves**, and the correction is recorded because the error was generalised from "Eclipse tooling" rather than read out of what opencode does. `jdtls`'s root resolver does `M.findUp("pom.xml", ...)` with multi-module awareness, so `pom.xml` alone is enough; and `jdtls` bundles M2Eclipse, which embeds its own Maven runtime, so `mvn` not on `PATH` is irrelevant. This repository has no Lombok and no `<annotationProcessorPaths>`, which removes the most common m2e-vs-Maven classpath divergence. The residual risk is m2e's view disagreeing with Maven's effective view — which is why diagnostics stay advisory.

### No warm cache

opencode's `jdtls` launch does `mkdtemp(…/opencode-jdtls-data)` per start, so every session re-imports the full Spring Boot 4.1 tree into a fresh Eclipse workspace, plus a second JVM. Expect a slow first launch. This is inherent to opencode's `jdtls` integration and is not fixed by pre-seeding.

## Related

- [ADR-073](ADR-073-supply-chain-coverage-for-actions-and-browsers.md) — GitHub Actions supply-chain coverage, the third audited population
- [ADR-064](ADR-064-pinned-container-audit-coverage.md) — digest-pinned container audit
- [ADR-050](ADR-050-dependency-cve-scanning.md) — CVE scanning and the rot-liability precedent for pinned overrides
- [ADR-022](ADR-022-agent-model-tier-governance.md) — model tier governance and the `expert-*` advisers' read-only posture
- `EOP-000` — the engineering-system reference for this change
