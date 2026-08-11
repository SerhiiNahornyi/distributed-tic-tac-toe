package dev.nahornyi.tictactoe.ui.web;

import dev.nahornyi.tictactoe.ui.client.SessionServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class UiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UiExceptionHandler.class);

    @ExceptionHandler(SessionServiceClient.SessionServiceUnavailableException.class)
    public ProblemDetail handleSessionServiceDown(SessionServiceClient.SessionServiceUnavailableException e) {
        log.error("Game Session Service is unreachable", e);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setType(URI.create("urn:tictactoe:error:session-service-unavailable"));
        problem.setTitle("Game session service unavailable");
        problem.setProperty("code", "session-service-unavailable");
        return problem;
    }
}
