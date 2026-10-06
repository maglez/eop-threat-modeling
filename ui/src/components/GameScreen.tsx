import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  fetchHand,
  getTrickState,
  playCard,
  getSession,
  subscribeToSession,
  endSession,
  ApiError,
  type HandDto,
  type CardDto,
  type TrickStateDto,
  type SessionStateDto,
  type PlayerDto,
} from '../api';
import { ErrorSummary } from './ErrorSummary';
import { CardLens, useCardMagnifier } from './CardMagnifier';
import { FollowSuitHint } from './FollowSuitHint';
import { cardImagePath } from '../utils/cardImagePath';
import './GameScreen.css';



interface GameScreenProps {
  readonly sessionId: string;
  readonly playerId: string;
  readonly playerToken: string;
  readonly session: SessionStateDto;
  readonly onSessionEnd: () => void;
  readonly onGameOver?: () => void;
}

/** Positions for other players around the table relative to the current player. */
type TablePosition = 'top' | 'top-left' | 'top-right' | 'left' | 'right';

/**
 * Maps other players (by their index relative to the current player's seat) to
 * a table position. Supports 2–5 other players (3–6 total).
 */
function getTablePositions(otherCount: number): TablePosition[] {
  switch (otherCount) {
    case 1: return ['top'];
    case 2: return ['top-left', 'top-right'];
    case 3: return ['top-left', 'top', 'top-right'];
    case 4: return ['left', 'top-left', 'top-right', 'right'];
    default: return ['left', 'top-left', 'top', 'top-right', 'right'];
  }
}

/** Suit colour mapping for card faces — GOV.UK palette. */
const SUIT_COLOURS: Record<string, string> = {
  SPOOFING: '#1d70b8',           // govuk-blue
  TAMPERING: '#00703c',          // govuk-green
  REPUDIATION: '#4c2c92',        // govuk-purple
  INFORMATION_DISCLOSURE: '#d4351c', // govuk-red
  DENIAL_OF_SERVICE: '#f47738',  // govuk-orange
  ELEVATION_OF_PRIVILEGE: '#0b0c0c', // govuk-black
};

const SUIT_LABELS: Record<string, string> = {
  SPOOFING: 'S',
  TAMPERING: 'T',
  REPUDIATION: 'R',
  INFORMATION_DISCLOSURE: 'I',
  DENIAL_OF_SERVICE: 'D',
  ELEVATION_OF_PRIVILEGE: 'E',
};

// ---- Sub-components ----

interface CardBodyProps {
  readonly card: CardDto;
  readonly rankFontSize: string;
  readonly suitFontSize: string;
  readonly imageBorderRadius: string;
  readonly suitLineHeight?: number;
}

/**
 * The inside of a card: its bundled art, or a three-line text fallback for the
 * suit and rank combinations that have no image.
 *
 * Extracted by EOP-145 so that the magnifier's replica renders the *same* JSX as
 * the card it magnifies, rather than a second copy that can silently drift out
 * of step with the original.
 *
 * @param props the card and the type sizes the text fallback should use
 * @return the card body
 */
function CardBody({ card, rankFontSize, suitFontSize, imageBorderRadius, suitLineHeight }: CardBodyProps): React.JSX.Element {
  const suitColour = SUIT_COLOURS[card.suit] ?? '#0b0c0c';
  const suitLabel = SUIT_LABELS[card.suit] ?? card.suit[0];
  const imagePath = cardImagePath(card.suit, card.rank);

  if (imagePath !== null) {
    return (
      <img
        src={imagePath}
        alt={`${card.rankSymbol} of ${card.suit.toLowerCase().replace(/_/g, ' ')}`}
        // An <img> is implicitly draggable="true" in every browser, so pressing on the
        // card art and moving starts a *native* HTML5 drag. That fires `dragstart` and
        // aborts the in-flight pointer stream with `pointercancel`, which
        // `handlePointerCancel` correctly treats as "drag abandoned" — so the card
        // snapped back to the hand and no play was ever submitted (EOP-79). Disabling
        // native dragging is what lets the pointer-based drag reach `pointerup`.
        draggable={false}
        style={{
          width: '100%',
          height: '100%',
          objectFit: 'contain',
          borderRadius: imageBorderRadius,
          display: 'block',
          WebkitUserDrag: 'none',
        } as React.CSSProperties}
      />
    );
  }

  return (
    <>
      <div style={{ fontSize: rankFontSize, fontWeight: 'bold', color: suitColour }}>
        {card.rankSymbol}
      </div>
      <div style={{
        fontSize: suitFontSize,
        color: suitColour,
        fontWeight: 'bold',
        textAlign: 'center',
        lineHeight: suitLineHeight,
      }}>
        {suitLabel}
      </div>
      <div style={{ fontSize: rankFontSize, fontWeight: 'bold', color: suitColour, textAlign: 'right' }}>
        {card.rankSymbol}
      </div>
    </>
  );
}

/** Border-box geometry of a hand card, shared with its magnifier replica. */
const HAND_CARD_WIDTH_PX = 80;
const HAND_CARD_HEIGHT_PX = 120;
const HAND_CARD_PADDING_PX = 6;

interface CardFaceProps {
  readonly card: CardDto;
  readonly selected: boolean;
  readonly disabled: boolean;
  readonly dragging: boolean;
  readonly magnifierEnabled: boolean;
  readonly onSelect: () => void;
  readonly onPointerDown: (e: React.PointerEvent<HTMLDivElement>) => void;
}

function CardFace({ card, selected, disabled, dragging, magnifierEnabled, onSelect, onPointerDown }: CardFaceProps): React.JSX.Element {
  const [focused, setFocused] = React.useState(false);
  const magnifier = useCardMagnifier(magnifierEnabled);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (!disabled && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      onSelect();
    }
  };

  // GOV.UK focus pattern: yellow outline + dark inset companion for SC 1.4.11 Non-text Contrast (AA).
  // The inset is applied via inline style so it is not overridden by the CSS class rule.
  const focusBoxShadow = '0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c';

  const body = (
    <CardBody
      card={card}
      rankFontSize="14px"
      suitFontSize="11px"
      imageBorderRadius="4px"
      suitLineHeight={1}
    />
  );

  const handleCardPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === 'mouse') {
      // A mouse drag calls setPointerCapture, so every subsequent pointermove is
      // routed here and the lens would track the cursor away across the table.
      // Hide it for the duration of the press; the next hover brings it back.
      magnifier.hide();
    } else {
      magnifier.onPointerDown(e);
    }
    // A disabled card still magnifies (a player must be able to read a card that
    // is not theirs to play yet) but must never start a drag.
    if (!disabled) onPointerDown(e);
  };

  return (
    <div
      ref={magnifier.containerRef}
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled}
      aria-pressed={selected}
      aria-label={`${card.rankSymbol} of ${card.suit.toLowerCase().replace(/_/g, ' ')}: ${card.threatPrompt}`}
      className="eop-card"
      onClick={disabled ? undefined : onSelect}
      onKeyDown={handleKeyDown}
      onPointerDown={handleCardPointerDown}
      onPointerMove={dragging ? undefined : magnifier.onPointerMove}
      onPointerLeave={magnifier.onPointerLeave}
      onFocus={() => { setFocused(true); }}
      onBlur={() => { setFocused(false); }}
      style={{
        display: 'inline-flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        // Declared inline rather than relying on `.eop-card { position: relative }`
        // in GameScreen.css, because the lens is absolutely positioned against
        // this box and that CSS win is incidental — every other geometric
        // property here already overrides the class.
        position: 'relative',
        width: `${String(HAND_CARD_WIDTH_PX)}px`,
        height: `${String(HAND_CARD_HEIGHT_PX)}px`,
        border: selected ? '3px solid #ffdd00' : '2px solid #b1b4b6',
        borderRadius: '6px',
        padding: `${String(HAND_CARD_PADDING_PX)}px`,
        backgroundColor: '#ffffff',
        cursor: disabled ? 'not-allowed' : dragging ? 'grabbing' : 'grab',
        opacity: disabled ? 0.45 : 1,
        boxShadow: focused ? focusBoxShadow : selected ? '0 0 0 3px #ffdd00' : dragging ? '4px 4px 12px rgba(0,0,0,0.3)' : '1px 1px 4px rgba(0,0,0,0.15)',
        userSelect: 'none',
        touchAction: 'none',
        transition: 'box-shadow 0.1s, opacity 0.1s',
        flexShrink: 0,
      }}
    >
      {body}
      {magnifier.point !== null && (
        <CardLens
          point={magnifier.point}
          width={HAND_CARD_WIDTH_PX}
          height={HAND_CARD_HEIGHT_PX}
          padding={HAND_CARD_PADDING_PX}
        >
          {body}
        </CardLens>
      )}
    </div>
  );
}

/** Border-box geometry of a trick-zone card, shared with its magnifier replica. */
const TRICK_CARD_WIDTH_PX = 64;
const TRICK_CARD_HEIGHT_PX = 96;
const TRICK_CARD_PADDING_PX = 4;

interface TrickCardFaceProps {
  readonly card: CardDto;
  readonly magnifierEnabled: boolean;
}

/**
 * A card already played into the current trick. Smaller than a hand card and
 * never interactive — it cannot be selected, dragged or focused.
 *
 * Extracted from the trick zone's inline markup by EOP-145 so that it can own a
 * magnifier hook; a hook cannot be called inside the `.map` that renders the
 * plays. The rendered DOM is unchanged from the inline version.
 *
 * @param props the played card and whether the magnifier is switched on
 * @return the trick-zone card
 */
function TrickCardFace({ card, magnifierEnabled }: TrickCardFaceProps): React.JSX.Element {
  const magnifier = useCardMagnifier(magnifierEnabled);

  const body = (
    <CardBody
      card={card}
      rankFontSize="12px"
      suitFontSize="10px"
      imageBorderRadius="3px"
    />
  );

  return (
    <div
      ref={magnifier.containerRef}
      onPointerMove={magnifier.onPointerMove}
      onPointerLeave={magnifier.onPointerLeave}
      onPointerDown={magnifier.onPointerDown}
      style={{
        display: 'inline-flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        position: 'relative',
        width: `${String(TRICK_CARD_WIDTH_PX)}px`,
        height: `${String(TRICK_CARD_HEIGHT_PX)}px`,
        border: '2px solid #b1b4b6',
        borderRadius: '6px',
        padding: `${String(TRICK_CARD_PADDING_PX)}px`,
        backgroundColor: '#ffffff',
      }}
    >
      {body}
      {magnifier.point !== null && (
        <CardLens
          point={magnifier.point}
          width={TRICK_CARD_WIDTH_PX}
          height={TRICK_CARD_HEIGHT_PX}
          padding={TRICK_CARD_PADDING_PX}
        >
          {body}
        </CardLens>
      )}
    </div>
  );
}

function CardBack(): React.JSX.Element {
  return (
    <div style={{
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      width: '80px',
      height: '120px',
      border: '2px solid #b1b4b6',
      borderRadius: '6px',
      backgroundColor: '#1d70b8',
      color: '#ffffff',
      fontSize: '12px',
      fontWeight: 'bold',
      userSelect: 'none',
    }}
      aria-hidden="true"
    >
      EoP
    </div>
  );
}

interface OtherPlayerSeatProps {
  readonly player: PlayerDto;
}

function OtherPlayerSeat({ player }: OtherPlayerSeatProps): React.JSX.Element {
  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: '6px',
    }}>
      <span className="govuk-body-s" style={{ fontWeight: 'bold', textAlign: 'center', maxWidth: '100px', wordBreak: 'break-word' }}>
        {player.displayName}
      </span>
      <CardBack />
    </div>
  );
}

// ---- Drag state ----

interface DragState {
  readonly cardId: string;
  readonly startX: number;
  readonly startY: number;
  readonly currentX: number;
  readonly currentY: number;
}

// ---- Main component ----

/**
 * Game screen: virtual table layout with private hand, drag-and-drop card play,
 * and trick winner announcement.
 *
 * SSE doorbell pattern mirrors LobbyScreen — each event triggers a re-fetch of
 * trick state and session state. playerToken is sent as a header only, never in
 * URL or body (ADR-015).
 */
export function GameScreen({
  sessionId,
  playerId,
  playerToken,
  session: initialSession,
  onSessionEnd,
  onGameOver,
}: GameScreenProps): React.JSX.Element {
  // Feature flag — read at component scope so that `vi.stubEnv` can reach it in
  // tests. Read at module scope it would be captured once at import time, and
  // the flag-off test would silently exercise whatever position the developer's
  // .env.local happens to hold. The `=== 'true'` comparison is what makes an
  // unset variable fail closed (ADR-037).
  const isCardMagnifierEnabled = import.meta.env.VITE_CARD_MAGNIFIER_ENABLED === 'true';

  // Feature flag — same contract as above: component scope, not module scope,
  // and compared with === 'true' so that an unset variable fails closed
  // (ADR-037). Threaded into FollowSuitHint rather than used to decide whether
  // to render it, so the gate lives at the single point the feature is entered.
  const isFollowSuitHintEnabled = import.meta.env.VITE_FOLLOW_SUIT_HINT_ENABLED === 'true';
  const [session, setSession] = useState<SessionStateDto>(initialSession);
  const [hand, setHand] = useState<HandDto | null>(null);
  const [trickState, setTrickState] = useState<TrickStateDto | null>(null);
  const [selectedCardId, setSelectedCardId] = useState<string | null>(null);
  const [dragState, setDragState] = useState<DragState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPlayingCard, setIsPlayingCard] = useState(false);
  const [winnerDismissed, setWinnerDismissed] = useState(false);
  const [confirmingEndGame, setConfirmingEndGame] = useState(false);
  const [isEndingGame, setIsEndingGame] = useState(false);
  const winnerTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const dropZoneRef = useRef<HTMLDivElement | null>(null);

  const currentPlayer = session.players.find(p => p.playerId === playerId);
  const mySeats = currentPlayer?.seatOrder;
  const isFacilitator = currentPlayer?.role === 'FACILITATOR';

  // Determine if it's my turn
  const isMyTurn = trickState?.seatToPlay !== undefined && mySeats !== undefined
    ? trickState.seatToPlay === mySeats
    : false;

  // Trick winner info
  const winnerSeat = trickState?.trick?.winningSeat;
  const winnerPlayer = winnerSeat !== undefined
    ? session.players.find(p => p.seatOrder === winnerSeat)
    : undefined;
  const winningPlay = trickState?.trick?.plays.find(p => p.seatOrder === winnerSeat);
  const iAmWinner = winnerSeat !== undefined && mySeats !== undefined && winnerSeat === mySeats;
  const showWinnerBanner = winnerSeat !== undefined && !winnerDismissed && trickState?.complete === true;

  // Reset winner dismissed state when trick changes
  const trickId = trickState?.trick?.trickId;
  useEffect(() => {
    setWinnerDismissed(false);
  }, [trickId]);

  // Auto-dismiss winner banner after 5 seconds
  useEffect(() => {
    if (showWinnerBanner) {
      if (winnerTimerRef.current !== null) clearTimeout(winnerTimerRef.current);
      winnerTimerRef.current = setTimeout(() => {
        setWinnerDismissed(true);
        winnerTimerRef.current = null;
      }, 5000);
    }
    return () => {
      if (winnerTimerRef.current !== null) {
        clearTimeout(winnerTimerRef.current);
        winnerTimerRef.current = null;
      }
    };
  }, [showWinnerBanner]);

  const refreshGameState = useCallback(async () => {
    // Fetch session state first. A 404 here means the session is genuinely gone
    // (or the token is invalid) — navigate home. A 409 on /hand or /tricks/current
    // means HandNotDealtException: the deck has not been dealt yet; show a waiting
    // state rather than ending the session.
    let newSession: SessionStateDto;
    try {
      newSession = await getSession(sessionId, playerToken);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load game state';
      setError(message);
      if (err instanceof ApiError && (err.status === 403 || err.status === 404)) {
        onSessionEnd();
      }
      return;
    }

    try {
      const [newHand, newTrickState] = await Promise.all([
        fetchHand(sessionId, playerToken),
        getTrickState(sessionId, playerToken),
      ]);
      setHand(newHand);
      setTrickState(newTrickState);
      setSession(newSession);
      setError(null);
    } catch (err) {
      // A 409 from /hand or /tricks/current means the deck has not been dealt yet
      // (HandNotDealtException → 409 Conflict). Keep the session alive and show a
      // waiting state (hand/trickState remain null).
      if (err instanceof ApiError && err.status === 409) {
        setSession(newSession);
        setHand(null);
        setTrickState(null);
        setError(null);
        return;
      }
      const message = err instanceof Error ? err.message : 'Failed to load game state';
      setError(message);
      if (err instanceof ApiError && err.status === 403) {
        onSessionEnd();
      }
    }
  }, [sessionId, playerToken, onSessionEnd]);

  /*
   * Initial load + SSE subscription.
   *
   * The read below deliberately precedes the subscribe, so first paint does not
   * wait on the stream handshake — measured at six seconds under EOP-224's
   * pathological contention. That ordering used to be a defect: an event
   * published in the gap between this read and the subscription was lost for
   * ever, and the `409` waiting state below has no retry, so a player whose
   * `cards-dealt` doorbell fell in that gap sat at "Waiting for cards to be
   * dealt..." indefinitely. The `onOpen` catch-up read closes the gap, which is
   * what makes this ordering safe rather than merely fast.
   */
  useEffect(() => {
    let abandoned = false;

    const setup = async () => {
      await refreshGameState();
      if (abandoned) return;

      const subscription = subscribeToSession(
        sessionId,
        playerToken,
        (eventType) => {
          if (abandoned) return;
          if (eventType === 'game-completed' && onGameOver) {
            onGameOver();
          } else {
            void refreshGameState();
          }
        },
        (err) => {
          if (abandoned) return;
          const message = err instanceof Error ? err.message : 'Connection lost';
          setError(message);
          if (err instanceof ApiError && (err.status === 403 || err.status === 404)) {
            onSessionEnd();
          }
        },
        () => {
          // Catch up on anything published before this stream existed.
          if (abandoned) return;
          void refreshGameState();
        },
      );

      return () => { subscription.abort(); };
    };

    let teardown: (() => void) | undefined;
    setup()
      .then((fn) => { teardown = fn; })
      .catch((err: unknown) => {
        if (!abandoned) {
          setError(err instanceof Error ? err.message : 'Failed to connect');
        }
      });

    return () => {
      abandoned = true;
      teardown?.();
      if (winnerTimerRef.current !== null) clearTimeout(winnerTimerRef.current);
    };
  }, [sessionId, playerToken, refreshGameState, onSessionEnd, onGameOver]);

  // ---- Card play ----

  const submitCardPlay = useCallback(async (cardId: string) => {
    if (isPlayingCard) return;
    setIsPlayingCard(true);
    setSelectedCardId(null);
    try {
      await playCard(sessionId, playerToken, { cardId });
      await refreshGameState();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to play card';
      setError(message);
    } finally {
      setIsPlayingCard(false);
    }
  }, [sessionId, playerToken, isPlayingCard, refreshGameState]);

  const handlePlaySelected = () => {
    if (selectedCardId) void submitCardPlay(selectedCardId);
  };

  // ---- Drag-and-drop (pointer events) ----

  const handlePointerDown = (cardId: string) => (e: React.PointerEvent<HTMLDivElement>) => {
    if (!isMyTurn || isPlayingCard) return;
    // Suppress the browser's own drag/selection gesture. `draggable={false}` on the card
    // image is the targeted fix for EOP-79; this is the belt-and-braces equivalent for the
    // text-fallback card face and for any future natively-draggable child element.
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragState({
      cardId,
      startX: e.clientX,
      startY: e.clientY,
      currentX: e.clientX,
      currentY: e.clientY,
    });
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragState) return;
    setDragState(prev => prev ? { ...prev, currentX: e.clientX, currentY: e.clientY } : null);
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragState) return;
    const dropZone = dropZoneRef.current;
    if (dropZone) {
      const rect = dropZone.getBoundingClientRect();
      const inDropZone =
        e.clientX >= rect.left &&
        e.clientX <= rect.right &&
        e.clientY >= rect.top &&
        e.clientY <= rect.bottom;
      if (inDropZone) {
        void submitCardPlay(dragState.cardId);
      }
    }
    setDragState(null);
  };

  const handlePointerCancel = () => { setDragState(null); };

  // ---- Resolve trick / start next ----

  const handleStartNextTrick = () => {
    // The trick is already resolved by the server when the last card is played.
    // Dismissing the banner is all that is needed; the winner leads the next trick
    // by playing a card normally.
    setWinnerDismissed(true);
  };

  // ---- End game early (facilitator) ----

  const handleEndGame = async (): Promise<void> => {
    setIsEndingGame(true);
    try {
      await endSession(sessionId, playerToken);
      // No navigation here on purpose: the server publishes `game-completed` to
      // every seat and this screen's own subscription handles it by calling
      // onGameOver. Leaving the button disabled until that arrives stops a second
      // click racing the event.
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not end the game. Please try again.');
      setIsEndingGame(false);
      setConfirmingEndGame(false);
    }
  };

  // ---- Layout helpers ----

  const otherPlayers = [...session.players]
    .filter(p => p.playerId !== playerId)
    .sort((a, b) => {
      // Sort clockwise from current player's seat.
      // Use ((x % n) + n) % n to correctly normalise negative values for any n.
      const n = session.players.length;
      const base = mySeats ?? 0;
      const aNorm = ((a.seatOrder - base) % n + n) % n;
      const bNorm = ((b.seatOrder - base) % n + n) % n;
      return aNorm - bNorm;
    });

  const positions = getTablePositions(otherPlayers.length);

  // Position styles for other players
  const positionStyle: Record<TablePosition, React.CSSProperties> = {
    'top': { gridColumn: '2', gridRow: '1', justifySelf: 'center' },
    'top-left': { gridColumn: '1', gridRow: '1', justifySelf: 'end' },
    'top-right': { gridColumn: '3', gridRow: '1', justifySelf: 'start' },
    'left': { gridColumn: '1', gridRow: '2', alignSelf: 'center', justifySelf: 'end' },
    'right': { gridColumn: '3', gridRow: '2', alignSelf: 'center', justifySelf: 'start' },
  };

  // Whose turn label
  const seatToPlayPlayer = trickState?.seatToPlay !== undefined
    ? session.players.find(p => p.seatOrder === trickState.seatToPlay)
    : undefined;
  const turnLabel = seatToPlayPlayer
    ? (seatToPlayPlayer.playerId === playerId ? 'Your turn' : `${seatToPlayPlayer.displayName}'s turn`)
    : '';

  if (!hand || !trickState) {
    return (
      <div className="govuk-width-container">
        <main className="govuk-main-wrapper" id="main-content">
          {error ? (
            <ErrorSummary title="Could not load game" errors={[error]} onDismiss={() => setError(null)} />
          ) : (
            <p className="govuk-body">Waiting for cards to be dealt...</p>
          )}
        </main>
      </div>
    );
  }

  return (
    <div className="govuk-width-container">
      <main className="govuk-main-wrapper" id="main-content"
        onPointerMove={dragState ? handlePointerMove : undefined}
        onPointerUp={dragState ? handlePointerUp : undefined}
        onPointerCancel={dragState ? handlePointerCancel : undefined}
      >
        {error && (
          <ErrorSummary title="There is a problem" errors={[error]} onDismiss={() => setError(null)} />
        )}

        {/* Turn announcement for screen readers */}
        <div
          aria-live="polite"
          aria-atomic="true"
          className="govuk-visually-hidden"
        >
          {turnLabel}
        </div>

        {/* End game (facilitator only) — visible for the whole game, with an
            inline confirmation, because ending early finalises the score and
            cannot be undone. */}
        {isFacilitator && (
          <div style={{ marginBottom: '20px' }}>
            {confirmingEndGame ? (
              <>
                <div role="alert" className="govuk-warning-text">
                  <span className="govuk-warning-text__icon" aria-hidden="true">!</span>
                  <strong className="govuk-warning-text__text">
                    <span className="govuk-visually-hidden">Warning</span> End the game? The score is finalised from the cards played so far, and this cannot be undone.
                  </strong>
                </div>
                <div className="govuk-button-group">
                  <button
                    type="button"
                    className="govuk-button govuk-button--warning"
                    disabled={isEndingGame}
                    onClick={() => { void handleEndGame(); }}
                  >
                    {isEndingGame ? 'Ending...' : 'Yes, end the game'}
                  </button>
                  <button
                    type="button"
                    className="govuk-button govuk-button--secondary"
                    disabled={isEndingGame}
                    onClick={() => setConfirmingEndGame(false)}
                  >
                    Cancel
                  </button>
                </div>
              </>
            ) : (
              <button
                type="button"
                className="govuk-button govuk-button--warning"
                onClick={() => setConfirmingEndGame(true)}
              >
                End game
              </button>
            )}
          </div>
        )}

        {/* Trick winner banner */}
        {showWinnerBanner && winnerPlayer && (
          <div
            role="alert"
            className="govuk-notification-banner govuk-notification-banner--success"
            style={{ marginBottom: '20px' }}
          >
            <div className="govuk-notification-banner__header">
              <h2 className="govuk-notification-banner__title">Trick won!</h2>
            </div>
            <div className="govuk-notification-banner__content">
              <p className="govuk-body">
                <strong>Trick won by {winnerPlayer.displayName}</strong>
                {winningPlay && (
                  <> — {winningPlay.card.rankSymbol} of {winningPlay.card.suit.toLowerCase().replace(/_/g, ' ')}</>
                )}
              </p>
              <div className="govuk-button-group">
                {iAmWinner && (
                  <button
                    type="button"
                    className="govuk-button"
                    onClick={() => { void handleStartNextTrick(); }}
                  >
                    Start next trick
                  </button>
                )}
                <button
                  type="button"
                  className="govuk-button govuk-button--secondary"
                  onClick={() => setWinnerDismissed(true)}
                >
                  Dismiss
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Turn indicator (visible) */}
        {turnLabel && (
          <p className="govuk-body govuk-!-font-weight-bold" aria-hidden="true">
            {turnLabel}
          </p>
        )}

        {/* Table grid: 3 columns × 3 rows */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: '1fr 2fr 1fr',
          gridTemplateRows: 'auto auto auto',
          gap: '16px',
          marginBottom: '24px',
        }}>
          {/* Other players */}
          {otherPlayers.map((player, idx) => {
            const pos = positions[idx] ?? 'top';
            return (
              <div key={player.playerId} style={positionStyle[pos]}>
                <OtherPlayerSeat player={player} />
              </div>
            );
          })}

          {/* Central trick zone */}
          <div
            ref={dropZoneRef}
            style={{
              gridColumn: '2',
              gridRow: '2',
              minHeight: '160px',
              border: dragState ? '3px dashed #1d70b8' : '2px dashed #b1b4b6',
              borderRadius: '8px',
              padding: '16px',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: '12px',
              backgroundColor: dragState ? '#e8f0fb' : '#f3f2f1',
              transition: 'background-color 0.15s, border-color 0.15s',
            }}
            aria-label="Current trick — drop a card here to play it"
          >
            <span className="govuk-body-s govuk-hint">Current trick</span>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', justifyContent: 'center' }}>
              {(trickState.trick?.plays ?? []).map(play => {
                const playedByPlayer = session.players.find(p => p.playerId === play.playerId);
                return (
                  <div key={play.trickPlayId} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' }}>
                    <span className="govuk-body-s" style={{ fontSize: '11px' }}>
                      {playedByPlayer?.displayName ?? 'Unknown'}
                    </span>
                    <TrickCardFace card={play.card} magnifierEnabled={isCardMagnifierEnabled} />
                  </div>
                );
              })}
              {(trickState.trick?.plays.length ?? 0) === 0 && (
                <span className="govuk-hint" style={{ fontSize: '13px' }}>No cards played yet</span>
              )}
            </div>
          </div>
        </div>

        {/* Current player's hand */}
        <div style={{ borderTop: '2px solid #b1b4b6', paddingTop: '16px' }}>
          <p className="govuk-body govuk-!-font-weight-bold" style={{ marginBottom: '8px' }}>
            {currentPlayer?.displayName ?? 'Your hand'}
          </p>
          <div
            role="group"
            aria-label="Your hand"
            style={{ display: 'flex', flexWrap: 'wrap', gap: '10px', marginBottom: '16px' }}
          >
            {hand.cards.map(card => (
              <CardFace
                key={card.cardId}
                card={card}
                selected={selectedCardId === card.cardId}
                disabled={!isMyTurn || isPlayingCard}
                dragging={dragState?.cardId === card.cardId}
                magnifierEnabled={isCardMagnifierEnabled}
                onSelect={() => {
                  if (!isMyTurn || isPlayingCard) return;
                  setSelectedCardId(prev => prev === card.cardId ? null : card.cardId);
                }}
                onPointerDown={handlePointerDown(card.cardId)}
              />
            ))}
            {hand.cards.length === 0 && (
              <span className="govuk-hint">No cards remaining</span>
            )}
          </div>

          {/* Follow-suit hint (EOP-168) — tells the active player which suit
              they must follow before they attempt an illegal play. The domain
              rule is follow-suit-*if-able* ({@code Trick#play}), so the hint is
              suppressed when the hand holds none of the led suit; the component
              also owns the feature-flag gate. */}
          <FollowSuitHint
            enabled={isFollowSuitHintEnabled}
            isMyTurn={isMyTurn}
            ledSuit={trickState.trick?.ledSuit}
            canFollowLedSuit={hand.cards.some(card => card.suit === trickState.trick?.ledSuit)}
          />

          {/* Keyboard fallback: play selected card button */}
          {selectedCardId && isMyTurn && (
            <button
              type="button"
              className="govuk-button"
              disabled={isPlayingCard}
              onClick={handlePlaySelected}
            >
              {isPlayingCard ? 'Playing...' : 'Play selected card'}
            </button>
          )}

          {!isMyTurn && trickState.seatToPlay !== undefined && (
            <p className="govuk-hint">
              Waiting for {seatToPlayPlayer?.displayName ?? 'another player'} to play
            </p>
          )}
        </div>

        {/* Drag ghost */}
        {dragState && (() => {
          const draggedCard = hand.cards.find(c => c.cardId === dragState.cardId);
          if (!draggedCard) return null;
          const dx = dragState.currentX - dragState.startX;
          const dy = dragState.currentY - dragState.startY;
          return (
            <div
              aria-hidden="true"
              style={{
                position: 'fixed',
                left: dragState.startX + dx - 40,
                top: dragState.startY + dy - 60,
                pointerEvents: 'none',
                zIndex: 1000,
                opacity: 0.85,
                transform: 'rotate(5deg)',
              }}
            >
              <div style={{
                display: 'inline-flex',
                flexDirection: 'column',
                justifyContent: 'space-between',
                width: '80px',
                height: '120px',
                border: '2px solid #b1b4b6',
                borderRadius: '6px',
                padding: '6px',
                backgroundColor: '#ffffff',
                boxShadow: '4px 4px 12px rgba(0,0,0,0.3)',
              }}>
                {cardImagePath(draggedCard.suit, draggedCard.rank) ? (
                  <img
                    src={cardImagePath(draggedCard.suit, draggedCard.rank) as string}
                    alt={`${draggedCard.rankSymbol} of ${draggedCard.suit.toLowerCase().replace(/_/g, ' ')}`}
                    // Not strictly required — the ghost's wrapper sets `pointerEvents: 'none'`, so it can
                    // never be the press target that starts a native drag. Set anyway so the EOP-79
                    // invariant holds uniformly at every `<img>` on the drag surface, and so a future
                    // edit that gives the ghost pointer events cannot silently reintroduce the bug.
                    draggable={false}
                    style={{
                      width: '100%',
                      height: '100%',
                      objectFit: 'contain',
                      borderRadius: '4px',
                      display: 'block',
                      WebkitUserDrag: 'none',
                    } as React.CSSProperties}
                  />
                ) : (
                  <>
                    <div style={{ fontSize: '14px', fontWeight: 'bold', color: SUIT_COLOURS[draggedCard.suit] ?? '#0b0c0c' }}>
                      {draggedCard.rankSymbol}
                    </div>
                    <div style={{ fontSize: '11px', color: SUIT_COLOURS[draggedCard.suit] ?? '#0b0c0c', fontWeight: 'bold', textAlign: 'center' }}>
                      {SUIT_LABELS[draggedCard.suit] ?? draggedCard.suit[0]}
                    </div>
                    <div style={{ fontSize: '14px', fontWeight: 'bold', color: SUIT_COLOURS[draggedCard.suit] ?? '#0b0c0c', textAlign: 'right' }}>
                      {draggedCard.rankSymbol}
                    </div>
                  </>
                )}
              </div>
            </div>
          );
        })()}
      </main>
    </div>
  );
}
