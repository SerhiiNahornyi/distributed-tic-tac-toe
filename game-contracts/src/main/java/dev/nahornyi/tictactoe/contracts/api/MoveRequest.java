package dev.nahornyi.tictactoe.contracts.api;

import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A single move. Boxed {@link Integer} on purpose: with a primitive, a missing {@code position}
 * would silently default to 0 (a legal move) instead of failing validation.
 *
 * @param symbol   the mark to place
 * @param position cell index 0-8, row-major from the top-left
 */
public record MoveRequest(
        @NotNull PlayerSymbol symbol,
        @NotNull @Min(0) @Max(8) Integer position) {
}
