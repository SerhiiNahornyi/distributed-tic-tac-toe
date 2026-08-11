package dev.nahornyi.tictactoe.engine.domain;

/**
 * Why a move was refused. Modelled as a closed set rather than as free-text messages so that
 * clients can branch on a stable machine-readable code, and so the HTTP mapping lives in one place.
 */
public enum MoveRejection {

    /** The requested cell is outside the 3x3 board. A malformed request, hence 400. */
    POSITION_OUT_OF_RANGE("position-out-of-range", "Position is outside the board"),

    /** The cell already holds a mark. The request was well-formed but conflicts with state. */
    CELL_OCCUPIED("cell-occupied", "Cell is already occupied"),

    /** The other player is to move. X always moves first. */
    OUT_OF_TURN("out-of-turn", "It is not that player's turn"),

    /** The game already ended in a win or a draw. */
    GAME_ALREADY_FINISHED("game-already-finished", "Game has already finished");

    private final String code;
    private final String title;

    MoveRejection(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }
}
