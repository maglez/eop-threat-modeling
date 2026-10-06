import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameScreen } from './GameScreen';
import * as api from '../api';

// Mock the ErrorSummary component
vi.mock('./ErrorSummary', () => ({
  ErrorSummary: ({ title, errors }: { title: string; errors: string[] }) => (
    <div data-testid="error-summary">
      <h2>{title}</h2>
      <ul>
        {errors.map((error) => (
          <li key={error}>{error}</li>
        ))}
      </ul>
    </div>
  )
}));

describe('GameScreen', () => {
  const mockSession: api.SessionStateDto = {
    sessionId: 'test-session',
    joinCode: 'ABC123',
    status: 'IN_PROGRESS',
    players: [
      { playerId: 'player1', displayName: 'Alice', seatOrder: 0, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
      { playerId: 'player2', displayName: 'Bob', seatOrder: 1, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
      { playerId: 'player3', displayName: 'Charlie', seatOrder: 2, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' }
    ],
    createdAt: '2023-01-01T00:00:00Z',
    updatedAt: '2023-01-01T00:00:00Z'
  };

  const defaultProps = {
    sessionId: 'test-session',
    playerId: 'player1',
    playerToken: 'test-token',
    session: mockSession,
    onSessionEnd: vi.fn()
  };

  const makeHand = (cards: api.CardDto[]): api.HandDto => ({
    handId: 'hand1',
    playerId: 'player1',
    cardCount: cards.length,
    cards,
  });

  const spoofingKing: api.CardDto = {
    cardId: 'card1',
    suit: 'SPOOFING',
    rank: 'KING',
    rankSymbol: 'K',
    rankValue: 13,
    threatPrompt: 'Test prompt 1',
  };

  const tamperingKing: api.CardDto = {
    cardId: 'card2',
    suit: 'TAMPERING',
    rank: 'KING',
    rankSymbol: 'K',
    rankValue: 13,
    threatPrompt: 'Test prompt 2',
  };

  const repudiationQueen: api.CardDto = {
    cardId: 'card3',
    suit: 'REPUDIATION',
    rank: 'QUEEN',
    rankSymbol: 'Q',
    rankValue: 12,
    threatPrompt: 'Test prompt 3',
  };

  const idleTrickState: api.TrickStateDto = {
    complete: false,
    handComplete: false,
    seatToPlay: 0,
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders loading state before data arrives', () => {
    vi.spyOn(api, 'fetchHand').mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, 'getTrickState').mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, 'getSession').mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    expect(screen.getByText('Waiting for cards to be dealt...')).toBeInTheDocument();
  });

  it("renders the player's hand with card images and rank symbols after data loads", async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing, tamperingKing, repudiationQueen]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // spoofingKing has rank KING — image exists, so alt text is rendered
      expect(screen.getByAltText('K of spoofing')).toBeInTheDocument();
    });

    // tamperingKing and repudiationQueen have real images — they render <img> with alt text
    expect(screen.getByAltText('K of tampering')).toBeInTheDocument();
    expect(screen.getByAltText('Q of repudiation')).toBeInTheDocument();
  });

  it("renders the player's display name above their hand", async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByText('Alice')).toBeInTheDocument();
    });
  });

  it('disables cards when it is not the player\'s turn', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue({
      complete: false,
      handComplete: false,
      seatToPlay: 1, // Bob's turn, not Alice's
    });
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // Card is rendered as a role="button" with aria-disabled
      // aria-label format: "{rankSymbol} of {suit}: {threatPrompt}"
      const cardButtons = screen.getAllByRole('button');
      const cardButton = cardButtons.find(btn => btn.getAttribute('aria-label')?.includes('of spoofing'));
      expect(cardButton).toBeDefined();
      expect(cardButton).toHaveAttribute('aria-disabled', 'true');
    });
  });

  it('shows whose turn it is', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue({
      complete: false,
      handComplete: false,
      seatToPlay: 1, // Bob's turn
    });
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // Both the aria-live region and the visible paragraph show the turn label
      expect(screen.getAllByText("Bob's turn").length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows 'Your turn' when it is the current player's turn", async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState); // seatToPlay: 0 = Alice
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getAllByText('Your turn').length).toBeGreaterThanOrEqual(1);
    });
  });

  it('allows card selection and shows play button when it is the player\'s turn', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByAltText('K of spoofing')).toBeInTheDocument();
    });

    // Click the card — aria-label format: "{rankSymbol} of {suit}: {threatPrompt}"
    const cardButton = screen.getByRole('button', { name: /K of spoofing/i });
    fireEvent.click(cardButton);

    // Play selected card button should appear
    expect(screen.getByRole('button', { name: 'Play selected card' })).toBeInTheDocument();
  });

  it('marks card as selected (aria-pressed) after click', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /K of spoofing/i })).toBeInTheDocument();
    });

    const cardButton = screen.getByRole('button', { name: /K of spoofing/i });
    expect(cardButton).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(cardButton);
    expect(cardButton).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows trick winner banner when trick is complete', async () => {
    const completeTrickState: api.TrickStateDto = {
      complete: true,
      handComplete: false,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 0,
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player1',
            seatOrder: 0,
            card: spoofingKing,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }
        ],
        winningSeat: 0,
      },
    };

    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(completeTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    const alertBanner = screen.getByRole('alert');
    expect(within(alertBanner).getByText('Trick won!')).toBeInTheDocument();
    // Winner's name appears in the banner
    expect(within(alertBanner).getByText(/Alice/)).toBeInTheDocument();
  });

  it('shows "Start next trick" button only for the trick winner', async () => {
    const completeTrickState: api.TrickStateDto = {
      complete: true,
      handComplete: false,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 0,
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player1',
            seatOrder: 0,
            card: spoofingKing,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }
        ],
        winningSeat: 0, // Alice wins
      },
    };

    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(completeTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    // Alice is the current player and the winner — "Start next trick" should appear
    expect(screen.getByRole('button', { name: 'Start next trick' })).toBeInTheDocument();
  });

  it('auto-dismisses winner banner after 5 seconds', async () => {
    const completeTrickState: api.TrickStateDto = {
      complete: true,
      handComplete: false,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 0,
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player1',
            seatOrder: 0,
            card: spoofingKing,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }
        ],
        winningSeat: 0,
      },
    };

    // Use fake timers from the start but keep Promise/microtask scheduling real
    vi.useFakeTimers({ shouldAdvanceTime: false, toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });

    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(completeTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    // Flush all pending promises so the component finishes loading and sets the timer
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });

    // Banner should now be visible
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // Advance fake timers past the 5s auto-dismiss
    act(() => {
      vi.advanceTimersByTime(5001);
    });

    // Banner should be gone
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    vi.useRealTimers();
  });

  it('dismisses winner banner when Dismiss button is clicked', async () => {
    const completeTrickState: api.TrickStateDto = {
      complete: true,
      handComplete: false,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 0,
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player1',
            seatOrder: 0,
            card: spoofingKing,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }
        ],
        winningSeat: 0,
      },
    };

    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(completeTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('handles API errors gracefully and shows error summary', async () => {
    vi.spyOn(api, 'fetchHand').mockRejectedValue(new Error('Failed to fetch hand'));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByTestId('error-summary')).toBeInTheDocument();
    });

    expect(screen.getByText('Failed to fetch hand')).toBeInTheDocument();
  });

  it('shows waiting state (not session end) when /hand returns 409 (cards not yet dealt)', async () => {
    const notDealtError = new api.ApiError(409, 'Hands not dealt');
    vi.spyOn(api, 'fetchHand').mockRejectedValue(notDealtError);
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    const onSessionEnd = vi.fn();

    render(<GameScreen {...defaultProps} onSessionEnd={onSessionEnd} />);

    await waitFor(() => {
      expect(screen.getByText('Waiting for cards to be dealt...')).toBeInTheDocument();
    });

    // Session must NOT have ended — the player is still in the game
    expect(onSessionEnd).not.toHaveBeenCalled();
    // No error summary shown — this is a normal waiting state
    expect(screen.queryByTestId('error-summary')).not.toBeInTheDocument();
  });

  /*
   * EOP-224. The regression test for the lost doorbell.
   *
   * The observed failure: a participant's `GET /hand` was refused 409 twenty-five
   * milliseconds before the deal committed, and the `cards-dealt` doorbell was
   * published while that participant's stream was still being established. The
   * 409 branch has no retry, so the client sat at "Waiting for cards to be
   * dealt..." for ever. Before the `onOpen` catch-up read this test fails: the
   * hand never appears, because nothing re-reads once the stream is live.
   */
  it('re-reads when the stream opens, recovering a doorbell published before it was live', async () => {
    const notDealtError = new api.ApiError(409, 'Hands not dealt');
    vi.spyOn(api, 'fetchHand')
      .mockRejectedValueOnce(notDealtError)
      .mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);

    let announceOpen: (() => void) | undefined;
    vi.spyOn(api, 'subscribeToSession').mockImplementation((_sessionId, _token, _onEvent, _onError, onOpen) => {
      announceOpen = onOpen;
      return { abort: vi.fn() } as unknown as AbortController;
    });

    const onSessionEnd = vi.fn();
    render(<GameScreen {...defaultProps} onSessionEnd={onSessionEnd} />);

    // The 409 read lands first, exactly as it did in the trace.
    await waitFor(() => {
      expect(screen.getByText('Waiting for cards to be dealt...')).toBeInTheDocument();
    });

    // The stream goes live. No further doorbell will ever arrive for the deal.
    expect(announceOpen).toBeDefined();
    act(() => {
      announceOpen?.();
    });

    await waitFor(() => {
      expect(screen.getByAltText('K of spoofing')).toBeInTheDocument();
    });
    expect(screen.queryByText('Waiting for cards to be dealt...')).not.toBeInTheDocument();
    expect(onSessionEnd).not.toHaveBeenCalled();
    expect(screen.queryByTestId('error-summary')).not.toBeInTheDocument();
  });

  it('calls onSessionEnd when getSession returns 404 (session genuinely gone)', async () => {
    const sessionGoneError = new api.ApiError(404, 'Session not found');
    vi.spyOn(api, 'getSession').mockRejectedValue(sessionGoneError);
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    const onSessionEnd = vi.fn();

    render(<GameScreen {...defaultProps} onSessionEnd={onSessionEnd} />);

    await waitFor(() => {
      expect(onSessionEnd).toHaveBeenCalledTimes(1);
    });
  });

  it('shows other players around the table', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // Bob and Charlie are other players — their names should appear
      expect(screen.getByText('Bob')).toBeInTheDocument();
      expect(screen.getByText('Charlie')).toBeInTheDocument();
    });
  });

  it('shows the central trick zone drop area', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByLabelText(/current trick.*drop a card/i)).toBeInTheDocument();
    });
  });

  it('shows "No cards remaining" when hand is empty', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByText('No cards remaining')).toBeInTheDocument();
    });
  });

  it('card buttons carry className eop-card so the CSS focus ring is reachable', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      const card = screen.getByRole('button', { name: /K of spoofing/i });
      expect(card).toHaveClass('eop-card');
    });
  });

  it('applies GOV.UK focus box-shadow (yellow + dark inset) when card receives focus', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    const card = await screen.findByRole('button', { name: /K of spoofing/i });

    // Before focus: no GOV.UK focus shadow
    expect(card).not.toHaveStyle({ boxShadow: '0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c' });

    // Trigger focus
    fireEvent.focus(card);
    expect(card).toHaveStyle({ boxShadow: '0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c' });

    // Trigger blur — focus shadow removed
    fireEvent.blur(card);
    expect(card).not.toHaveStyle({ boxShadow: '0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c' });
  });

  describe('drag and drop card play', () => {
    // PointerEvent and Element.setPointerCapture are absent from jsdom and shimmed in
    // src/setupTests.ts, so fireEvent.pointer* here behaves as it does in a browser.
    const DROP_ZONE_RECT = { left: 100, top: 100, right: 300, bottom: 300, width: 200, height: 200, x: 100, y: 100 };

    const mockDropZoneRect = (dropZone: HTMLElement): void => {
      // getBoundingClientRect returns all-zeros in jsdom, so the bounds check in
      // handlePointerUp could never pass without a real rect.
      dropZone.getBoundingClientRect = vi.fn(() => ({
        ...DROP_ZONE_RECT,
        toJSON: () => DROP_ZONE_RECT,
      }));
    };

    const renderWithHand = async (): Promise<{ card: HTMLElement; dropZone: HTMLElement }> => {
      vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing, tamperingKing]));
      vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
      vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
      vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

      render(<GameScreen {...defaultProps} />);

      const card = await screen.findByRole('button', { name: /K of tampering/i });
      const dropZone = screen.getByLabelText(/current trick.*drop a card/i);
      mockDropZoneRect(dropZone);
      return { card, dropZone };
    };

    beforeEach(() => {
      vi.spyOn(api, 'playCard').mockResolvedValue(undefined as unknown as never);
    });

    // The regression guard for EOP-79. An <img> is implicitly draggable="true", so the
    // browser started its own HTML5 drag on press-and-move, fired `dragstart`, and
    // aborted the pointer stream with `pointercancel` — which handlePointerCancel treats
    // as an abandoned drag. The card snapped back and no play was submitted. This
    // assertion needs no pointer plumbing, so it holds regardless of jsdom's gaps.
    it('marks the card image as not natively draggable', async () => {
      await renderWithHand();

      expect(screen.getByAltText('K of tampering')).toHaveAttribute('draggable', 'false');
    });

    it('marks the played card image in the trick zone as not natively draggable', async () => {
      // The trick zone sits beneath the same container that carries onPointerCancel, and
      // handlePointerCancel filters no pointerId — so a native drag begun on a played card
      // would abort a hand-card drag in flight. Same EOP-79 invariant, second surface.
      const trickWithPlay: api.TrickStateDto = {
        complete: false,
        handComplete: false,
        seatToPlay: 0,
        trick: {
          trickId: 'trick1',
          sequence: 1,
          leaderSeat: 0,
          plays: [
            {
              trickPlayId: 'play1',
              playerId: 'player2',
              seatOrder: 1,
              card: tamperingKing,
              threatLinked: false,
              components: [],
              playedAt: '2023-01-01T00:00:00Z',
            },
          ],
        },
      };

      vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
      vi.spyOn(api, 'getTrickState').mockResolvedValue(trickWithPlay);
      vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
      vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

      render(<GameScreen {...defaultProps} />);

      // The hand holds only the spoofing king, so this alt text resolves to the trick zone.
      const playedCard = await screen.findByAltText('K of tampering');
      expect(playedCard).toHaveAttribute('draggable', 'false');
    });

    it('submits the play when a card is dropped inside the trick zone', async () => {
      const playCardSpy = vi.spyOn(api, 'playCard');
      const { card } = await renderWithHand();

      fireEvent.pointerDown(card, { pointerId: 1, clientX: 10, clientY: 400 });
      fireEvent.pointerMove(card, { pointerId: 1, clientX: 150, clientY: 250 });
      fireEvent.pointerUp(card, { pointerId: 1, clientX: 200, clientY: 200 });

      await waitFor(() => {
        expect(playCardSpy).toHaveBeenCalledWith('test-session', 'test-token', { cardId: 'card2' });
      });
    });

    it('does not submit the play when a card is dropped outside the trick zone', async () => {
      const playCardSpy = vi.spyOn(api, 'playCard');
      const { card, dropZone } = await renderWithHand();

      fireEvent.pointerDown(card, { pointerId: 1, clientX: 10, clientY: 400 });
      fireEvent.pointerMove(card, { pointerId: 1, clientX: 480, clientY: 480 });

      // Assert the drag genuinely started before asserting nothing was played, so this
      // test cannot pass vacuously by never having begun a drag at all. The drop zone
      // switches to its active border/background only while dragState is set.
      expect(dropZone).toHaveStyle({ backgroundColor: 'rgb(232, 240, 251)' });

      fireEvent.pointerUp(card, { pointerId: 1, clientX: 500, clientY: 500 });

      await waitFor(() => {
        expect(dropZone).toHaveStyle({ backgroundColor: 'rgb(243, 242, 241)' });
      });
      expect(playCardSpy).not.toHaveBeenCalled();
    });
  });

  /**
   * Follow-suit hint (EOP-168).
   *
   * These tests drive the hint through GameScreen rather than through
   * FollowSuitHint in isolation, because the wiring is the part that can break:
   * FollowSuitHint.test.tsx already covers the prop logic, but only GameScreen
   * reads `import.meta.env`, so this is the only place `vi.stubEnv` can reach
   * the flag. The flag is stubbed explicitly in every test — a developer's
   * .env.local may set VITE_FOLLOW_SUIT_HINT_ENABLED either way, and an
   * unstubbed test would silently assert whatever position that file holds
   * (ADR-037).
   */
  describe('follow-suit hint', () => {
    const tamperingLed: api.TrickStateDto = {
      complete: false,
      handComplete: false,
      seatToPlay: 0,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 1,
        ledSuit: 'TAMPERING',
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player2',
            seatOrder: 1,
            card: tamperingKing,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          },
        ],
      },
    };

    const renderWith = async (
      trickState: api.TrickStateDto,
      cards: api.CardDto[],
    ): Promise<void> => {
      vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand(cards));
      vi.spyOn(api, 'getTrickState').mockResolvedValue(trickState);
      vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
      vi.spyOn(api, 'subscribeToSession').mockReturnValue({
        abort: vi.fn(),
      } as unknown as AbortController);

      render(<GameScreen {...defaultProps} />);
      await waitFor(() => {
        expect(api.getTrickState).toHaveBeenCalled();
      });
    };

    afterEach(() => {
      vi.unstubAllEnvs();
    });

    describe('when the flag is on', () => {
      beforeEach(() => {
        vi.stubEnv('VITE_FOLLOW_SUIT_HINT_ENABLED', 'true');
      });

      it('names the led suit when it is my turn and I can follow', async () => {
        await renderWith(tamperingLed, [tamperingKing, spoofingKing]);

        expect(
          await screen.findByText(/you must play a tampering card/i),
        ).toBeInTheDocument();
      });

      it('renders the hint as a status region so screen readers announce it', async () => {
        await renderWith(tamperingLed, [tamperingKing]);

        expect(await screen.findByRole('status')).toHaveTextContent(/follow suit/i);
      });

      it('does not show the hint when I hold no card of the led suit', async () => {
        // The domain rule is follow-suit-*if-able* (Trick#play), so a hint
        // demanding a suit the hand cannot supply would state a falsehood.
        await renderWith(tamperingLed, [spoofingKing, repudiationQueen]);

        await waitFor(() => {
          expect(screen.getByRole('group', { name: 'Your hand' })).toBeInTheDocument();
        });
        expect(screen.queryByText(/you must play a/i)).not.toBeInTheDocument();
      });

      it('does not show the hint when I am leading the trick', async () => {
        // idleTrickState carries no trick, so no suit has been led yet.
        await renderWith(idleTrickState, [tamperingKing]);

        await waitFor(() => {
          expect(screen.getByRole('group', { name: 'Your hand' })).toBeInTheDocument();
        });
        expect(screen.queryByText(/you must play a/i)).not.toBeInTheDocument();
      });

      it('does not show the hint when it is not my turn', async () => {
        await renderWith({ ...tamperingLed, seatToPlay: 1 }, [tamperingKing]);

        await waitFor(() => {
          expect(screen.getByRole('group', { name: 'Your hand' })).toBeInTheDocument();
        });
        expect(screen.queryByText(/you must play a/i)).not.toBeInTheDocument();
      });
    });

    describe('when the flag is off', () => {
      it('renders no hint even though every other condition holds', async () => {
        vi.stubEnv('VITE_FOLLOW_SUIT_HINT_ENABLED', 'false');
        await renderWith(tamperingLed, [tamperingKing]);

        await waitFor(() => {
          expect(screen.getByRole('group', { name: 'Your hand' })).toBeInTheDocument();
        });
        expect(screen.queryByText(/you must play a/i)).not.toBeInTheDocument();
      });
    });

    describe('when the flag is unset', () => {
      it('fails closed', async () => {
        // ADR-037: the `=== 'true'` comparison means an absent variable
        // disables the feature rather than enabling it.
        vi.stubEnv('VITE_FOLLOW_SUIT_HINT_ENABLED', '');
        await renderWith(tamperingLed, [tamperingKing]);

        await waitFor(() => {
          expect(screen.getByRole('group', { name: 'Your hand' })).toBeInTheDocument();
        });
        expect(screen.queryByText(/you must play a/i)).not.toBeInTheDocument();
      });
    });
  });

  describe('end game (facilitator only, EOP-255)', () => {
    const facilitatorSession: api.SessionStateDto = {
      ...mockSession,
      players: [
        { playerId: 'player1', displayName: 'Alice', seatOrder: 0, role: 'FACILITATOR', connectionStatus: 'CONNECTED' },
        { playerId: 'player2', displayName: 'Bob', seatOrder: 1, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
        { playerId: 'player3', displayName: 'Charlie', seatOrder: 2, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
      ],
    };

    const renderGame = async (session: api.SessionStateDto): Promise<void> => {
      vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
      vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
      vi.spyOn(api, 'getSession').mockResolvedValue(session);
      vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);
      vi.spyOn(api, 'endSession').mockResolvedValue(undefined);

      render(<GameScreen {...defaultProps} session={session} />);
      await waitFor(() => {
        expect(screen.getByRole('group', { name: 'Your hand' })).toBeInTheDocument();
      });
    };

    it('shows the End game button to the facilitator', async () => {
      await renderGame(facilitatorSession);

      expect(screen.getByRole('button', { name: 'End game' })).toBeInTheDocument();
    });

    it('never shows the End game button to a participant', async () => {
      await renderGame(mockSession);

      expect(screen.queryByRole('button', { name: 'End game' })).not.toBeInTheDocument();
    });

    it('asks for confirmation, and cancelling ends nothing', async () => {
      await renderGame(facilitatorSession);

      fireEvent.click(screen.getByRole('button', { name: 'End game' }));
      expect(screen.getByText(/cannot be undone/)).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

      expect(screen.queryByText(/cannot be undone/)).not.toBeInTheDocument();
      expect(api.endSession).not.toHaveBeenCalled();
      // The plain button is back, so the facilitator can try again.
      expect(screen.getByRole('button', { name: 'End game' })).toBeInTheDocument();
    });

    it('ends the game when the facilitator confirms', async () => {
      await renderGame(facilitatorSession);

      fireEvent.click(screen.getByRole('button', { name: 'End game' }));
      fireEvent.click(screen.getByRole('button', { name: 'Yes, end the game' }));

      await waitFor(() => {
        expect(api.endSession).toHaveBeenCalledWith('test-session', 'test-token');
      });
    });
  });
});
