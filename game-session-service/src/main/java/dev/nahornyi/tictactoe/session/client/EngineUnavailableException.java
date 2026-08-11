package dev.nahornyi.tictactoe.session.client;

/**
 * The engine could not be reached, timed out, or answered 5xx.
 *
 * <p>Retryable by definition: the request may never have been processed, and the engine's create
 * and move endpoints are safe to repeat. Resilience4j is configured to retry exactly this type.
 */
public class EngineUnavailableException extends RuntimeException {

    public EngineUnavailableException(String message) {
        super(message);
    }

    public EngineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
