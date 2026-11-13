# ADR-076: Respect prefers-reduced-motion on the game screen via a React hook plus scoped CSS media query

**Status:** Accepted (2026-11-13)

**Date:** 2026-11-13

**Deciders:** @tech-lead, @architecture-guardian

## Context

The Chrome Modern Web Guidance v1 audit (EOP-260) found that the game screen animated eight motion sites with no regard for the operating system's "reduce motion" preference: the `.eop-card` hover/selected lift and its transition, plus three inline styles in `GameScreen.tsx` (card face transition, trick drop-zone transition, drag-ghost rotation). No `@media (prefers-reduced-motion: reduce)` block existed anywhere in the codebase.

The root cause is structural: the animated properties are split across two surfaces. The card's lift and transition live in `GameScreen.css`, which a stylesheet media query *can* reach. The card face's `transition`, the drop zone's `transition` and the drag ghost's `transform` are applied as inline styles, which a stylesheet media query cannot override without `!important` — and `!important` in a media query would fight the component's own inline style rather than cooperate with it.

## Decision

Use a **two-part approach**:

1. **Stylesheet media query** in `GameScreen.css` for the CSS-owned motion: an `@media (prefers-reduced-motion: reduce)` block on `.eop-card`, `.eop-card:hover` and `.eop-card-selected` sets `transition: none; transform: none`. The disabled variants need no override — they already pin `transform: none !important`.

2. **React hook** (`ui/src/utils/usePrefersReducedMotion.ts`) for the inline-style motion: reads `(prefers-reduced-motion: reduce)` through `window.matchMedia` and subscribes to its `change` event so the value stays live if the user toggles the OS preference while the app is open. `GameScreen.tsx` calls the hook in `CardFace` and `GameScreen` and substitutes `'none'` for the three inline styles when the preference is set.

The `.eop-card` transition was also scoped from `transition: all 0.2s ease` to `transition: transform 0.2s ease, box-shadow 0.2s ease`, so that future property changes do not animate by default.

## Alternatives considered

- **CSS-only (`!important` media query)**: rejected because it would override inline styles with a specificity fight rather than a cooperative gate, and would not be testable through the component's rendered output.
- **A global context provider**: rejected as over-engineering for a single boolean consumed in two components; a hook is the smallest seam that works.
- **Reading the preference once at module load**: rejected because a value captured at load is stale for the rest of the session if the user toggles the setting.

## Consequences

- **Accessibility**: users who prefer reduced motion no longer see the card lift, the transitions, or the drag-ghost tilt. The remaining colour and box-shadow changes still carry the state.
- **Testability**: the hook is unit-tested in isolation (4 tests) and exercised through the real component with `window.matchMedia` stubbed (4 tests), proving the gate is wired rather than merely present.
- **jsdom gap**: jsdom has no `matchMedia` implementation, so `ui/src/setupTests.ts` gained a `matchMedia` shim reporting `matches: false`. This is the same pattern as the existing `setPointerCapture` shim.
- **No feature flag**: this is a user-preference-driven behaviour change, not an experimental feature, so it ships enabled. The preference is read at runtime from the OS, not from a build-time variable.
- **No hexagonal impact**: the change is confined to the UI adapter layer (`ui/src/`); no domain, application or outbound adapter code is touched.
