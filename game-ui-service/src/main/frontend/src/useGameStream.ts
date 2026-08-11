import { useEffect, useReducer, useRef, useState } from 'react';
import {
  EMPTY_BOARD,
  GameStatus,
  MoveRecord,
  PlayerSymbol,
  SessionResponse,
  SessionStatus,
} from './api';

export type ConnectionState = 'idle' | 'connecting' | 'live' | 'reconnecting';

export interface GameState {
  board: string;
  gameStatus: GameStatus;
  sessionStatus: SessionStatus;
  winner: PlayerSymbol | null;
  moves: MoveRecord[];
  lastMove: number | null;
  failureReason: string | null;
}

const INITIAL_STATE: GameState = {
  board: EMPTY_BOARD,
  gameStatus: 'IN_PROGRESS',
  sessionStatus: 'CREATED',
  winner: null,
  moves: [],
  lastMove: null,
  failureReason: null,
};

type Action =
  | { type: 'snapshot'; session: SessionResponse }
  | { type: 'simulation-started' }
  | { type: 'move'; move: MoveRecord }
  | { type: 'finished'; status: GameStatus; winner: PlayerSymbol | null; board: string }
  | { type: 'failed'; reason: string }
  | { type: 'reset' };

function reducer(state: GameState, action: Action): GameState {
  switch (action.type) {
    case 'reset':
      return INITIAL_STATE;

    case 'snapshot':
      return {
        board: action.session.board,
        gameStatus: action.session.gameStatus,
        sessionStatus: action.session.sessionStatus,
        winner: action.session.winner,
        moves: action.session.moves,
        lastMove: action.session.moves.at(-1)?.position ?? null,
        failureReason: action.session.failureReason,
      };

    case 'simulation-started':
      return { ...state, sessionStatus: 'RUNNING' };

    case 'move':
      // Events are ordered per game by Kafka, but a reconnect can replay one that the snapshot
      // already contained. Keying on move number makes applying it twice a no-op.
      if (state.moves.some((move) => move.moveNumber === action.move.moveNumber)) {
        return state;
      }
      return {
        ...state,
        board: action.move.boardAfter,
        gameStatus: action.move.statusAfter,
        moves: [...state.moves, action.move],
        lastMove: action.move.position,
      };

    case 'finished':
      return {
        ...state,
        board: action.board,
        gameStatus: action.status,
        winner: action.winner,
        sessionStatus: 'FINISHED',
      };

    case 'failed':
      return { ...state, sessionStatus: 'FAILED', failureReason: action.reason };

    default:
      return state;
  }
}

/**
 * Subscribes to one game's server-sent event stream.
 *
 * The backend sends a snapshot of current state as soon as the stream opens and deltas after that,
 * so a page opened mid-game renders correctly instead of starting from an empty board.
 */
export function useGameStream(sessionId: string | null) {
  const [state, dispatch] = useReducer(reducer, INITIAL_STATE);
  const [connection, setConnection] = useState<ConnectionState>('idle');
  const [streamError, setStreamError] = useState<string | null>(null);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    dispatch({ type: 'reset' });
    setStreamError(null);

    if (!sessionId) {
      setConnection('idle');
      return;
    }

    setConnection('connecting');
    const source = new EventSource(`/api/sessions/${sessionId}/stream`);
    sourceRef.current = source;

    const on = <T,>(name: string, handler: (payload: T) => void) => {
      source.addEventListener(name, (event) => {
        setConnection('live');
        handler(JSON.parse((event as MessageEvent).data) as T);
      });
    };

    on<SessionResponse>('snapshot', (session) => dispatch({ type: 'snapshot', session }));
    on<unknown>('session-created', () => undefined);
    on<unknown>('simulation-started', () => dispatch({ type: 'simulation-started' }));
    on<{ move: MoveRecord }>('move-applied', (event) => dispatch({ type: 'move', move: event.move }));
    on<{ status: GameStatus; winner: PlayerSymbol | null; board: string }>('game-finished', (event) =>
      dispatch({ type: 'finished', status: event.status, winner: event.winner, board: event.board }),
    );
    on<{ reason: string }>('simulation-failed', (event) =>
      dispatch({ type: 'failed', reason: event.reason }),
    );
    on<{ reason: string }>('stream-error', (event) => setStreamError(event.reason));

    source.onopen = () => {
      setConnection('live');
      setStreamError(null);
    };

    // EventSource reconnects on its own; surface that so the page can say so rather than looking
    // frozen while the browser retries in the background.
    source.onerror = () => setConnection('reconnecting');

    return () => {
      source.close();
      sourceRef.current = null;
      setConnection('idle');
    };
  }, [sessionId]);

  return { state, connection, streamError };
}
