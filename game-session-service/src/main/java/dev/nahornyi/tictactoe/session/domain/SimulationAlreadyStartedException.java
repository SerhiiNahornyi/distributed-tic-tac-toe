package dev.nahornyi.tictactoe.session.domain;

import dev.nahornyi.tictactoe.contracts.SessionStatus;

import java.util.UUID;

/** Raised when {@code /simulate} is called on a session that is already running or has ended. */
public class SimulationAlreadyStartedException extends RuntimeException {

    public SimulationAlreadyStartedException(UUID sessionId, SessionStatus status) {
        super("Simulation for session %s cannot be started because it is %s".formatted(sessionId, status));
    }
}
