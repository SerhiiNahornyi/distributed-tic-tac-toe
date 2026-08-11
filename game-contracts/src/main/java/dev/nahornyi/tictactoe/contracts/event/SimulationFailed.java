package dev.nahornyi.tictactoe.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the Game Session Service when a simulation cannot continue, for example because the
 * engine is unreachable after retries. This is what lets the UI show a real error instead of
 * waiting forever for a move that will never arrive.
 */
public record SimulationFailed(UUID gameId, String reason, Instant occurredAt) implements GameEvent {

    @Override
    public String eventName() {
        return "simulation-failed";
    }
}
