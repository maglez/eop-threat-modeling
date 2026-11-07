import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameScreen } from './GameScreen';
import { LENS_BORDER_PX, LENS_DIAMETER_PX, LENS_ZOOM } from './CardMagnifier';
import * as api from '../api';

/**
 * EOP-145 — the card magnifier lens.
 *
 * These tests drive the lens through GameScreen rather than through the hook in
 * isolation, because the wiring is the part that can break: which pointer events
 * each surface subscribes to, whether a drag suppresses the lens, and whether a
 * disabled card still magnifies. A hook test would pass with all of that wrong.
 *
 * The lens is always on: the former VITE_CARD_MAGNIFIER_ENABLED flag was
 * retired because it left the lens inert in every run path that did not pass
 * the build argument. No env stub is needed — and deliberately none is set
 * here, so these tests prove the lens appears with no flag configured at all.
 */
describe('CardMagnifier', () => {
  const mockSession: api.SessionStateDto = {
    sessionId: 'test-session',
    joinCode: 'ABC12345',
    status: 'IN_PROGRESS',
    players: [
      { playerId: 'player1', displayName: 'Alice', seatOrder: 0, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
      { playerId: 'player2', displayName: 'Bob', seatOrder: 1, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
      { playerId: 'player3', displayName: 'Charlie', seatOrder: 2, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
    ],
    createdAt: '2023-01-01T00:00:00Z',
    updatedAt: '2023-01-01T00:00:00Z',
  };

  const defaultProps = {
    sessionId: 'test-session',
    playerId: 'player1',
    playerToken: 'test-token',
    session: mockSession,
    onSessionEnd: vi.fn(),
  };

  const spoofingKing: api.CardDto = {
    cardId: 'card1',
    suit: 'SPOOFING',
    rank: 'KING',
    rankSymbol: 'K',
    rankValue: 13,
    threatPrompt: 'An attacker impersonates a user',
  };

  const tamperingQueen: api.CardDto = {
    cardId: 'card2',
    suit: 'TAMPERING',
    rank: 'QUEEN',
    rankSymbol: 'Q',
    rankValue: 12,
    threatPrompt: 'An attacker modifies data in transit',
  };

  const idleTrickState: api.TrickStateDto = {
    complete: false,
    handComplete: false,
    seatToPlay: 0,
  };

  /** Returns the lens element, or null when no lens is rendered. */
  const lens = (): HTMLElement | null => document.querySelector('.eop-card-lens');

  const stubApi = (hand: api.CardDto[], trickState: api.TrickStateDto = idleTrickState): void => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue({
      handId: 'hand1',
      playerId: 'player1',
      cardCount: hand.length,
      cards: hand,
    });
    vi.spyOn(api, 'getTrickState').mockResolvedValue(trickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);
  };

  /** Resolves once the hand has rendered, so the card is present to hover. */
  const waitForHand = async (): Promise<HTMLElement> => {
    await waitFor(() => {
      expect(screen.getByRole('group', { name: 'Your hand' })).toBeInTheDocument();
    });
    const [card] = screen.getAllByRole('button', { name: /of spoofing/ });
    if (card === undefined) throw new Error('the hand rendered no spoofing card to hover');
    return card;
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  describe('the lens', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_GAME_SCREEN_ENABLED', 'true');
    });

    it('shows a lens when the mouse moves over a hand card', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      expect(lens()).toBeNull();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      const shown = lens();
      expect(shown).not.toBeNull();
      // Literals, not the imported constants. Asserting against LENS_DIAMETER_PX
      // would make this test a tautology: changing the constant would change both
      // the rendered value and the expectation, and the suite would stay green.
      // jsdom's getBoundingClientRect is all zeroes, so the point is the raw
      // client coordinate and the lens is centred on it: 40 - 100/2, 60 - 100/2.
      expect(shown?.style.left).toBe('-10px');
      expect(shown?.style.top).toBe('10px');
      expect(shown?.style.width).toBe('100px');
      expect(shown?.style.height).toBe('100px');
    });

    it('pins the agreed lens dimensions, so a change to them is a deliberate act', () => {
      // The user asked for twice the original 2x magnification after playing
      // with it, so the lens is now 4x. Every other geometry assertion in this
      // file uses literals, so this is the one place a reader can see the
      // agreed numbers, and the one place that fails if somebody edits them
      // without revisiting the story.
      expect(LENS_ZOOM).toBe(4);
      expect(LENS_DIAMETER_PX).toBe(100);
      expect(LENS_BORDER_PX).toBe(2);
    });

    it('removes the lens when the pointer leaves the card', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      fireEvent.pointerLeave(card);

      expect(lens()).toBeNull();
    });

    it('magnifies the pixels beneath it at the agreed zoom factor', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      const replica = lens()?.firstElementChild as HTMLElement | undefined;
      expect(replica?.style.transform).toBe('scale(4)');
      expect(replica?.style.transformOrigin).toBe('0 0');
      // The replica offset is the actual lens maths, and it is the one thing a
      // sign error would break invisibly. An absolutely positioned child is laid
      // out against the PADDING box, which sits one border width inside the lens
      // box, so the offset carries a - LENS_BORDER_PX correction:
      //   left = radius - zoom * x - border = 50 - 4*40 - 2 = -112
      //   top  = radius - zoom * y - border = 50 - 4*60 - 2 = -192
      // Drop the border term and the whole magnified image shifts by the ring
      // width, which is exactly the bug these two lines exist to catch.
      expect(replica?.style.left).toBe('-112px');
      expect(replica?.style.top).toBe('-192px');
    });

    it('keeps the lens out of the accessibility tree and out of the tab order', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      const shown = lens();
      expect(shown?.getAttribute('aria-hidden')).toBe('true');
      expect(shown?.style.pointerEvents).toBe('none');
      // Nothing inside the lens may be reachable by keyboard: the lens is a copy
      // of a card that is already in the tab order, so a focusable duplicate
      // would announce every card twice.
      expect(shown?.querySelectorAll('[tabindex], button, a, input')).toHaveLength(0);
    });

    it('magnifies a card the player may not play yet', async () => {
      // Seat 1 is Bob's, so Alice's cards are disabled — but she must still be
      // able to read them while she waits (EOP-145 scenario 6).
      stubApi([spoofingKing], { complete: false, handComplete: false, seatToPlay: 1 });
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      expect(card.getAttribute('aria-disabled')).toBe('true');

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      expect(lens()).not.toBeNull();
    });

    it('shows a lens over a card already played into the trick', async () => {
      const trickState: api.TrickStateDto = {
        complete: false,
        handComplete: false,
        seatToPlay: 1,
        trick: {
          trickId: 'trick1',
          sequence: 1,
          leaderSeat: 1,
          ledSuit: 'TAMPERING',
          plays: [{
            trickPlayId: 'play1',
            playerId: 'player2',
            seatOrder: 1,
            card: tamperingQueen,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }],
        },
      };
      stubApi([spoofingKing], trickState);
      render(<GameScreen {...defaultProps} />);

      const played = await waitFor(() => screen.getByAltText('Q of tampering'));
      const trickCard = played.parentElement;
      expect(trickCard).not.toBeNull();

      fireEvent.pointerMove(trickCard as HTMLElement, { pointerType: 'mouse', clientX: 20, clientY: 30 });

      expect(lens()).not.toBeNull();
    });

    it('pins the lens on a touch tap and releases it on a second tap', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      // A pinned lens survives the pointer leaving — a finger lifts off, it does
      // not hover away.
      fireEvent.pointerLeave(card);
      expect(lens()).not.toBeNull();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      expect(lens()).toBeNull();
    });

    it('releases a pinned lens when the player taps somewhere else', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      fireEvent.pointerDown(document.body, { pointerType: 'touch', clientX: 400, clientY: 400 });

      expect(lens()).toBeNull();
    });

    it('still selects the card when a touch tap magnifies it', async () => {
      // Tap-to-select is the WCAG 2.2 SC 2.5.7 single-pointer alternative to
      // dragging. The magnifier must not consume the tap.
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      fireEvent.click(card);

      expect(card.getAttribute('aria-pressed')).toBe('true');
      expect(screen.getByRole('button', { name: 'Play selected card' })).toBeInTheDocument();
    });

    it('hides the lens when a mouse drag begins', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      // setPointerCapture would otherwise route every subsequent pointermove to
      // this card, dragging the lens across the table with the ghost.
      fireEvent.pointerDown(card, { pointerType: 'mouse', clientX: 40, clientY: 60, pointerId: 1 });

      expect(lens()).toBeNull();
    });

    it('ignores a moving touch pointer, which would fight the tap-to-pin gesture', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      // A finger dragged across the hand emits pointermove just as a mouse does.
      // Tracking it would make the lens follow the finger instead of pinning
      // where the player tapped, so onPointerMove is guarded on pointerType.
      fireEvent.pointerMove(card, { pointerType: 'touch', clientX: 40, clientY: 60 });

      expect(lens()).toBeNull();
    });

    it('removes its document listener when a card unmounts with the lens pinned', async () => {
      stubApi([spoofingKing]);
      const addSpy = vi.spyOn(document, 'addEventListener');
      const removeSpy = vi.spyOn(document, 'removeEventListener');
      const { unmount } = render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      // The tap-outside dismissal is a capture-phase document listener. If the
      // effect's cleanup were missing, unmounting a pinned card would leak one
      // listener per card per session and keep a dead component's state alive.
      const added = addSpy.mock.calls.filter((call) => call[0] === 'pointerdown' && call[2] === true);
      expect(added.length).toBeGreaterThan(0);

      unmount();

      const removed = removeSpy.mock.calls.filter((call) => call[0] === 'pointerdown' && call[2] === true);
      expect(removed.length).toBe(added.length);
    });
  });
});
