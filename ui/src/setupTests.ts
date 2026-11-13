import "@testing-library/jest-dom/vitest";

// jsdom implements neither `PointerEvent` nor the pointer-capture methods, while every
// browser we support implements both. Without these shims `fireEvent.pointerDown` and
// friends fall all the way back to a bare `Event`, which silently drops `clientX` /
// `clientY` / `pointerId` from the init object — so any handler doing hit-testing against
// `getBoundingClientRect()` compares `undefined` and fails for reasons that have nothing
// to do with the code under test. Shimming here rather than per test keeps the whole suite
// behaving like a browser, and keeps the deception surface small: these are the only two
// gaps papered over, and both are genuine jsdom omissions rather than product behaviour.

if (typeof window.PointerEvent === "undefined") {
    class PointerEventShim extends MouseEvent {
        public readonly pointerId: number;

        public readonly pointerType: string;

        public readonly isPrimary: boolean;

        public constructor(type: string, params: PointerEventInit = {}) {
            super(type, params);
            this.pointerId = params.pointerId ?? 0;
            this.pointerType = params.pointerType ?? "mouse";
            this.isPrimary = params.isPrimary ?? true;
        }
    }

    window.PointerEvent = PointerEventShim as unknown as typeof window.PointerEvent;
}

if (typeof Element.prototype.setPointerCapture === "undefined") {
    Element.prototype.setPointerCapture = function setPointerCapture(): void {
        /* no-op: jsdom has no pointer-capture implementation to delegate to */
    };
    Element.prototype.releasePointerCapture = function releasePointerCapture(): void {
        /* no-op */
    };
    Element.prototype.hasPointerCapture = function hasPointerCapture(): boolean {
        return false;
    };
}

// jsdom implements neither `matchMedia` nor a media-query engine, while every
// browser we support implements both. `usePrefersReducedMotion` reads it on
// render and subscribes to it in an effect, so without this shim the hook throws
// and the whole suite fails for a reason that has nothing to do with the
// component under test. The shim reports `matches: false` — the default path —
// and a test that needs the reduced-motion path replaces `window.matchMedia`
// itself. Same reasoning as the two shims above: a genuine jsdom omission,
// papered over once rather than per test.
if (typeof window.matchMedia === "undefined") {
    window.matchMedia = function matchMedia(query: string): MediaQueryList {
        return {
            matches: false,
            media: query,
            onchange: null,
            addListener: (): void => {
                /* no-op: jsdom has no media-query engine to notify */
            },
            removeListener: (): void => {
                /* no-op */
            },
            addEventListener: (): void => {
                /* no-op */
            },
            removeEventListener: (): void => {
                /* no-op */
            },
            dispatchEvent: (): boolean => false,
        };
    };
}
