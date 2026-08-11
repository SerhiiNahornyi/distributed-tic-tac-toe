import { useCallback, useState } from 'react';
import { createSession, startSimulation } from './api';
import { Board } from './components/Board';
import { ErrorBanner } from './components/ErrorBanner';
import { MoveLog } from './components/MoveLog';
import { StatusBanner } from './components/StatusBanner';
import { useGameStream } from './useGameStream';

const STRATEGIES = [
  { value: 'random', label: 'Random', hint: 'Both players pick any free cell' },
  { value: 'blocking', label: 'Rule-based', hint: 'Take the win, otherwise block' },
];

export default function App() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [strategy, setStrategy] = useState('random');
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { state, connection, streamError } = useGameStream(sessionId);

  const start = useCallback(async () => {
    setStarting(true);
    setError(null);

    try {
      const session = await createSession(strategy);

      // Subscribe before triggering the simulation. The other order is a race: the first moves can
      // land before the stream exists, and although the snapshot would recover them, the board
      // would visibly jump instead of filling in one cell at a time.
      setSessionId(session.sessionId);
      await startSimulation(session.sessionId);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not start the simulation');
      setSessionId(null);
    } finally {
      setStarting(false);
    }
  }, [strategy]);

  const running = state.sessionStatus === 'RUNNING';

  return (
    <div className="app">
      <header className="header">
        <h1 className="header__title">Distributed Tic Tac Toe</h1>
        <p className="header__subtitle">
          Two automated players, three microservices. Moves travel over REST, events over Kafka, and
          this page updates over SSE.
        </p>
      </header>

      {error && <ErrorBanner message={error} onDismiss={() => setError(null)} />}
      {streamError && <ErrorBanner message={streamError} />}
      {state.sessionStatus === 'FAILED' && state.failureReason && (
        <ErrorBanner message={`Simulation failed: ${state.failureReason}`} />
      )}

      <main className="layout">
        <section className="panel panel--board">
          <StatusBanner
            sessionStatus={state.sessionStatus}
            gameStatus={state.gameStatus}
            connection={connection}
            moveCount={state.moves.length}
          />

          <Board board={state.board} status={state.gameStatus} lastMove={state.lastMove} />

          <div className="controls">
            <label className="controls__field">
              <span className="controls__label">Move strategy</span>
              <select
                className="controls__select"
                value={strategy}
                disabled={running || starting}
                onChange={(event) => setStrategy(event.target.value)}
              >
                {STRATEGIES.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label} - {option.hint}
                  </option>
                ))}
              </select>
            </label>

            <button type="button" className="controls__start" onClick={start} disabled={running || starting}>
              {starting ? 'Starting...' : running ? 'Playing...' : 'Start simulation'}
            </button>
          </div>

          {sessionId && (
            <p className="session-id">
              Session <code>{sessionId}</code>
            </p>
          )}
        </section>

        <aside className="panel panel--log">
          <h2 className="panel__title">Move history</h2>
          <MoveLog moves={state.moves} />
        </aside>
      </main>
    </div>
  );
}
