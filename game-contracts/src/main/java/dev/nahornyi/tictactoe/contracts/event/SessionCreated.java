package dev.nahornyi.tictactoe.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** Published by the Game Session Service when a session and its backing game exist. */
public record SessionCreated(UUID gameId, String strategy, Instant occurredAt) implements GameEvent {

    @Override
    public String eventName() {
        return "session-created";
    }
}
