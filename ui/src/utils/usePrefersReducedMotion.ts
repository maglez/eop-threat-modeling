import { useEffect, useState } from 'react';

/** Media query for the operating system's "reduce motion" preference. */
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/**
 * Reports whether the user has asked the operating system to minimise animation.
 *
 * The preference is read through {@link window.matchMedia} rather than a CSS
 * media query because two of the animated properties here — the hand card's
 * `transition` and the drag ghost's `transform` — are applied as inline styles,
 * which a stylesheet cannot override without `!important`. A hook is the only
 * way to reach them.
 *
 * The subscription matters as much as the initial read: the setting can be
 * toggled in system preferences while the app is open, and a value captured at
 * module load would be stale for the rest of the session.
 *
 * @return `true` when the user prefers reduced motion
 */
export function usePrefersReducedMotion(): boolean {
  const [prefersReducedMotion, setPrefersReducedMotion] = useState<boolean>(
    () => window.matchMedia(REDUCED_MOTION_QUERY).matches,
  );

  useEffect(() => {
    const query = window.matchMedia(REDUCED_MOTION_QUERY);
    // Syncs the state with the live query in case the initialiser read a value
    // that changed between render and effect.
    setPrefersReducedMotion(query.matches);

    const handleChange = (event: MediaQueryListEvent): void => {
      setPrefersReducedMotion(event.matches);
    };

    query.addEventListener('change', handleChange);
    return () => {
      query.removeEventListener('change', handleChange);
    };
  }, []);

  return prefersReducedMotion;
}
