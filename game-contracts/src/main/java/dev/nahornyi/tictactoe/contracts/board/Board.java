package dev.nahornyi.tictactoe.contracts.board;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Immutable 3x3 board, serialised as a nine character string in row-major order where
 * {@code '-'} marks an empty cell (for example {@code "X-O--X---"}).
 *
 * <p>This type lives in the shared contracts module as a <em>shared kernel</em>: both the Game
 * Engine Service (which owns the rules) and the Game Session Service (whose rule-based move
 * strategy needs to reason about winning lines) operate on the same board semantics. Duplicating
 * the winning-line table in two services would be a correctness risk, so it is defined once here.
 * Mutating rules such as "whose turn is it" and "is this game over" stay in the engine.
 */
public final class Board {

    public static final int SIZE = 9;

    /** The eight ways to win: three rows, three columns, two diagonals. */
    private static final int[][] WINNING_LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };

    private final char[] cells;

    private Board(char[] cells) {
        this.cells = cells;
    }

    public static Board empty() {
        char[] cells = new char[SIZE];
        java.util.Arrays.fill(cells, PlayerSymbol.EMPTY_CELL);
        return new Board(cells);
    }

    /**
     * @throws IllegalArgumentException if the value is not nine characters of {@code X}, {@code O}
     *                                  or {@code -}
     */
    public static Board fromString(String value) {
        if (value == null || value.length() != SIZE) {
            throw new IllegalArgumentException("Board must be exactly " + SIZE + " characters, got: " + value);
        }
        for (char c : value.toCharArray()) {
            if (c != PlayerSymbol.EMPTY_CELL && c != 'X' && c != 'O') {
                throw new IllegalArgumentException("Illegal board character '" + c + "' in: " + value);
            }
        }
        return new Board(value.toCharArray());
    }

    public static boolean isValidPosition(int position) {
        return position >= 0 && position < SIZE;
    }

    public String asString() {
        return new String(cells);
    }

    public char at(int position) {
        requireValidPosition(position);
        return cells[position];
    }

    public boolean isOccupied(int position) {
        return at(position) != PlayerSymbol.EMPTY_CELL;
    }

    public List<Integer> emptyPositions() {
        List<Integer> free = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            if (cells[i] == PlayerSymbol.EMPTY_CELL) {
                free.add(i);
            }
        }
        return free;
    }

    public int moveCount() {
        int count = 0;
        for (char cell : cells) {
            if (cell != PlayerSymbol.EMPTY_CELL) {
                count++;
            }
        }
        return count;
    }

    /** X moves first, so the player to move follows from how many marks are already on the board. */
    public PlayerSymbol nextTurn() {
        return PlayerSymbol.forMoveNumber(moveCount());
    }

    /**
     * Returns a new board with {@code symbol} placed at {@code position}.
     *
     * @throws IllegalArgumentException if the position is off the board or already taken. Callers
     *                                  that need domain-specific errors validate before calling.
     */
    public Board place(PlayerSymbol symbol, int position) {
        requireValidPosition(position);
        if (isOccupied(position)) {
            throw new IllegalArgumentException("Cell " + position + " is already occupied by " + cells[position]);
        }
        char[] updated = cells.clone();
        updated[position] = symbol.mark();
        return new Board(updated);
    }

    public Optional<PlayerSymbol> winner() {
        for (int[] line : WINNING_LINES) {
            char first = cells[line[0]];
            if (first != PlayerSymbol.EMPTY_CELL && first == cells[line[1]] && first == cells[line[2]]) {
                return Optional.of(PlayerSymbol.valueOf(String.valueOf(first)));
            }
        }
        return Optional.empty();
    }

    public boolean isFull() {
        return moveCount() == SIZE;
    }

    /** Derives the game outcome from the board alone: a win takes precedence over a full board. */
    public GameStatus status() {
        return winner()
                .map(GameStatus::wonBy)
                .orElseGet(() -> isFull() ? GameStatus.DRAW : GameStatus.IN_PROGRESS);
    }

    private void requireValidPosition(int position) {
        if (!isValidPosition(position)) {
            throw new IllegalArgumentException("Position must be between 0 and " + (SIZE - 1) + ", got: " + position);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Board board && java.util.Arrays.equals(cells, board.cells);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(cells);
    }

    @Override
    public String toString() {
        return asString();
    }
}
