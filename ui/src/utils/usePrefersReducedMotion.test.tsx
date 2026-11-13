import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePrefersReducedMotion } from './usePrefersReducedMotion';

/**
 * A minimal `MediaQueryList` stand-in. jsdom has no media-query engine, so the
 * hook's subscription is exercised against this object rather than a real one.
 *
 * @param matches the preference the query should report
 * @param listeners collects the `change` handlers so a test can fire them
 * @return a `MediaQueryList` shaped object
 */
function makeQuery(
  matches: boolean,
  listeners: Array<(event: MediaQueryListEvent) => void>,
): MediaQueryList {
  return {
    matches,
    media: '(prefers-reduced-motion: reduce)',
    onchange: null,
    addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void): void => {
      listeners.push(listener);
    },
    removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void): void => {
      const index = listeners.indexOf(listener);
      if (index >= 0) {
        listeners.splice(index, 1);
      }
    },
    addListener: (): void => {
      /* no-op: deprecated API, unused by the hook */
    },
    removeListener: (): void => {
      /* no-op: deprecated API, unused by the hook */
    },
    dispatchEvent: (): boolean => false,
  } as unknown as MediaQueryList;
}

describe('usePrefersReducedMotion', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('reports false when the OS preference is no-preference', () => {
    const listeners: Array<(event: MediaQueryListEvent) => void> = [];
    vi.spyOn(window, 'matchMedia').mockReturnValue(makeQuery(false, listeners));

    const { result } = renderHook(() => usePrefersReducedMotion());

    expect(result.current).toBe(false);
  });

  it('reports true when the OS preference is reduce', () => {
    const listeners: Array<(event: MediaQueryListEvent) => void> = [];
    vi.spyOn(window, 'matchMedia').mockReturnValue(makeQuery(true, listeners));

    const { result } = renderHook(() => usePrefersReducedMotion());

    expect(result.current).toBe(true);
  });

  it('updates when the preference changes while the app is open', () => {
    const listeners: Array<(event: MediaQueryListEvent) => void> = [];
    vi.spyOn(window, 'matchMedia').mockReturnValue(makeQuery(false, listeners));

    const { result } = renderHook(() => usePrefersReducedMotion());
    expect(result.current).toBe(false);

    act(() => {
      for (const listener of listeners) {
        listener({ matches: true } as MediaQueryListEvent);
      }
    });

    expect(result.current).toBe(true);
  });

  it('unsubscribes when unmounted', () => {
    const listeners: Array<(event: MediaQueryListEvent) => void> = [];
    vi.spyOn(window, 'matchMedia').mockReturnValue(makeQuery(false, listeners));

    const { unmount } = renderHook(() => usePrefersReducedMotion());
    expect(listeners).toHaveLength(1);

    unmount();

    expect(listeners).toHaveLength(0);
  });
});
