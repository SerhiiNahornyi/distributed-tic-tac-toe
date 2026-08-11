package dev.nahornyi.tictactoe.contracts.api;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;

import java.util.UUID;

/**
 * The full state of a game as seen by callers of the Game Engine Service.
 *
 * @param board     nine characters, row-major, {@code '-'} for empty
 * @param nextTurn  the symbol to move, or {@code null} once the game is over
 * @param winner    the winning symbol, or {@code null} for a draw or an unfinished game
 */
public record GameStateResponse(
        UUID gameId,
        String board,
        GameStatus status,
        PlayerSymbol nextTurn,
        PlayerSymbol winner,
        int moveCount) {
}
