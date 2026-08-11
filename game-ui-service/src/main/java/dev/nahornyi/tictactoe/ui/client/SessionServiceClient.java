package dev.nahornyi.tictactoe.ui.client;

import dev.nahornyi.tictactoe.contracts.api.SessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Talks to the Game Session Service on the browser's behalf.
 *
 * <p>Two shapes of call, for two different needs. {@link #relayPost} and {@link #relayGet} are a
 * transparent pass-through: whatever the session service answered - including its RFC 7807 error
 * bodies - reaches the browser unaltered, so the UI never has to guess why something failed.
 * {@link #getSession} deserialises properly, because the SSE snapshot needs a real object.
 */
@Component
public class SessionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(SessionServiceClient.class);

    /** Headers that describe a single connection and must not be forwarded (RFC 9110). */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length");

    private final RestClient restClient;

    public SessionServiceClient(RestClient sessionServiceRestClient) {
        this.restClient = sessionServiceRestClient;
    }

    public ResponseEntity<String> relayPost(String path, String body) {
        try {
            return sanitise(restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body == null ? "{}" : body)
                    .retrieve()
                    // Pass errors through instead of throwing: an upstream 409 is information the
                    // browser needs, not an exception for this service to interpret.
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                    })
                    .toEntity(String.class));
        } catch (ResourceAccessException exception) {
            throw new SessionServiceUnavailableException(exception);
        }
    }

    public ResponseEntity<String> relayGet(String path) {
        try {
            return sanitise(restClient.get()
                    .uri(path)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                    })
                    .toEntity(String.class));
        } catch (ResourceAccessException exception) {
            throw new SessionServiceUnavailableException(exception);
        }
    }

    /**
     * Rebuilds the response so only headers that make sense to forward survive.
     *
     * <p>Two things go wrong without this. Hop-by-hop headers describe the upstream connection, not
     * ours: forwarding {@code Transfer-Encoding} makes the container emit it twice and the browser
     * discard the body. And {@code Location} arrives pointing at the session service's own host and
     * port, which is unreachable from the browser and leaks internal topology - so it is rewritten
     * onto this service's {@code /api} prefix.
     */
    private ResponseEntity<String> sanitise(ResponseEntity<String> upstream) {
        HttpHeaders headers = new HttpHeaders();

        upstream.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, values);
            }
        });

        URI location = upstream.getHeaders().getLocation();
        if (location != null) {
            headers.setLocation(URI.create("/api" + location.getPath()));
        }

        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
    }

    /** Used for the SSE snapshot, where the state has to be an object rather than opaque bytes. */
    public SessionResponse getSession(UUID sessionId) {
        try {
            return restClient.get()
                    .uri("/sessions/{sessionId}", sessionId)
                    .retrieve()
                    .body(SessionResponse.class);
        } catch (ResourceAccessException exception) {
            throw new SessionServiceUnavailableException(exception);
        } catch (RuntimeException exception) {
            log.warn("Could not read session {} for the stream snapshot: {}", sessionId, exception.getMessage());
            throw exception;
        }
    }

    public static class SessionServiceUnavailableException extends RuntimeException {
        public SessionServiceUnavailableException(Throwable cause) {
            super("The Game Session Service could not be reached: " + cause.getMessage(), cause);
        }
    }
}
