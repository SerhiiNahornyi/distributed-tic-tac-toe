package dev.nahornyi.tictactoe.engine.service;

import dev.nahornyi.tictactoe.contracts.api.CreateGameRequest;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.MoveRecord;
import dev.nahornyi.tictactoe.contracts.api.MoveRequest;
import dev.nahornyi.tictactoe.contracts.event.GameFinished;
import dev.nahornyi.tictactoe.contracts.event.MoveApplied;
import dev.nahornyi.tictactoe.engine.domain.Game;
import dev.nahornyi.tictactoe.engine.domain.GameNotFoundException;
import dev.nahornyi.tictactoe.engine.domain.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Application service around the {@link Game} aggregate. It owns transactions and event
 * publication; the rules themselves stay in the aggregate.
 *
 * <p>Events are published through Spring's {@link ApplicationEventPublisher} rather than sent to
 * Kafka inline, so that {@code GameEventPublisher} can forward them only after the transaction
 * commits. Publishing inline would let a rolled-back move be announced as if it had happened.
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final GameRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public GameService(GameRepository repository, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Creates a game, or returns the existing one when the caller supplies an id it already used.
     *
     * <p>Deliberately idempotent: the Game Session Service retries failed calls, and a retry that
     * arrives after a successful-but-unacknowledged create must not fail with a conflict.
     */
    @Transactional
    public GameStateResponse createGame(CreateGameRequest request) {
        UUID gameId = request == null || request.gameId() == null ? UUID.randomUUID() : request.gameId();

        Game game = repository.findById(gameId)
                .orElseGet(() -> {
                    log.info("Creating game {}", gameId);
                    return repository.save(Game.create(gameId, clock.instant()));
                });

        return game.toResponse();
    }

    /**
     * Validates and applies a move, then reports the resulting state.
     *
     * @throws GameNotFoundException                                            if the game is unknown
     * @throws dev.nahornyi.tictactoe.engine.domain.IllegalMoveException        if the move breaks the rules
     */
    @Transactional
    public GameStateResponse applyMove(UUID gameId, MoveRequest request) {
        Game game = repository.findById(gameId).orElseThrow(() -> new GameNotFoundException(gameId));

        MoveRecord move = game.applyMove(request.symbol(), request.position(), clock.instant());

        // Flush inside the transaction so an optimistic lock conflict surfaces here, as a 409,
        // rather than escaping the handler as an unmapped commit-time failure.
        repository.saveAndFlush(game);

        log.info("Game {} move {}: {} -> {} ({})",
                gameId, move.moveNumber(), request.symbol(), request.position(), game.status());

        Instant now = move.playedAt();
        events.publishEvent(new MoveApplied(gameId, move, now));
        if (game.status().isTerminal()) {
            events.publishEvent(new GameFinished(
                    gameId,
                    game.status(),
                    game.status().winner(),
                    game.board().asString(),
                    game.board().moveCount(),
                    now));
        }

        return game.toResponse();
    }

    @Transactional(readOnly = true)
    public GameStateResponse getGame(UUID gameId) {
        return repository.findById(gameId)
                .map(Game::toResponse)
                .orElseThrow(() -> new GameNotFoundException(gameId));
    }
}
