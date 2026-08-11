package dev.nahornyi.tictactoe.engine.web;

import dev.nahornyi.tictactoe.engine.domain.GameNotFoundException;
import dev.nahornyi.tictactoe.engine.domain.IllegalMoveException;
import dev.nahornyi.tictactoe.engine.domain.MoveRejection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Translates domain failures into RFC 7807 {@code application/problem+json} responses.
 *
 * <p>Every response carries a stable {@code type} URN and a {@code code} property, so the Game
 * Session Service can branch on the reason without parsing prose. The distinction that matters
 * most to callers is 4xx (never retry, the move is wrong) versus 5xx (safe to retry).
 */
@RestControllerAdvice
public class GameExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GameExceptionHandler.class);
    private static final String ERROR_URN_PREFIX = "urn:tictactoe:error:";

    @ExceptionHandler(GameNotFoundException.class)
    public ProblemDetail handleGameNotFound(GameNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "game-not-found", "Game not found", exception.getMessage());
    }

    @ExceptionHandler(IllegalMoveException.class)
    public ProblemDetail handleIllegalMove(IllegalMoveException exception) {
        MoveRejection rejection = exception.rejection();
        HttpStatus status = rejection == MoveRejection.POSITION_OUT_OF_RANGE
                ? HttpStatus.BAD_REQUEST   // the request itself is malformed
                : HttpStatus.CONFLICT;     // well-formed, but it conflicts with current state

        log.debug("Rejected move: {} - {}", rejection, exception.getMessage());
        return problem(status, rejection.code(), rejection.title(), exception.getMessage());
    }

    /**
     * Two moves raced on the same game and this one lost. The move was never applied, so the caller
     * may safely re-read the state and try again - hence 409 rather than 500.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentModification(ObjectOptimisticLockingFailureException exception) {
        log.warn("Concurrent modification detected: {}", exception.getMessage());
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent modification",
                "Another move was applied to this game at the same time. Re-read the game state and retry.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(ERROR_URN_PREFIX + code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
