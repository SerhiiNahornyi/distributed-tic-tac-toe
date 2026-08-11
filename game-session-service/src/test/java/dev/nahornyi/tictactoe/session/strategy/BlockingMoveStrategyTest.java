package dev.nahornyi.tictactoe.session.strategy;

import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.board.Board;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BlockingMoveStrategyTest {

    /** Seeded so the tie-breaking fallback is reproducible rather than flaky. */
    private final BlockingMoveStrategy strategy = new BlockingMoveStrategy(new Random(42));

    @Test
    @DisplayName("completes its own line when one move away from winning")
    void takesTheWinningCell() {
        Board board = Board.fromString("XX-------");

        assertThat(strategy.chooseMove(board, PlayerSymbol.X)).isEqualTo(2);
    }

    @Test
    @DisplayName("denies the opponent when they are one move away")
    void blocksTheOpponent() {
        Board board = Board.fromString("OO-X-----");

        assertThat(strategy.chooseMove(board, PlayerSymbol.X)).isEqualTo(2);
    }

    @Test
    @DisplayName("winning beats blocking when both are available")
    void prefersItsOwnWinOverABlock() {
        // X wins at 2; O would win at 3. Taking the win ends the game first.
        Board board = Board.fromString("XX--OO---");

        assertThat(strategy.chooseMove(board, PlayerSymbol.X)).isEqualTo(2);
    }

    @Test
    void takesTheCentreWhenThereIsNothingTactical() {
        assertThat(strategy.chooseMove(Board.empty(), PlayerSymbol.X)).isEqualTo(4);
    }

    @Test
    void fallsBackToACornerWhenTheCentreIsTaken() {
        Board board = Board.fromString("----X----");

        assertThat(strategy.chooseMove(board, PlayerSymbol.O)).isEqualTo(0);
    }

    @Test
    @DisplayName("never proposes an occupied or out-of-range cell, whatever the board")
    void alwaysReturnsAFreeCell() {
        Random random = new Random(7);

        for (int iteration = 0; iteration < 500; iteration++) {
            Board board = randomNonTerminalBoard(random);
            PlayerSymbol symbol = board.nextTurn();

            int position = strategy.chooseMove(board, symbol);

            assertThat(position).isBetween(0, Board.SIZE - 1);
            assertThat(board.isOccupied(position))
                    .as("chose occupied cell %d on board %s", position, board.asString())
                    .isFalse();
        }
    }

    /** Builds a legal, still-playable position by playing random legal moves. */
    private Board randomNonTerminalBoard(Random random) {
        Board board = Board.empty();
        int moves = random.nextInt(Board.SIZE);

        for (int i = 0; i < moves; i++) {
            if (board.status().isTerminal() || board.emptyPositions().isEmpty()) {
                break;
            }
            var free = board.emptyPositions();
            board = board.place(board.nextTurn(), free.get(random.nextInt(free.size())));
        }

        return board.status().isTerminal() ? Board.empty() : board;
    }
}
