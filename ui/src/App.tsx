import React, { useState, useCallback } from 'react';
import { CreateSessionForm } from './components/CreateSessionForm';
import { JoinSessionForm } from './components/JoinSessionForm';
import { LobbyScreen } from './components/LobbyScreen';
import { GameScreen } from './components/GameScreen';
import { GameOverScreen } from './components/GameOverScreen';
import { CardCatalogue } from './components/CardCatalogue';
import type { SessionAdmissionDto, SessionStateDto } from './api';

// Storage key for session credentials
const STORAGE_KEY = 'eop_session';

// Stored session interface
interface StoredSession {
  readonly playerToken: string;
  readonly playerId: string;
  readonly sessionId: string;
}

/** Runtime type guard — rejects any stored object missing required string fields. */
function isStoredSession(value: unknown): value is StoredSession {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v['playerToken'] === 'string' &&
    typeof v['playerId'] === 'string' &&
    typeof v['sessionId'] === 'string'
  );
}

// View types
type View =
  | { readonly screen: 'home' }
  | { readonly screen: 'create' }
  | { readonly screen: 'join' }
  | { readonly screen: 'lobby'; readonly sessionId: string; readonly playerId: string; readonly playerToken: string }
  | { readonly screen: 'game'; readonly sessionId: string; readonly playerId: string; readonly playerToken: string; readonly session: SessionStateDto }
  | { readonly screen: 'game-over'; readonly sessionId: string; readonly playerId: string; readonly playerToken: string; readonly isFacilitator: boolean };

/**
 * The application shell with view switching logic.
 */
export default function App(): React.JSX.Element {
  // Feature flags — read at component scope so they are available throughout the
  // component, including in renderView.
  const isGameScreenEnabled = import.meta.env.VITE_GAME_SCREEN_ENABLED === 'true';

  const [view, setView] = useState<View>(() => {
    // Check if we have stored session credentials
    const stored = sessionStorage.getItem(STORAGE_KEY);
    if (stored) {
      try {
        const parsed: unknown = JSON.parse(stored);
        if (isStoredSession(parsed)) {
          return {
            screen: 'lobby',
            sessionId: parsed.sessionId,
            playerId: parsed.playerId,
            playerToken: parsed.playerToken
          };
        }
        // Stored object is missing required fields — discard it
        sessionStorage.removeItem(STORAGE_KEY);
      } catch {
        // Invalid stored data, clear it
        sessionStorage.removeItem(STORAGE_KEY);
      }
    }
    return { screen: 'home' };
  });

  // Handle session admission (after create/join)
  const handleSessionAdmission = (admission: SessionAdmissionDto) => {
    const storedSession: StoredSession = {
      playerToken: admission.playerToken,
      playerId: admission.playerId,
      sessionId: admission.session.sessionId
    };
    
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(storedSession));
    
    setView({
      screen: 'lobby',
      sessionId: admission.session.sessionId,
      playerId: admission.playerId,
      playerToken: admission.playerToken
    });
  };

  // Handle session end (logout/clear storage)
  const handleSessionEnd = useCallback(() => {
    sessionStorage.removeItem(STORAGE_KEY);
    setView({ screen: 'home' });
  }, []);

  // Handle errors from forms
  const handleError = (message: string) => {
    // Errors are displayed in the forms themselves
    console.error('Form error:', message);
  };

  // Render the appropriate view
  const renderView = () => {
    switch (view.screen) {
      case 'home':
        return <HomeView onViewChange={(screen) => setView({ screen })} />;
      
      case 'create':
        return (
          <div className="govuk-width-container">
            <main className="govuk-main-wrapper" id="main-content">
              <div className="govuk-grid-row">
                <div className="govuk-grid-column-two-thirds">
                  <CreateSessionForm 
                    onSubmit={handleSessionAdmission} 
                    onError={handleError} 
                  />
                </div>
              </div>
            </main>
          </div>
        );
      
      case 'join':
        return (
          <div className="govuk-width-container">
            <main className="govuk-main-wrapper" id="main-content">
              <div className="govuk-grid-row">
                <div className="govuk-grid-column-two-thirds">
                  <JoinSessionForm 
                    onSubmit={handleSessionAdmission} 
                    onError={handleError} 
                  />
                </div>
              </div>
            </main>
          </div>
        );
      
      case 'lobby':
        return (
          <LobbyScreen
            sessionId={view.sessionId}
            playerId={view.playerId}
            playerToken={view.playerToken}
            onSessionEnd={handleSessionEnd}
            onGameStarted={(session) => {
              if (isGameScreenEnabled) {
                setView({
                  screen: 'game',
                  sessionId: view.sessionId,
                  playerId: view.playerId,
                  playerToken: view.playerToken,
                  session
                });
              }
            }}
          />
        );
      
      case 'game':
        return (
          <GameScreen
            sessionId={view.sessionId}
            playerId={view.playerId}
            playerToken={view.playerToken}
            session={view.session}
            onSessionEnd={handleSessionEnd}
            onGameOver={() => {
              const isFacilitator = view.session.players.find(
                p => p.playerId === view.playerId
              )?.role === 'FACILITATOR';
              const tok = view.playerToken;
              setView({
                screen: 'game-over',
                sessionId: view.sessionId,
                playerId: view.playerId,
                isFacilitator,
                playerToken: tok
              });
            }}
          />
        );

      case 'game-over':
        return (
          <GameOverScreen
            sessionId={view.sessionId}
            playerToken={view.playerToken}
            isFacilitator={view.isFacilitator}
            onNewGame={(session) => {
              // A second game is under way — either the 204 from this
              // facilitator's own click, or this seat's subscription observing
              // IN_PROGRESS. Route straight to the game screen: NewGameUseCase
              // resets to IN_PROGRESS and deals in one transaction, so the deal
              // is already done, and SessionStatus.LOBBY is never re-entered by
              // any code path. This used to route through the lobby, which
              // worked only because LobbyScreen forwards on observing
              // IN_PROGRESS — a transition dressed up as a destination, and one
              // only the facilitator ever reached (EOP-233).
              const tok = view.playerToken;
              if (isGameScreenEnabled) {
                setView({
                  screen: 'game',
                  sessionId: view.sessionId,
                  playerId: view.playerId,
                  playerToken: tok,
                  session
                });
                return;
              }
              // With the game screen off there is nowhere to play, so hold the
              // seat in the lobby — the same refusal to advance that
              // onGameStarted makes above, so one flag means one behaviour
              // whichever screen the player came from.
              setView({
                screen: 'lobby',
                sessionId: view.sessionId,
                playerId: view.playerId,
                playerToken: tok
              });
            }}
            onSessionEnd={handleSessionEnd}
          />
        );
    }
  };

  return (
    <>
      <header className="govuk-header" data-module="govuk-header">
        <div className="govuk-header__container govuk-width-container">
          <div className="govuk-header__content">
            <span className="govuk-header__service-name">
              Elevation of Privilege
            </span>
          </div>
        </div>
      </header>

      {renderView()}

      <footer className="govuk-footer">
        <div className="govuk-width-container">
          <div className="govuk-footer__meta">
            <div className="govuk-footer__meta-item govuk-footer__meta-item--grow">
              <p className="govuk-footer__licence-description">
                Elevation of Privilege is{" "}
                <span className="govuk-!-font-weight-bold">
                  &copy; 2009 Microsoft Corporation
                </span>
                , licensed under{" "}
                <a
                  className="govuk-footer__link"
                  href="https://creativecommons.org/licenses/by/3.0/us/"
                >
                  Creative Commons Attribution 3.0 United States
                </a>. The threat prompts shown above are Microsoft&apos;s,
                transcribed from the published deck. Attribution is the only
                obligation the licence imposes, and it is discharged here rather
                than only in the repository.
              </p>
            </div>
          </div>
        </div>
      </footer>
    </>
  );
}

interface HomeViewProps {
  readonly onViewChange: (screen: 'create' | 'join') => void;
}

function HomeView({ onViewChange }: HomeViewProps): React.JSX.Element {
  return (
    <div className="govuk-width-container">
      <main className="govuk-main-wrapper" id="main-content">
        <h1 className="govuk-heading-xl">Threat modelling card game</h1>

        <p className="govuk-body-l">
          A digital version of the Elevation of Privilege card game, for teams
          who cannot share a table.
        </p>

        <div className="govuk-button-group">
          <button
            type="button"
            className="govuk-button"
            data-module="govuk-button"
            onClick={() => onViewChange('create')}
          >
            Create a session
          </button>
          <button
            type="button"
            className="govuk-button govuk-button--secondary"
            data-module="govuk-button"
            onClick={() => onViewChange('join')}
          >
            Join a session
          </button>
        </div>

        <h2 className="govuk-heading-l">The deck</h2>
        <CardCatalogue />
      </main>
    </div>
  );
}