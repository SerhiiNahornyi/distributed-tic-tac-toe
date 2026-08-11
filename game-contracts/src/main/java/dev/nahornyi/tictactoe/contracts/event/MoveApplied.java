package dev.nahornyi.tictactoe.contracts.event;

import dev.nahornyi.tictactoe.contracts.api.MoveRecord;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the Game Engine Service after a move passes validation and is persisted. The engine
 * is the only writer of game state, so this is the authoritative record that a move happened.
 */
public record MoveApplied(UUID gameId, MoveRecord move, Instant occurredAt) implements GameEvent {

    @Override
    public String eventName() {
        return "move-applied";
    }
}
