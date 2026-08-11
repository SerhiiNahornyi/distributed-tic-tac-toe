package dev.nahornyi.tictactoe.session.domain;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.SessionStatus;
import dev.nahornyi.tictactoe.contracts.api.MoveRecord;
import dev.nahornyi.tictactoe.contracts.api.SessionResponse;
import dev.nahornyi.tictactoe.contracts.board.Board;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A simulation and its observed game state.
 *
 * <p>One thread writes (the simulation runner) while HTTP threads read for {@code GET /sessions}.
 * Mutable fields are therefore {@code volatile} and the move history is copy-on-write, so a reader
 * always sees a consistent snapshot rather than a half-applied move. {@link #tryStart()} is the
 * only compare-and-set in the class and is what makes a duplicate {@code /simulate} a no-op instead
 * of a second concurrent simulation of the same game.
 *
 * <p>The engine remains the source of truth for the board; what is held here is the last state the
 * engine reported.
 */
public class GameSession {

    private final UUID id;
    private final String strategy;
    private final Instant createdAt;
    private final List<MoveRecord> moves = new CopyOnWriteArrayList<>();
    private final AtomicReference<SessionStatus> status = new AtomicReference<>(SessionStatus.CREATED);

    private volatile GameStatus gameStatus = GameStatus.IN_PROGRESS;
    private volatile String board = Board.empty().asString();
    private volatile String failureReason;
    private volatile Instant updatedAt;

    public GameSession(UUID id, String strategy, Instant createdAt) {
        this.id = id;
        this.strategy = strategy;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * Moves the session from {@code CREATED} to {@code RUNNING}.
     *
     * @return {@code true} if this caller won the transition; {@code false} if a simulation was
     * already started, which is how repeated {@code /simulate} calls are made harmless
     */
    public boolean tryStart() {
        return status.compareAndSet(SessionStatus.CREATED, SessionStatus.RUNNING);
    }

    public void recordMove(MoveRecord move, Instant now) {
        moves.add(move);
        this.board = move.boardAfter();
        this.gameStatus = move.statusAfter();
        this.updatedAt = now;
    }

    /** Adopts a board observed from the engine without attributing it to a specific move. */
    public void syncFrom(String board, GameStatus gameStatus, Instant now) {
        this.board = board;
        this.gameStatus = gameStatus;
        this.updatedAt = now;
    }

    public void markFinished(Instant now) {
        status.set(SessionStatus.FINISHED);
        this.updatedAt = now;
    }

    public void markFailed(String reason, Instant now) {
        status.set(SessionStatus.FAILED);
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String strategy() {
        return strategy;
    }

    public SessionStatus status() {
        return status.get();
    }

    public GameStatus gameStatus() {
        return gameStatus;
    }

    public String board() {
        return board;
    }

    public int moveCount() {
        return moves.size();
    }

    public SessionResponse toResponse() {
        return new SessionResponse(
                id,
                status.get(),
                gameStatus,
                board,
                gameStatus.winner(),
                strategy,
                List.copyOf(moves),
                failureReason,
                createdAt,
                updatedAt);
    }
}
