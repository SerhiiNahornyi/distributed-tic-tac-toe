package dev.nahornyi.tictactoe.session.strategy;

import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.board.Board;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The rule-based option from the assignment: win if a winning cell exists, otherwise deny the
 * opponent theirs, otherwise prefer the centre, then a corner, then anything free.
 *
 * <p>Not a solver - it looks exactly one move ahead, so two blocking players still reach a win or a
 * draw rather than always drawing. That is intentional: a perfect player would make every
 * simulation identical and the UI dull to watch.
 */
@Component
public class BlockingMoveStrategy implements MoveStrategy {

    public static final String NAME = "blocking";

    private static final int CENTRE = 4;
    private static final int[] CORNERS = {0, 2, 6, 8};

    private final Random random;

    public BlockingMoveStrategy() {
        this(new Random());
    }

    public BlockingMoveStrategy(Random random) {
        this.random = random;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int chooseMove(Board board, PlayerSymbol symbol) {
        List<Integer> free = board.emptyPositions();
        if (free.isEmpty()) {
            throw new IllegalStateException("No free cells on board " + board.asString());
        }

        return winningMoveFor(board, symbol, free)
                .or(() -> winningMoveFor(board, symbol.opponent(), free))
                .or(() -> free.contains(CENTRE) ? Optional.of(CENTRE) : Optional.empty())
                .or(() -> firstFreeCorner(free))
                .orElseGet(() -> free.get(random.nextInt(free.size())));
    }

    /** The cell, if any, that immediately completes a line for {@code symbol}. */
    private Optional<Integer> winningMoveFor(Board board, PlayerSymbol symbol, List<Integer> free) {
        return free.stream()
                .filter(position -> board.place(symbol, position).winner().filter(symbol::equals).isPresent())
                .findFirst();
    }

    private Optional<Integer> firstFreeCorner(List<Integer> free) {
        for (int corner : CORNERS) {
            if (free.contains(corner)) {
                return Optional.of(corner);
            }
        }
        return Optional.empty();
    }
}
