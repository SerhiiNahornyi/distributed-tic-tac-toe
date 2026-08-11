package dev.nahornyi.tictactoe.session.strategy;

import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.board.Board;

/**
 * Picks the next move for an automated player.
 *
 * <p>An interface rather than an if-else in the runner: the assignment allows either a random or a
 * rule-based algorithm, and keeping them behind one abstraction means both are exercised by the
 * same simulation code and can be swapped per request.
 *
 * <p>Implementations only propose moves. The Game Engine Service is still the authority that
 * validates them, so a buggy strategy produces a rejected move, never corrupt state.
 */
public interface MoveStrategy {

    /** Identifier used in configuration and in the {@code strategy} field of a create request. */
    String name();

    /**
     * @param board  the current board, guaranteed to have at least one free cell
     * @param symbol the symbol to place
     * @return a free cell index in 0-8
     */
    int chooseMove(Board board, PlayerSymbol symbol);
}
