package dev.nahornyi.tictactoe.contracts.api;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;

import java.time.Instant;

/**
 * One entry in a session's move history. Carries the resulting board so the UI can replay the game
 * without asking the engine for each intermediate state.
 *
 * @param moveNumber 1-based ordinal of the move within the game
 */
public record MoveRecord(
        int moveNumber,
        PlayerSymbol symbol,
        int position,
        String boardAfter,
        GameStatus statusAfter,
        Instant playedAt) {
}
