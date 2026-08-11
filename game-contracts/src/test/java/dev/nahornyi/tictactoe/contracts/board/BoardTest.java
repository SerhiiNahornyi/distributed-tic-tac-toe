package dev.nahornyi.tictactoe.contracts.board;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BoardTest {

    @Nested
    @DisplayName("winning lines")
    class WinningLines {

        @ParameterizedTest(name = "X wins with board {0}")
        @ValueSource(strings = {
                "XXXOO----", // top row
                "OO-XXX---", // middle row
                "OO----XXX", // bottom row
                "X--X--X-O", // left column
                "-X--X-OX-", // middle column
                "O-X--X--X", // right column
                "X---X---X", // main diagonal
                "--X-X-X-O"  // anti diagonal
        })
        void detectsEveryWinningLineForX(String board) {
            assertThat(Board.fromString(board).winner()).contains(PlayerSymbol.X);
            assertThat(Board.fromString(board).status()).isEqualTo(GameStatus.X_WON);
        }

        @Test
        void detectsAWinForO() {
            Board board = Board.fromString("OOOXX----");

            assertThat(board.winner()).contains(PlayerSymbol.O);
            assertThat(board.status()).isEqualTo(GameStatus.O_WON);
        }

        @Test
        void doesNotTreatThreeEmptyCellsAsAWin() {
            assertThat(Board.empty().winner()).isEmpty();
            assertThat(Board.empty().status()).isEqualTo(GameStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("terminal states")
    class TerminalStates {

        @Test
        void reportsDrawWhenBoardIsFullWithoutAWinner() {
            Board board = Board.fromString("XOXXOOOXX");

            assertThat(board.isFull()).isTrue();
            assertThat(board.winner()).isEmpty();
            assertThat(board.status()).isEqualTo(GameStatus.DRAW);
        }

        @Test
        void prefersWinOverDrawOnAFullBoard() {
            Board board = Board.fromString("XXXOOXOXO");

            assertThat(board.isFull()).isTrue();
            assertThat(board.status()).isEqualTo(GameStatus.X_WON);
        }
    }

    @Nested
    @DisplayName("placing marks")
    class PlacingMarks {

        @Test
        void placeReturnsANewBoardAndLeavesTheOriginalUntouched() {
            Board empty = Board.empty();

            Board afterMove = empty.place(PlayerSymbol.X, 4);

            assertThat(afterMove.asString()).isEqualTo("----X----");
            assertThat(empty.asString()).isEqualTo("---------");
        }

        @Test
        void rejectsAnOccupiedCell() {
            Board board = Board.empty().place(PlayerSymbol.X, 0);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> board.place(PlayerSymbol.O, 0))
                    .withMessageContaining("already occupied");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 9, 42})
        void rejectsAPositionOffTheBoard(int position) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Board.empty().place(PlayerSymbol.X, position))
                    .withMessageContaining("between 0 and 8");
        }
    }

    @Nested
    @DisplayName("turn order and free cells")
    class TurnOrder {

        @Test
        void xMovesFirstAndPlayersAlternate() {
            assertThat(Board.empty().nextTurn()).isEqualTo(PlayerSymbol.X);
            assertThat(Board.fromString("X--------").nextTurn()).isEqualTo(PlayerSymbol.O);
            assertThat(Board.fromString("XO-------").nextTurn()).isEqualTo(PlayerSymbol.X);
        }

        @Test
        void listsOnlyUnoccupiedPositions() {
            assertThat(Board.fromString("XO-X-----").emptyPositions())
                    .containsExactly(2, 4, 5, 6, 7, 8);
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        void rejectsWrongLength() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Board.fromString("XOX"))
                    .withMessageContaining("exactly 9 characters");
        }

        @Test
        void rejectsUnknownCharacters() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Board.fromString("XOZ------"))
                    .withMessageContaining("Illegal board character");
        }

        @Test
        void roundTripsThroughItsStringForm() {
            String value = "XO-XO-X--";

            assertThat(Board.fromString(value).asString()).isEqualTo(value);
            assertThat(Board.fromString(value)).isEqualTo(Board.fromString(value));
        }
    }
}
