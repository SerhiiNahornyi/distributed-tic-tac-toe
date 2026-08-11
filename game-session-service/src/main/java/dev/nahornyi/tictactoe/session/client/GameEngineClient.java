package dev.nahornyi.tictactoe.session.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.api.CreateGameRequest;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.MoveRequest;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The REST link between this service and the Game Engine Service, as the assignment requires.
 *
 * <p>Everything the rest of the service needs to know about HTTP stops here: responses come back as
 * domain types and failures as two exceptions that answer the only question the caller has - "can I
 * retry this?". {@link EngineUnavailableException} means yes, {@link EngineRejectedRequestException}
 * means no.
 */
@Component
public class GameEngineClient {

    private static final Logger log = LoggerFactory.getLogger(GameEngineClient.class);
    private static final String RETRY_INSTANCE = "gameEngine";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GameEngineClient(RestClient gameEngineRestClient, ObjectMapper objectMapper) {
        this.restClient = gameEngineRestClient;
        this.objectMapper = objectMapper;
    }

    /** Idempotent on the engine side, which is what makes retrying it safe. */
    @Retry(name = RETRY_INSTANCE)
    public GameStateResponse createGame(UUID gameId) {
        log.debug("Creating game {} on the engine", gameId);
        return exchange(() -> restClient.post()
                .uri("/games")
                .body(new CreateGameRequest(gameId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .body(GameStateResponse.class));
    }

    @Retry(name = RETRY_INSTANCE)
    public GameStateResponse applyMove(UUID gameId, PlayerSymbol symbol, int position) {
        log.debug("Sending move {}@{} for game {} to the engine", symbol, position, gameId);
        return exchange(() -> restClient.post()
                .uri("/games/{gameId}/move", gameId)
                .body(new MoveRequest(symbol, position))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .body(GameStateResponse.class));
    }

    @Retry(name = RETRY_INSTANCE)
    public GameStateResponse getGame(UUID gameId) {
        return exchange(() -> restClient.get()
                .uri("/games/{gameId}", gameId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .body(GameStateResponse.class));
    }

    /**
     * Turns transport-level problems - connection refused, timeouts, DNS - into the retryable
     * exception. Left alone they surface as {@link ResourceAccessException} and would slip past the
     * retry configuration.
     */
    private GameStateResponse exchange(Supplier<GameStateResponse> call) {
        try {
            GameStateResponse response = call.get();
            if (response == null) {
                throw new EngineUnavailableException("Engine returned an empty body");
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw new EngineUnavailableException(
                    "Could not reach the game engine: " + exception.getMessage(), exception);
        }
    }

    /**
     * Maps an error response onto the retryable/not-retryable split: 5xx is the engine having a bad
     * day and worth repeating, 4xx is the engine telling us the request itself was wrong.
     */
    private void translateError(HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);

        if (status.is5xxServerError()) {
            log.warn("Engine returned {} for {} {}", status, request.getMethod(), request.getURI());
            throw new EngineUnavailableException("Engine responded %s: %s".formatted(status, body));
        }

        throw new EngineRejectedRequestException(status, problemCode(body), body);
    }

    /** Reads the {@code code} property out of the engine's RFC 7807 body, if it is one. */
    private String problemCode(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode code = node.get("code");
            return code == null ? null : code.asText();
        } catch (IOException exception) {
            return null;
        }
    }
}
