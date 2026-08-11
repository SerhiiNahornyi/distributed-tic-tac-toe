package dev.nahornyi.tictactoe.session.web;

import dev.nahornyi.tictactoe.session.client.EngineRejectedRequestException;
import dev.nahornyi.tictactoe.session.client.EngineUnavailableException;
import dev.nahornyi.tictactoe.session.domain.SessionNotFoundException;
import dev.nahornyi.tictactoe.session.domain.SimulationAlreadyStartedException;
import dev.nahornyi.tictactoe.session.strategy.MoveStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * RFC 7807 responses for session failures.
 *
 * <p>The interesting decisions are the two engine failures. An unreachable engine is this service's
 * dependency failing, so it is a 503 with {@code Retry-After} semantics for the caller. An engine
 * that actively rejected our request is a 502: the client did nothing wrong, but an upstream
 * response could not be used.
 */
@RestControllerAdvice
public class SessionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionExceptionHandler.class);
    private static final String ERROR_URN_PREFIX = "urn:tictactoe:error:";

    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(SessionNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "session-not-found", "Session not found", exception.getMessage());
    }

    @ExceptionHandler(SimulationAlreadyStartedException.class)
    public ProblemDetail handleAlreadyStarted(SimulationAlreadyStartedException exception) {
        return problem(HttpStatus.CONFLICT, "simulation-already-started", "Simulation already started",
                exception.getMessage());
    }

    @ExceptionHandler(MoveStrategies.UnknownStrategyException.class)
    public ProblemDetail handleUnknownStrategy(MoveStrategies.UnknownStrategyException exception) {
        return problem(HttpStatus.BAD_REQUEST, "unknown-strategy", "Unknown strategy", exception.getMessage());
    }

    @ExceptionHandler(EngineUnavailableException.class)
    public ProblemDetail handleEngineUnavailable(EngineUnavailableException exception) {
        log.error("Game Engine Service is unreachable", exception);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "engine-unavailable", "Game engine unavailable",
                "The Game Engine Service could not be reached after retries: " + exception.getMessage());
    }

    @ExceptionHandler(EngineRejectedRequestException.class)
    public ProblemDetail handleEngineRejection(EngineRejectedRequestException exception) {
        log.warn("Game Engine Service rejected a request: {}", exception.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "engine-rejected-request", "Game engine rejected the request",
                exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(ERROR_URN_PREFIX + code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
