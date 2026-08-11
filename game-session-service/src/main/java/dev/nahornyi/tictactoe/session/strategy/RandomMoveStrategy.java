package dev.nahornyi.tictactoe.session.strategy;

import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.board.Board;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Plays a uniformly random free cell.
 *
 * <p>The {@link Random} is injectable so tests can seed it and assert on an exact game, rather
 * than asserting only that "something legal happened".
 */
@Component
public class RandomMoveStrategy implements MoveStrategy {

    public static final String NAME = "random";

    private final Random random;

    public RandomMoveStrategy() {
        this(new Random());
    }

    public RandomMoveStrategy(Random random) {
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
        return free.get(random.nextInt(free.size()));
    }
}
