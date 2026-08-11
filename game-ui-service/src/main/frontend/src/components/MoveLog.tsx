import { MoveRecord } from '../api';

const CELL_NAMES = [
  'top left',
  'top centre',
  'top right',
  'middle left',
  'centre',
  'middle right',
  'bottom left',
  'bottom centre',
  'bottom right',
];

export function MoveLog({ moves }: { moves: MoveRecord[] }) {
  if (moves.length === 0) {
    return <p className="log__empty">No moves yet.</p>;
  }

  return (
    <ol className="log">
      {moves.map((move) => (
        <li key={move.moveNumber} className="log__entry">
          <span className={`log__symbol log__symbol--${move.symbol.toLowerCase()}`}>{move.symbol}</span>
          <span className="log__cell">{CELL_NAMES[move.position]}</span>
          <span className="log__index">#{move.moveNumber}</span>
        </li>
      ))}
    </ol>
  );
}
