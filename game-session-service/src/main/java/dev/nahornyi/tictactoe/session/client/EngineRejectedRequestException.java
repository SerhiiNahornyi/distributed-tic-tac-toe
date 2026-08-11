package dev.nahornyi.tictactoe.session.client;

import org.springframework.http.HttpStatusCode;

/**
 * The engine understood the request and refused it - an occupied cell, the wrong turn, a finished
 * game, or a lost optimistic-lock race.
 *
 * <p>Explicitly <em>not</em> retryable: replaying an illegal move produces the same rejection and
 * only hides the bug that generated it. Resilience4j is configured to ignore this type.
 *
 * @param code the stable {@code code} property from the engine's RFC 7807 body, or {@code null}
 */
public class EngineRejectedRequestException extends RuntimeException {

    private final transient HttpStatusCode status;
    private final transient String code;

    public EngineRejectedRequestException(HttpStatusCode status, String code, String detail) {
        super("Engine rejected the request with %s (%s): %s".formatted(status, code, detail));
        this.status = status;
        this.code = code;
    }

    public HttpStatusCode status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** A conflict means the engine's state moved on; re-reading it and continuing may succeed. */
    public boolean isConflict() {
        return status != null && status.value() == 409;
    }
}
