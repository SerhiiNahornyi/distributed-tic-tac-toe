package dev.nahornyi.tictactoe.engine.domain;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.MoveRecord;
import dev.nahornyi.tictactoe.contracts.board.Board;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * The aggregate root and the single writer of game state.
 *
 * <p>Rules live here rather than in a service class: whoever holds a {@code Game} cannot put it
 * into an illegal state, because {@link #applyMove} is the only mutator and it validates first.
 *
 * <p>The board is stored as its nine character string form. That keeps the table trivial and means
 * the persisted value is the same representation the API returns. {@link Version} gives optimistic
 * locking, which is how concurrent moves on the same game are resolved: the second writer fails
 * rather than overwriting the first.
 */
@Entity
@Table(name = "games")
public class Game {

    @Id
    private UUID id;

    @Column(name = "board", nullable = false, length = Board.SIZE)
    private String board;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GameStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For JPA only. */
    protected Game() {
    }

    private Game(UUID id, Instant now) {
        this.id = id;
        this.board = Board.empty().asString();
        this.status = GameStatus.IN_PROGRESS;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Game create(UUID id, Instant now) {
        return new Game(id, now);
    }

    /**
     * Validates and applies a move.
     *
     * @return the resulting history entry
     * @throws IllegalMoveException if the game has finished, the position is off the board, the
     *                              cell is taken, or it is the other player's turn
     */
    public MoveRecord applyMove(PlayerSymbol symbol, int position, Instant now) {
        if (status.isTerminal()) {
            throw new IllegalMoveException(MoveRejection.GAME_ALREADY_FINISHED,
                    "Game %s already finished with status %s".formatted(id, status));
        }
        if (!Board.isValidPosition(position)) {
            throw new IllegalMoveException(MoveRejection.POSITION_OUT_OF_RANGE,
                    "Position %d is outside the board".formatted(position));
        }

        Board current = board();
        if (current.isOccupied(position)) {
            throw new IllegalMoveException(MoveRejection.CELL_OCCUPIED,
                    "Cell %d is already occupied by %s".formatted(position, current.at(position)));
        }
        if (current.nextTurn() != symbol) {
            throw new IllegalMoveException(MoveRejection.OUT_OF_TURN,
                    "It is %s's turn, but %s tried to move".formatted(current.nextTurn(), symbol));
        }

        Board updated = current.place(symbol, position);
        this.board = updated.asString();
        this.status = updated.status();
        this.updatedAt = now;

        return new MoveRecord(updated.moveCount(), symbol, position, this.board, this.status, now);
    }

    public Board board() {
        return Board.fromString(board);
    }

    public UUID id() {
        return id;
    }

    public GameStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public GameStateResponse toResponse() {
        Board current = board();
        return new GameStateResponse(
                id,
                board,
                status,
                status.isTerminal() ? null : current.nextTurn(),
                status.winner(),
                current.moveCount());
    }
}
