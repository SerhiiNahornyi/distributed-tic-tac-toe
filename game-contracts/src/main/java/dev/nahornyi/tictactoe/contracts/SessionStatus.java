package dev.nahornyi.tictactoe.contracts;

/**
 * Lifecycle of a session, which is distinct from the {@link GameStatus} of the underlying game:
 * a simulation can fail (engine unreachable) while the game itself is still {@code IN_PROGRESS}.
 */
public enum SessionStatus {

    CREATED,
    RUNNING,
    FINISHED,
    FAILED;

    public boolean isTerminal() {
        return this == FINISHED || this == FAILED;
    }
}
