package dev.nahornyi.tictactoe.contracts.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain events published to the {@code game-events} Kafka topic.
 *
 * <p>The Game Session Service reuses its {@code sessionId} as the engine's {@code gameId}, so a
 * single {@link #gameId()} correlates a session, its game and every event about them, and is used
 * as the Kafka message key. Keying by game guarantees that all events for one game land on the
 * same partition and are therefore consumed in order.
 *
 * <p>Sealed, so adding an event type is a compile error anywhere that switches over them rather
 * than a silently ignored message.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SessionCreated.class, name = "SESSION_CREATED"),
        @JsonSubTypes.Type(value = SimulationStarted.class, name = "SIMULATION_STARTED"),
        @JsonSubTypes.Type(value = MoveApplied.class, name = "MOVE_APPLIED"),
        @JsonSubTypes.Type(value = GameFinished.class, name = "GAME_FINISHED"),
        @JsonSubTypes.Type(value = SimulationFailed.class, name = "SIMULATION_FAILED")
})
public sealed interface GameEvent
        permits SessionCreated, SimulationStarted, MoveApplied, GameFinished, SimulationFailed {

    UUID gameId();

    Instant occurredAt();

    /** Stable name used as the SSE event name delivered to the browser. */
    String eventName();
}
