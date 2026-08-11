package dev.nahornyi.tictactoe.contracts.api;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.SessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the UI needs to render a session: the lifecycle of the simulation, the state of the
 * game, and the full move history.
 *
 * @param sessionStatus  whether the simulation is queued, running, finished or failed
 * @param gameStatus     the outcome reported by the engine
 * @param failureReason  populated only when {@code sessionStatus} is {@code FAILED}
 */
public record SessionResponse(
        UUID sessionId,
        SessionStatus sessionStatus,
        GameStatus gameStatus,
        String board,
        PlayerSymbol winner,
        String strategy,
        List<MoveRecord> moves,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {
}
