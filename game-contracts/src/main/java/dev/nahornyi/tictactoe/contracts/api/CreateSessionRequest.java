package dev.nahornyi.tictactoe.contracts.api;

/**
 * @param strategy optional override of the configured move strategy, {@code "random"} or
 *                 {@code "blocking"}. Null falls back to the service default.
 */
public record CreateSessionRequest(String strategy) {
}
