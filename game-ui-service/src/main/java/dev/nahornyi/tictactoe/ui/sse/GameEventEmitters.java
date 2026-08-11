package dev.nahornyi.tictactoe.ui.sse;

import dev.nahornyi.tictactoe.contracts.event.GameEvent;
import dev.nahornyi.tictactoe.ui.config.UiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the browser connections watching each game and fans events out to them.
 *
 * <p>This is the bridge between Kafka and the browser: a Kafka listener hands events here, and this
 * class writes them to whichever {@link SseEmitter} instances are watching that game. Browsers
 * cannot speak the Kafka protocol, so something has to make this hop.
 *
 * <p>The registry is per-instance and in-memory, which is correct rather than a shortcut: an
 * emitter is a live TCP connection to <em>this</em> process and cannot be shared with another
 * replica. What makes horizontal scaling work is on the consumer side - every replica reads every
 * event via its own consumer group (see {@code GameEventListener}).
 */
@Component
public class GameEventEmitters implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GameEventEmitters.class);

    private final Map<UUID, Set<SseEmitter>> emittersByGame = new ConcurrentHashMap<>();
    private final UiProperties properties;

    private volatile boolean running;

    public GameEventEmitters(UiProperties properties) {
        this.properties = properties;
    }

    /**
     * Opens a stream for one browser. All three completion callbacks deregister the emitter -
     * without them, a page refresh would leak a connection on every reload.
     */
    public SseEmitter register(UUID gameId) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());

        emittersByGame.computeIfAbsent(gameId, key -> ConcurrentHashMap.newKeySet()).add(emitter);

        emitter.onCompletion(() -> deregister(gameId, emitter));
        emitter.onError(throwable -> deregister(gameId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            deregister(gameId, emitter);
        });

        log.debug("Opened stream for game {} ({} watching)", gameId, subscriberCount(gameId));
        return emitter;
    }

    /** Delivers an event to every browser watching its game. */
    public void broadcast(GameEvent event) {
        send(event.gameId(), event.eventName(), event);
    }

    public void send(UUID gameId, String eventName, Object payload) {
        Set<SseEmitter> emitters = emittersByGame.get(gameId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException failure) {
                // The browser went away mid-write. Expected, not exceptional: drop the connection
                // and carry on delivering to everyone else.
                log.debug("Dropping a closed stream for game {}: {}", gameId, failure.getMessage());
                deregister(gameId, emitter);
            }
        }
    }

    /**
     * Keeps idle connections alive. A game between two bots can be quiet for seconds at a time, and
     * intermediaries happily close a connection that has sent nothing.
     */
    @Scheduled(fixedDelayString = "${tictactoe.ui.heartbeat-interval:20s}")
    public void sendHeartbeats() {
        emittersByGame.forEach((gameId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException failure) {
                    deregister(gameId, emitter);
                }
            }
        });
    }

    /**
     * Closes every open stream when the application shuts down, so browsers see a clean
     * end-of-stream and reconnect elsewhere.
     *
     * <p>This runs as a {@link SmartLifecycle} at the highest phase rather than from
     * {@code @PreDestroy}, and that detail matters: bean destruction happens <em>after</em> the web
     * server has been told to stop, and the servlet container will not finish stopping while async
     * requests are still open. Closing them here - before the server stops - is the difference
     * between an immediate shutdown and one that blocks until every SSE connection times out.
     */
    @Override
    public void stop() {
        log.info("Closing {} game stream(s) on shutdown", emittersByGame.size());
        emittersByGame.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
        emittersByGame.clear();
        running = false;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Highest phase, so this is the first component stopped and the last one started. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public int subscriberCount(UUID gameId) {
        Set<SseEmitter> emitters = emittersByGame.get(gameId);
        return emitters == null ? 0 : emitters.size();
    }

    private void deregister(UUID gameId, SseEmitter emitter) {
        // Compute-and-remove in one step so the map does not accumulate empty sets for every game
        // that has ever been watched.
        emittersByGame.computeIfPresent(gameId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
