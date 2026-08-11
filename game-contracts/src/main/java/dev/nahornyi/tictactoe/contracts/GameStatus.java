package dev.nahornyi.tictactoe.contracts;

/** Outcome of a game as reported by the Game Engine Service. */
public enum GameStatus {

    IN_PROGRESS,
    X_WON,
    O_WON,
    DRAW;

    public static GameStatus wonBy(PlayerSymbol symbol) {
        return symbol == PlayerSymbol.X ? X_WON : O_WON;
    }

    public boolean isTerminal() {
        return this != IN_PROGRESS;
    }

    /** The winning symbol, or {@code null} for a draw or an unfinished game. */
    public PlayerSymbol winner() {
        return switch (this) {
            case X_WON -> PlayerSymbol.X;
            case O_WON -> PlayerSymbol.O;
            case DRAW, IN_PROGRESS -> null;
        };
    }
}
