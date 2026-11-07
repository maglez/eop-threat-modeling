import React, { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Card magnifier — a circular CSS lens that follows the pointer over a card and
 * scales the pixels beneath it (EOP-145).
 *
 * The lens is a *pixel* magnifier, not a re-render: it clips a scaled replica of
 * the card's own body to a circle. That is a deliberate limitation and worth
 * stating plainly, because it bounds what this feature can achieve — a card's
 * `threatPrompt` never reaches the visible DOM (it exists only in the card's
 * `aria-label`), so magnifying the card enlarges its art and rank glyphs but
 * does not surface threat text to a sighted player. If reading the scenario text
 * is the real requirement, that is a different story: render the prompt, then
 * magnify it.
 *
 * Accessibility: the lens is decoration over content that is already in the
 * accessibility tree via the card's `aria-label`, so it is `aria-hidden="true"`,
 * takes no focus, and sets `pointer-events: none` so it can never intercept the
 * click or drag that plays a card. Nothing about the keyboard path changes.
 */

/** Diameter of the lens including its ring, in CSS pixels. */
export const LENS_DIAMETER_PX = 100;

/** Width of the lens ring, in CSS pixels. Part of the offset arithmetic below. */
export const LENS_BORDER_PX = 2;

/**
 * Magnification factor. 4x at the user's request after playing with 2x — they
 * found 2x insufficient to read the card text. Higher zoom shows less context
 * per lens (a 100px lens at 4x covers 25px of the card), which is the
 * trade-off the user accepted.
 */
export const LENS_ZOOM = 4;

/** A point in the card container's border box, in CSS pixels. */
export interface LensPoint {
  readonly x: number;
  readonly y: number;
}

/** Handlers and state a card component wires up to show a lens. */
export interface CardMagnifierState {
  /** Where the lens is centred, or `null` when no lens is showing. */
  readonly point: LensPoint | null;
  /** Attach to the card's own container element — used for tap-outside dismissal. */
  readonly containerRef: React.MutableRefObject<HTMLDivElement | null>;
  /** Mouse tracking. Do not attach while a drag is in flight (see below). */
  readonly onPointerMove: (e: React.PointerEvent<HTMLElement>) => void;
  /** Dismisses an unpinned (mouse) lens. */
  readonly onPointerLeave: () => void;
  /** Touch and pen: first tap pins the lens, a second tap dismisses it. */
  readonly onPointerDown: (e: React.PointerEvent<HTMLElement>) => void;
  /** Dismisses the lens unconditionally, pinned or not. */
  readonly hide: () => void;
}

/**
 * Owns one card's lens state. Call it once per card — each card needs its own
 * container ref so that "tap outside" can tell this card from its neighbours.
 *
 * The magnifier is always on. It was originally gated by the build-time
 * `VITE_CARD_MAGNIFIER_ENABLED` flag (EOP-145), but that left the lens inert
 * in every run path that did not explicitly pass the build argument — a plain
 * `docker build` (the Dockerfile default is `false`) and `npm run dev` from a
 * `.env.local` without the entry — so players who built or ran the game in the
 * documented way never saw a lens on hover. Per `feature-flags.md` a stable
 * feature's flag is removed, so the handlers below are unconditional.
 *
 * @return the lens state and the handlers to wire onto the card container
 */
export function useCardMagnifier(): CardMagnifierState {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [point, setPoint] = useState<LensPoint | null>(null);
  const [pinned, setPinned] = useState(false);

  const hide = useCallback(() => {
    setPoint(null);
    setPinned(false);
  }, []);

  const onPointerMove = useCallback((e: React.PointerEvent<HTMLElement>) => {
    // Only a mouse tracks continuously. A touch pointer emits pointermove too,
    // but following a finger would put the lens underneath it where it cannot
    // be read, which is why touch pins instead (see onPointerDown).
    if (pinned || e.pointerType !== 'mouse') return;
    const box = e.currentTarget.getBoundingClientRect();
    setPoint({ x: e.clientX - box.left, y: e.clientY - box.top });
  }, [pinned]);

  const onPointerLeave = useCallback(() => {
    if (pinned) return;
    setPoint(null);
  }, [pinned]);

  const onPointerDown = useCallback((e: React.PointerEvent<HTMLElement>) => {
    if (e.pointerType === 'mouse') return;
    if (pinned) {
      hide();
      return;
    }
    const box = e.currentTarget.getBoundingClientRect();
    setPoint({ x: e.clientX - box.left, y: e.clientY - box.top });
    setPinned(true);
  }, [pinned, hide]);

  // A tap anywhere outside this card dismisses its pinned lens. Capture phase
  // matters: tapping a *second* card must dismiss the first one's lens before
  // that card's own React handler pins its own, otherwise two lenses show.
  useEffect(() => {
    if (!pinned) return undefined;
    const onDocumentPointerDown = (e: PointerEvent) => {
      const container = containerRef.current;
      if (container !== null && e.target instanceof Node && container.contains(e.target)) return;
      hide();
    };
    document.addEventListener('pointerdown', onDocumentPointerDown, true);
    return () => { document.removeEventListener('pointerdown', onDocumentPointerDown, true); };
  }, [pinned, hide]);

  return { point, containerRef, onPointerMove, onPointerLeave, onPointerDown, hide };
}

/** Geometry and content of the magnified replica. */
export interface CardLensProps {
  /** Lens centre, in the container's border box. */
  readonly point: LensPoint;
  /** The card container's border-box width in CSS pixels. */
  readonly width: number;
  /** The card container's border-box height in CSS pixels. */
  readonly height: number;
  /** The card container's padding in CSS pixels. */
  readonly padding: number;
  /** A copy of the card body. Must be the same JSX the card renders. */
  readonly children: React.ReactNode;
}

/**
 * The lens itself: a circle clipped out of a scaled replica of the card.
 *
 * The replica is offset so that the pixel under the pointer stays exactly under
 * the pointer at any zoom. With lens radius `r`, zoom `z`, ring width `b` and
 * pointer at `x`, the replica's left edge sits at `r - z*x - b` inside the
 * lens's padding box; the padding box itself sits at `x - r + b` in container
 * coordinates, so a container point `p` lands at `x + z*(p - x)` — which is `x`
 * exactly when `p == x`. The `- b` term is not cosmetic: absolutely positioned
 * children are placed against the padding box, so omitting it shifts the whole
 * magnified image by the ring width.
 *
 * Render this as the last child of the card container, which must be
 * `position: relative`.
 *
 * @param props the lens geometry and the replica content
 * @return the lens element
 */
export function CardLens({ point, width, height, padding, children }: CardLensProps): React.JSX.Element {
  const radius = LENS_DIAMETER_PX / 2;
  return (
    <div
      className="eop-card-lens"
      aria-hidden="true"
      style={{
        position: 'absolute',
        left: `${String(point.x - radius)}px`,
        top: `${String(point.y - radius)}px`,
        width: `${String(LENS_DIAMETER_PX)}px`,
        height: `${String(LENS_DIAMETER_PX)}px`,
        boxSizing: 'border-box',
        border: `${String(LENS_BORDER_PX)}px solid #0b0c0c`,
        borderRadius: '50%',
        overflow: 'hidden',
        backgroundColor: '#ffffff',
        boxShadow: '0 2px 8px rgba(0,0,0,0.35)',
        // The lens must never swallow the click or the pointer stream that plays
        // a card (EOP-79's drag depends on an uninterrupted pointer sequence).
        pointerEvents: 'none',
        // Above sibling cards, below the drag ghost at 1000.
        zIndex: 900,
      }}
    >
      <div
        style={{
          position: 'absolute',
          left: `${String(radius - LENS_ZOOM * point.x - LENS_BORDER_PX)}px`,
          top: `${String(radius - LENS_ZOOM * point.y - LENS_BORDER_PX)}px`,
          width: `${String(width)}px`,
          height: `${String(height)}px`,
          padding: `${String(padding)}px`,
          boxSizing: 'border-box',
          display: 'inline-flex',
          flexDirection: 'column',
          justifyContent: 'space-between',
          transform: `scale(${String(LENS_ZOOM)})`,
          transformOrigin: '0 0',
        }}
      >
        {children}
      </div>
    </div>
  );
}
