package dev.nahornyi.tictactoe.engine.domain;

/** Thrown by {@link Game} when a move violates the rules. Never retried by clients. */
public class IllegalMoveException extends RuntimeException {

    private final transient MoveRejection rejection;

    public IllegalMoveException(MoveRejection rejection, String detail) {
        super(detail);
        this.rejection = rejection;
    }

    public MoveRejection rejection() {
        return rejection;
    }
}
