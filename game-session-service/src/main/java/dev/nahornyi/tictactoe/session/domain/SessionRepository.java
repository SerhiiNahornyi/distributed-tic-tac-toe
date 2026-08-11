package dev.nahornyi.tictactoe.session.domain;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session store, as the assignment specifies.
 *
 * <p>Deliberately behind an interface-shaped class rather than exposing the map: swapping this for
 * Redis or Postgres later means replacing one file, not chasing map access across the codebase.
 * State is lost on restart - the trade-off is documented in the README.
 */
@Repository
public class SessionRepository {

    private final Map<UUID, GameSession> sessions = new ConcurrentHashMap<>();

    public GameSession save(GameSession session) {
        sessions.put(session.id(), session);
        return session;
    }

    public Optional<GameSession> findById(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public GameSession getOrThrow(UUID sessionId) {
        return findById(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    public int count() {
        return sessions.size();
    }
}
