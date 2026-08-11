package dev.nahornyi.tictactoe.contracts;

/**
 * The two marks that can occupy a cell. {@link #EMPTY_CELL} is the board's placeholder for an
 * unoccupied cell and is intentionally not a symbol a player can own.
 */
public enum PlayerSymbol {

    X,
    O;

    public static final char EMPTY_CELL = '-';

    /** X always moves first, so the player to move is derived from the move count. */
    public static PlayerSymbol forMoveNumber(int movesPlayed) {
        return movesPlayed % 2 == 0 ? X : O;
    }

    public PlayerSymbol opponent() {
        return this == X ? O : X;
    }

    public char mark() {
        return name().charAt(0);
    }
}
