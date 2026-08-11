package dev.nahornyi.tictactoe.session.service;

import dev.nahornyi.tictactoe.contracts.api.CreateSessionRequest;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.SessionResponse;
import dev.nahornyi.tictactoe.contracts.event.SessionCreated;
import dev.nahornyi.tictactoe.contracts.event.SimulationStarted;
import dev.nahornyi.tictactoe.session.client.GameEngineClient;
import dev.nahornyi.tictactoe.session.domain.GameSession;
import dev.nahornyi.tictactoe.session.domain.SessionRepository;
import dev.nahornyi.tictactoe.session.domain.SimulationAlreadyStartedException;
import dev.nahornyi.tictactoe.session.event.SessionEventPublisher;
import dev.nahornyi.tictactoe.session.strategy.MoveStrategies;
import dev.nahornyi.tictactoe.session.strategy.MoveStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Session lifecycle: create, start, read. The move loop itself lives in {@link SimulationRunner}
 * because it runs on another thread.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository repository;
    private final GameEngineClient engineClient;
    private final MoveStrategies strategies;
    private final SessionEventPublisher events;
    private final SimulationRunner simulationRunner;
    private final Clock clock;

    public SessionService(SessionRepository repository,
                          GameEngineClient engineClient,
                          MoveStrategies strategies,
                          SessionEventPublisher events,
                          SimulationRunner simulationRunner,
                          Clock clock) {
        this.repository = repository;
        this.engineClient = engineClient;
        this.strategies = strategies;
        this.events = events;
        this.simulationRunner = simulationRunner;
        this.clock = clock;
    }

    /**
     * Creates a session and, in the same call, the backing game in the engine.
     *
     * <p>The game is created eagerly rather than lazily at first move so that an unreachable engine
     * is reported by {@code POST /sessions} - the request the user is waiting on - instead of
     * surfacing later as a mysteriously failed simulation.
     */
    public SessionResponse createSession(CreateSessionRequest request) {
        MoveStrategy strategy = strategies.resolve(request == null ? null : request.strategy());
        UUID sessionId = UUID.randomUUID();

        // The session id doubles as the engine's game id, so one identifier correlates the session,
        // the game and every event published about them.
        GameStateResponse game = engineClient.createGame(sessionId);
        log.info("Created session {} backed by game {} using the '{}' strategy",
                sessionId, game.gameId(), strategy.name());

        Instant now = clock.instant();
        GameSession session = repository.save(new GameSession(sessionId, strategy.name(), now));
        events.publish(new SessionCreated(sessionId, strategy.name(), now));

        return session.toResponse();
    }

    /**
     * Accepts a simulation request and hands the work to a background thread.
     *
     * @throws SimulationAlreadyStartedException if this session has already been simulated, which
     *                                           makes a duplicate request a clear 409 rather than
     *                                           two threads fighting over one game
     */
    public SessionResponse startSimulation(UUID sessionId) {
        GameSession session = repository.getOrThrow(sessionId);

        if (!session.tryStart()) {
            throw new SimulationAlreadyStartedException(sessionId, session.status());
        }

        events.publish(new SimulationStarted(sessionId, clock.instant()));
        simulationRunner.run(sessionId);
        log.info("Simulation queued for session {}", sessionId);

        return session.toResponse();
    }

    public SessionResponse getSession(UUID sessionId) {
        return repository.getOrThrow(sessionId).toResponse();
    }
}
