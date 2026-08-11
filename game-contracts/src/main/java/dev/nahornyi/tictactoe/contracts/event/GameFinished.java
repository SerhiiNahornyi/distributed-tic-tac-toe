package dev.nahornyi.tictactoe.contracts.event;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the Game Engine Service when a move produces a terminal state.
 *
 * @param winner the winning symbol, or {@code null} for a draw
 */
public record GameFinished(
        UUID gameId,
        GameStatus status,
        PlayerSymbol winner,
        String board,
        int totalMoves,
        Instant occurredAt) implements GameEvent {

    @Override
    public String eventName() {
        return "game-finished";
    }
}
