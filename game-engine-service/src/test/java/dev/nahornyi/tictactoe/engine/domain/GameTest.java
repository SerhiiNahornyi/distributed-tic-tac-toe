package dev.nahornyi.tictactoe.engine.domain;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.api.MoveRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class GameTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private Game newGame() {
        return Game.create(UUID.randomUUID(), NOW);
    }

    @Test
    void aNewGameIsEmptyAndWaitingForX() {
        Game game = newGame();

        assertThat(game.board().asString()).isEqualTo("---------");
        assertThat(game.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(game.toResponse().nextTurn()).isEqualTo(PlayerSymbol.X);
    }

    @Test
    void aValidMoveUpdatesTheBoardAndReturnsAHistoryEntry() {
        Game game = newGame();

        MoveRecord move = game.applyMove(PlayerSymbol.X, 4, NOW);

        assertThat(move.moveNumber()).isEqualTo(1);
        assertThat(move.symbol()).isEqualTo(PlayerSymbol.X);
        assertThat(move.position()).isEqualTo(4);
        assertThat(move.boardAfter()).isEqualTo("----X----");
        assertThat(move.statusAfter()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(game.board().asString()).isEqualTo("----X----");
    }

    @Test
    @DisplayName("a winning move ends the game and names the winner")
    void aWinningMoveEndsTheGame() {
        Game game = newGame();
        // X: 0, 1, 2   O: 3, 4
        play(game, PlayerSymbol.X, 0);
        play(game, PlayerSymbol.O, 3);
        play(game, PlayerSymbol.X, 1);
        play(game, PlayerSymbol.O, 4);

        MoveRecord winning = game.applyMove(PlayerSymbol.X, 2, NOW);

        assertThat(winning.statusAfter()).isEqualTo(GameStatus.X_WON);
        assertThat(game.status()).isEqualTo(GameStatus.X_WON);
        assertThat(game.toResponse().winner()).isEqualTo(PlayerSymbol.X);
        assertThat(game.toResponse().nextTurn()).isNull();
    }

    @Test
    void aFullBoardWithoutALineIsADraw() {
        Game game = newGame();
        // X O X / X O O / O X X
        int[] positions = {0, 1, 2, 4, 3, 5, 7, 6, 8};
        for (int i = 0; i < positions.length; i++) {
            game.applyMove(PlayerSymbol.forMoveNumber(i), positions[i], NOW);
        }

        assertThat(game.board().asString()).isEqualTo("XOXXOOOXX");
        assertThat(game.status()).isEqualTo(GameStatus.DRAW);
        assertThat(game.toResponse().winner()).isNull();
    }

    @Test
    void rejectsAMoveIntoAnOccupiedCell() {
        Game game = newGame();
        play(game, PlayerSymbol.X, 4);

        assertThatExceptionOfType(IllegalMoveException.class)
                .isThrownBy(() -> game.applyMove(PlayerSymbol.O, 4, NOW))
                .satisfies(exception -> assertThat(exception.rejection()).isEqualTo(MoveRejection.CELL_OCCUPIED));
    }

    @Test
    void rejectsAMoveByThePlayerWhoseTurnItIsNot() {
        Game game = newGame();

        assertThatExceptionOfType(IllegalMoveException.class)
                .isThrownBy(() -> game.applyMove(PlayerSymbol.O, 0, NOW))
                .satisfies(exception -> assertThat(exception.rejection()).isEqualTo(MoveRejection.OUT_OF_TURN));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 9, 100})
    void rejectsAPositionOutsideTheBoard(int position) {
        Game game = newGame();

        assertThatExceptionOfType(IllegalMoveException.class)
                .isThrownBy(() -> game.applyMove(PlayerSymbol.X, position, NOW))
                .satisfies(exception ->
                        assertThat(exception.rejection()).isEqualTo(MoveRejection.POSITION_OUT_OF_RANGE));
    }

    @Test
    void rejectsAnyMoveOnceTheGameHasFinished() {
        Game game = newGame();
        play(game, PlayerSymbol.X, 0);
        play(game, PlayerSymbol.O, 3);
        play(game, PlayerSymbol.X, 1);
        play(game, PlayerSymbol.O, 4);
        play(game, PlayerSymbol.X, 2);

        assertThatExceptionOfType(IllegalMoveException.class)
                .isThrownBy(() -> game.applyMove(PlayerSymbol.O, 5, NOW))
                .satisfies(exception ->
                        assertThat(exception.rejection()).isEqualTo(MoveRejection.GAME_ALREADY_FINISHED));
    }

    /**
     * The out-of-range check runs before the occupancy check, which reads the cell. Without that
     * ordering an out-of-range position would blow up as an ArrayIndexOutOfBounds instead of a
     * domain error, and surface as a 500 rather than a 400.
     */
    @Test
    void checksTheBoundsBeforeReadingTheCell() {
        Game game = newGame();

        assertThatExceptionOfType(IllegalMoveException.class)
                .isThrownBy(() -> game.applyMove(PlayerSymbol.X, 99, NOW));
    }

    private void play(Game game, PlayerSymbol symbol, int position) {
        game.applyMove(symbol, position, NOW);
    }
}
