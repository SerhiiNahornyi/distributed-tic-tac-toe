package dev.nahornyi.tictactoe.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** Published when the session service accepts a simulation request and begins generating moves. */
public record SimulationStarted(UUID gameId, Instant occurredAt) implements GameEvent {

    @Override
    public String eventName() {
        return "simulation-started";
    }
}
