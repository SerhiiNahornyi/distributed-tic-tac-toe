package dev.nahornyi.tictactoe.session.service;

import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.MoveRecord;
import dev.nahornyi.tictactoe.contracts.board.Board;
import dev.nahornyi.tictactoe.contracts.event.SimulationFailed;
import dev.nahornyi.tictactoe.session.client.EngineRejectedRequestException;
import dev.nahornyi.tictactoe.session.client.GameEngineClient;
import dev.nahornyi.tictactoe.session.config.SessionProperties;
import dev.nahornyi.tictactoe.session.domain.GameSession;
import dev.nahornyi.tictactoe.session.domain.SessionRepository;
import dev.nahornyi.tictactoe.session.event.SessionEventPublisher;
import dev.nahornyi.tictactoe.session.strategy.MoveStrategies;
import dev.nahornyi.tictactoe.session.strategy.MoveStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Plays a whole game by generating moves for both players and sending each one to the Game Engine
 * Service.
 *
 * <p>Three properties are worth calling out:
 *
 * <ul>
 *   <li><b>The engine's response is the state.</b> After every move the loop continues from what
 *       the engine returned, never from a board reconstructed locally. The session cannot drift
 *       out of sync with the authority.</li>
 *   <li><b>It always terminates.</b> Every iteration is counted against {@code maxAttempts}, so
 *       even an engine that answers nonsense cannot spin this thread forever.</li>
 *   <li><b>A conflict is recoverable.</b> If the engine rejects a move because state moved on, the
 *       loop re-reads the game and carries on instead of failing the session.</li>
 * </ul>
 */
@Component
public class SimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);
    private static final String SESSION_MDC_KEY = "sessionId";

    private final SessionRepository repository;
    private final GameEngineClient engineClient;
    private final MoveStrategies strategies;
    private final SessionEventPublisher events;
    private final SessionProperties properties;
    private final Clock clock;

    public SimulationRunner(SessionRepository repository,
                            GameEngineClient engineClient,
                            MoveStrategies strategies,
                            SessionEventPublisher events,
                            SessionProperties properties,
                            Clock clock) {
        this.repository = repository;
        this.engineClient = engineClient;
        this.strategies = strategies;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs the simulation to completion on a background thread. Never throws to the caller: the
     * outcome is recorded on the session and announced as an event, which is what the UI observes.
     */
    @Async("simulationExecutor")
    public void run(UUID sessionId) {
        MDC.put(SESSION_MDC_KEY, sessionId.toString());
        try {
            simulate(repository.getOrThrow(sessionId));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(sessionId, "Simulation was interrupted during shutdown");
        } catch (RuntimeException failure) {
            log.error("Simulation failed for session {}", sessionId, failure);
            fail(sessionId, failure.getMessage());
        } finally {
            MDC.remove(SESSION_MDC_KEY);
        }
    }

    private void simulate(GameSession session) throws InterruptedException {
        UUID sessionId = session.id();
        MoveStrategy strategy = strategies.resolve(session.strategy());

        GameStateResponse state = engineClient.getGame(sessionId);
        session.syncFrom(state.board(), state.status(), clock.instant());

        int attempts = 0;
        while (!state.status().isTerminal()) {
            if (++attempts > properties.maxAttempts()) {
                throw new IllegalStateException(
                        "Simulation exceeded %d attempts without reaching a terminal state (board %s)"
                                .formatted(properties.maxAttempts(), state.board()));
            }

            // Purely for the benefit of the viewer: without a pause the game is over before the
            // browser has painted the first move.
            Thread.sleep(properties.moveDelay().toMillis());

            PlayerSymbol symbol = state.nextTurn();
            int position = strategy.chooseMove(Board.fromString(state.board()), symbol);

            try {
                state = engineClient.applyMove(sessionId, symbol, position);
            } catch (EngineRejectedRequestException rejection) {
                if (rejection.isConflict()) {
                    // Someone else moved on this game. Re-read the authoritative state and continue
                    // rather than failing a session that is still perfectly playable.
                    log.warn("Move {}@{} conflicted ({}); re-reading game state",
                            symbol, position, rejection.code());
                    state = engineClient.getGame(sessionId);
                    session.syncFrom(state.board(), state.status(), clock.instant());
                    continue;
                }
                throw rejection;
            }

            Instant now = clock.instant();
            session.recordMove(
                    new MoveRecord(state.moveCount(), symbol, position, state.board(), state.status(), now),
                    now);
        }

        session.markFinished(clock.instant());
        log.info("Session {} finished after {} moves with status {}",
                sessionId, session.moveCount(), state.status());
    }

    private void fail(UUID sessionId, String reason) {
        String message = reason == null ? "Simulation failed for an unknown reason" : reason;
        Instant now = clock.instant();
        repository.findById(sessionId).ifPresent(session -> session.markFailed(message, now));
        events.publish(new SimulationFailed(sessionId, message, now));
    }
}
