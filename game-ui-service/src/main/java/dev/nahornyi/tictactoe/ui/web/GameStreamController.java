package dev.nahornyi.tictactoe.ui.web;

import dev.nahornyi.tictactoe.contracts.api.SessionResponse;
import dev.nahornyi.tictactoe.ui.client.SessionServiceClient;
import dev.nahornyi.tictactoe.ui.sse.GameEventEmitters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * The browser's entire API surface.
 *
 * <p>Everything is proxied through this service rather than called directly from the page, which
 * keeps the browser same-origin - no CORS configuration to get wrong - and means the session
 * service does not have to be reachable from outside the cluster.
 */
@RestController
@RequestMapping("/api/sessions")
public class GameStreamController {

    private static final Logger log = LoggerFactory.getLogger(GameStreamController.class);

    private final SessionServiceClient sessionClient;
    private final GameEventEmitters emitters;

    public GameStreamController(SessionServiceClient sessionClient, GameEventEmitters emitters) {
        this.sessionClient = sessionClient;
        this.emitters = emitters;
    }

    @PostMapping
    public ResponseEntity<String> createSession(@RequestBody(required = false) String body) {
        return sessionClient.relayPost("/sessions", body);
    }

    @PostMapping("/{sessionId}/simulate")
    public ResponseEntity<String> simulate(@PathVariable UUID sessionId) {
        return sessionClient.relayPost("/sessions/" + sessionId + "/simulate", null);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<String> getSession(@PathVariable UUID sessionId) {
        return sessionClient.relayGet("/sessions/" + sessionId);
    }

    /**
     * Live feed of one game.
     *
     * <p>The current state is sent first, then deltas. Without that snapshot a browser that
     * connects a moment after the simulation started would render an empty board and only catch up
     * from the next move - the earlier moves are already past and SSE has no history.
     */
    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID sessionId) {
        SseEmitter emitter = emitters.register(sessionId);

        try {
            SessionResponse snapshot = sessionClient.getSession(sessionId);
            emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
        } catch (IOException | RuntimeException failure) {
            // Report the problem on the stream the browser is already holding, rather than failing
            // the request: the UI can then show a real message instead of a dead connection.
            log.warn("Could not send the snapshot for session {}: {}", sessionId, failure.getMessage());
            sendStreamError(emitter, failure.getMessage());
        }

        return emitter;
    }

    private void sendStreamError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("stream-error")
                    .data("{\"reason\":\"Could not load the session: %s\"}"
                            .formatted(message == null ? "unknown error" : message.replace("\"", "'"))));
        } catch (IOException ignored) {
            emitter.complete();
        }
    }
}
