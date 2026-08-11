package dev.nahornyi.tictactoe.session.strategy;

import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.board.Board;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RandomMoveStrategyTest {

    @Test
    void onlyEverChoosesAFreeCell() {
        RandomMoveStrategy strategy = new RandomMoveStrategy(new Random(1));
        Board board = Board.fromString("XO-XO-X--");

        Set<Integer> chosen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            chosen.add(strategy.chooseMove(board, PlayerSymbol.O));
        }

        assertThat(chosen).isSubsetOf(board.emptyPositions());
    }

    @Test
    void spreadsAcrossTheAvailableCellsRatherThanAlwaysPickingTheFirst() {
        RandomMoveStrategy strategy = new RandomMoveStrategy(new Random(1));
        Board board = Board.empty();

        Set<Integer> chosen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            chosen.add(strategy.chooseMove(board, PlayerSymbol.X));
        }

        assertThat(chosen).hasSize(Board.SIZE);
    }

    @Test
    void aSeededStrategyIsReproducible() {
        Board board = Board.empty();

        int first = new RandomMoveStrategy(new Random(99)).chooseMove(board, PlayerSymbol.X);
        int second = new RandomMoveStrategy(new Random(99)).chooseMove(board, PlayerSymbol.X);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void refusesToChooseOnAFullBoard() {
        RandomMoveStrategy strategy = new RandomMoveStrategy(new Random(1));
        Board full = Board.fromString("XOXXOOOXX");

        assertThatIllegalStateException().isThrownBy(() -> strategy.chooseMove(full, PlayerSymbol.X));
    }
}
