package dev.nahornyi.tictactoe.session.domain;

import java.util.UUID;

public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(UUID sessionId) {
        super("No session with id " + sessionId);
    }
}
