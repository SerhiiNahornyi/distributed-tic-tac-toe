import { GameStatus, SessionStatus } from '../api';
import { ConnectionState } from '../useGameStream';

interface StatusBannerProps {
  sessionStatus: SessionStatus;
  gameStatus: GameStatus;
  connection: ConnectionState;
  moveCount: number;
}

function describe(sessionStatus: SessionStatus, gameStatus: GameStatus, moveCount: number): string {
  if (sessionStatus === 'FAILED') return 'Simulation failed';
  if (sessionStatus === 'CREATED') return 'Session ready';

  switch (gameStatus) {
    case 'X_WON':
      return 'X wins';
    case 'O_WON':
      return 'O wins';
    case 'DRAW':
      return 'Draw';
    default:
      return moveCount === 0 ? 'Waiting for the first move' : `In progress - ${moveCount} moves played`;
  }
}

function tone(sessionStatus: SessionStatus, gameStatus: GameStatus): string {
  if (sessionStatus === 'FAILED') return 'banner--error';
  if (gameStatus === 'X_WON' || gameStatus === 'O_WON') return 'banner--win';
  if (gameStatus === 'DRAW') return 'banner--draw';
  return 'banner--running';
}

export function StatusBanner({ sessionStatus, gameStatus, connection, moveCount }: StatusBannerProps) {
  return (
    <div className={`banner ${tone(sessionStatus, gameStatus)}`} role="status" aria-live="polite">
      <span className="banner__text">{describe(sessionStatus, gameStatus, moveCount)}</span>
      <span className={`connection connection--${connection}`}>
        <span className="connection__dot" aria-hidden="true" />
        {connection === 'live' ? 'live' : connection}
      </span>
    </div>
  );
}
