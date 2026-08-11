package dev.nahornyi.tictactoe.contracts.api;

import java.util.UUID;

/**
 * @param gameId optional caller-supplied identifier. The Game Session Service passes its own
 *               {@code sessionId} here so that one identifier correlates a session, a game and
 *               every event emitted about them. When {@code null} the engine generates one.
 */
public record CreateGameRequest(UUID gameId) {
}
