export type PlayerSymbol = 'X' | 'O';
export type GameStatus = 'IN_PROGRESS' | 'X_WON' | 'O_WON' | 'DRAW';
export type SessionStatus = 'CREATED' | 'RUNNING' | 'FINISHED' | 'FAILED';

export const EMPTY_BOARD = '---------';

export interface MoveRecord {
  moveNumber: number;
  symbol: PlayerSymbol;
  position: number;
  boardAfter: string;
  statusAfter: GameStatus;
  playedAt: string;
}

export interface SessionResponse {
  sessionId: string;
  sessionStatus: SessionStatus;
  gameStatus: GameStatus;
  board: string;
  winner: PlayerSymbol | null;
  strategy: string;
  moves: MoveRecord[];
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Turns a failed response into a readable message.
 *
 * Every backend error is RFC 7807, so `detail` is the human-readable sentence and `code` is the
 * stable identifier. Falling back through both keeps the UI useful even if something upstream
 * answers with something else entirely (a proxy error page, say).
 */
async function describeFailure(response: Response): Promise<string> {
  try {
    const problem = await response.json();
    return problem.detail || problem.title || problem.code || `Request failed with ${response.status}`;
  } catch {
    return `Request failed with ${response.status} ${response.statusText}`;
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });

  if (!response.ok) {
    throw new Error(await describeFailure(response));
  }

  return (await response.json()) as T;
}

export function createSession(strategy: string): Promise<SessionResponse> {
  return request<SessionResponse>('/api/sessions', {
    method: 'POST',
    body: JSON.stringify({ strategy }),
  });
}

export function startSimulation(sessionId: string): Promise<SessionResponse> {
  return request<SessionResponse>(`/api/sessions/${sessionId}/simulate`, { method: 'POST' });
}

export function fetchSession(sessionId: string): Promise<SessionResponse> {
  return request<SessionResponse>(`/api/sessions/${sessionId}`);
}
