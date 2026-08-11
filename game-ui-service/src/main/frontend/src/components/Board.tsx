import { GameStatus } from '../api';

const WINNING_LINES = [
  [0, 1, 2],
  [3, 4, 5],
  [6, 7, 8],
  [0, 3, 6],
  [1, 4, 7],
  [2, 5, 8],
  [0, 4, 8],
  [2, 4, 6],
];

/** The completed line, so the winning three can be highlighted once the game ends. */
function winningLine(board: string, status: GameStatus): number[] {
  if (status !== 'X_WON' && status !== 'O_WON') {
    return [];
  }
  return (
    WINNING_LINES.find(
      ([a, b, c]) => board[a] !== '-' && board[a] === board[b] && board[a] === board[c],
    ) ?? []
  );
}

interface BoardProps {
  board: string;
  status: GameStatus;
  lastMove: number | null;
}

export function Board({ board, status, lastMove }: BoardProps) {
  const winning = winningLine(board, status);

  return (
    <div className="board" role="grid" aria-label="Tic Tac Toe board">
      {Array.from(board).map((cell, index) => {
        const classes = ['cell'];
        if (cell !== '-') classes.push(`cell--${cell.toLowerCase()}`);
        if (index === lastMove) classes.push('cell--latest');
        if (winning.includes(index)) classes.push('cell--winning');

        return (
          <div
            key={index}
            className={classes.join(' ')}
            role="gridcell"
            aria-label={cell === '-' ? `Cell ${index + 1}, empty` : `Cell ${index + 1}, ${cell}`}
          >
            {cell === '-' ? '' : cell}
          </div>
        );
      })}
    </div>
  );
}
