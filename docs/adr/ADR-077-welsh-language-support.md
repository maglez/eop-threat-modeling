# ADR-077: Welsh language support — i18n approach and scope

**Status:** Accepted (2026-11-27)

**Date:** 2026-11-27

**Deciders:** @tech-lead, @architecture-guardian, @product-owner

## Context

The EoP cards site is a single-origin SPA (React 18 + Vite + GOV.UK Frontend CSS, served by Caddy) with all user-facing prose hardcoded in the components (`ui/src/components/*.tsx`, `ui/src/App.tsx`). There is no i18n infrastructure, no language switching, and no Welsh content. The product owner wants the application offered in Welsh (Cymraeg) to match the intended UK-site context.

Welsh has six CLDR plural forms (zero/one/two/few/many/other) and Welsh ordinals differ from English (1af, 2il, 3ydd, 4ydd, …). The app has roughly 120 user-facing strings, most of which are simple labels, but player counts and similar quantities are pluralised.

## Decision

### 1. i18n library: adopt `react-i18next` + `i18next`

Use `react-i18next`/`i18next` for runtime translation, interpolation and pluralisation. The dependency is a production dependency and therefore must:

- be added to `ui/package.json` and `ui/package-lock.json` in the same commit;
- pass `npm audit` and the Trivy CVE scan over `ui/package-lock.json` (ADR-050);
- be recorded in the project's supply-chain posture (no new HIGH/CRITICAL advisory without a traced allowlist entry).

### 2. Language is a runtime choice, not a build-time flag

Per ADR-037, front-end flags are build-time `VITE_*` variables. **Language must be runtime**: a build-time Welsh bundle would require two deploys and offer no in-page switch, which contradicts "offer the application in Welsh". Implementation: a `LanguageProvider` (React context) exposing `locale`, `setLocale` and `t(key, params)`, placed in `ui/src/main.tsx` wrapping `<App />`, persisted to `localStorage` (`eop_locale`) and applied on mount.

The *feature* (the toggle and the Welsh catalogue) is still gated behind `VITE_WELSH_LANGUAGE_ENABLED` (ADR-037) while it ships, defaulting `false`, so the change is shippable dark. The selected language is runtime; the feature flag is build-time. These are different mechanisms and must not be conflated (see ADR-013 / ADR-037).

### 3. Scope v1: UI chrome only

- **In scope (v1):** all buttons, headings, labels, hints, validation messages, error summaries, aria labels, live-region announcements, ordinal suffixes, suit display labels, `cardImagePath` alt text.
- **Out of scope (v1):** card threat prompts (`card.threatPrompt`) — these are Microsoft's licensed English text. They stay English and are wrapped in `<span lang="en">` so assistive tech pronounces them correctly on a Welsh UI. A Welsh deck is a separate content/licensing story (phase 3).
- **Out of scope (v1):** backend RFC 9457 error `title`/`detail` strings — deferred to phase 2 (`EOP-267`), via `MessageSource` + `Accept-Language` in `GlobalExceptionHandler`.
- **Out of scope (v1):** URL-based language state. Persistence is `localStorage` + an in-page toggle; no `?lang=` parameter.

### 4. Persistence and default

- Store the choice in `localStorage` under key `eop_locale`.
- Default on first visit: `navigator.language` starting with `cy` → `cy`, otherwise `en`.
- Missing keys in `cy.json` fall back to English, so the Welsh catalogue can land incrementally.

### 5. Accessibility contract

- `<html lang>` flips to `cy`/`en` reactively (currently static `lang="en"` in `ui/index.html`); `document.title` and the meta description follow.
- The GOV.UK skip link moves out of `index.html` into the React tree so it is translatable.
- The language toggle sits in the GOV.UK header, pattern "English | Cymraeg", with the current language not a link; keyboard-accessible with `aria-current`.
- English card prompts and proper nouns are wrapped in `<span lang="en">` on a Welsh UI.
- Ordinals use a `formatOrdinal(n, locale)` helper with a Welsh branch (1af, 2il, 3ydd, 4ydd, …) or, if complexity is judged too high, numeric positions ("1.", "2.") — the decision is recorded in the implementation story.

### 6. STRIDE labels and suit labels

- `GameOverScreen.STRIDE_LABELS` and `GameScreen.SUIT_LABELS` are STRIDE category names. They are translated with consistent terminology (a glossary is part of the Welsh catalogue story).
- One-letter suit labels (S/T/R/I/D/E) and `SUIT_COLOURS` stay language-neutral.

## Alternatives considered

- **Zero-dependency custom `LanguageContext`** reading two JSON dictionaries: rejected for the plural-handling risk. Welsh has six plural forms and hand-rolling plural selection is the one place a silent bug is likely. The modest string count (~120) would make the custom route viable for simple labels, but pluralised strings (player counts, attempts) are exactly where correctness matters. `i18next` ships the CLDR rules.
- **Build-time Welsh bundle (two deploys)**: rejected — no in-page switch, contradicts the product requirement, and would force a redeploy to change language.
- **Backend error localisation in v1**: rejected — exceptions carry pre-formatted English strings; refactoring them to message keys + args is a non-trivial change to exception construction and `GlobalExceptionHandlerTest`. Deferred to phase 2.
- **Translating card threat prompts in v1**: rejected — licensed content; a content/licensing decision, not an engineering one.

## Consequences

- **New production dependency**: `react-i18next` + `i18next` in `ui/package.json`, Trivy-clean and npm-audit-clean, pinned in `ui/package-lock.json`.
- **No backend change in v1**: `./mvnw verify` (Java scope) is unaffected; only the front-end Sonar ratchet (`tools/sonar/sonar-ui-report.json`) must not rise, requiring a rescan after the extraction.
- **Feature flag**: `VITE_WELSH_LANGUAGE_ENABLED` added to `ui/src/vite-env.d.ts` and wired through `ui/Dockerfile`, `.github/workflows/ci.yml`, `scripts/update-local.sh`, `ui/.env.example` per ADR-037. Default `false`.
- **No hexagonal impact**: the change is confined to the UI adapter layer (`ui/src/`) plus documentation. No domain, application or outbound adapter code is touched in v1; phase 2 touches `GlobalExceptionHandler` (interface adapter layer).
- **Docs gates**: `npm run verify` in `ui/` must stay green; the extraction step (`EOP-263`) is done in isolation and verified before any Welsh content lands.
- **Phase 3 (not in this epic)**: Welsh card content, subject to licensing review.

## References

- ADR-009: Front-end React + TypeScript (amended)
- ADR-013: Feature flags (back-end `@ConditionalOnProperty`)
- ADR-037: Front-end build-time feature flags (`VITE_*`)
- ADR-045: Front-end response validation
- ADR-050: Dependency CVE scanning
- `EOP-261`: Welsh language support epic
- `EOP-262` … `EOP-267`: implementation stories
