package dev.nahornyi.tictactoe.engine.domain;

import java.util.UUID;

public class GameNotFoundException extends RuntimeException {

    public GameNotFoundException(UUID gameId) {
        super("No game with id " + gameId);
    }
}
