package dev.nahornyi.tictactoe.ui.kafka;

import dev.nahornyi.tictactoe.contracts.event.GameEvent;
import dev.nahornyi.tictactoe.contracts.event.GameTopics;
import dev.nahornyi.tictactoe.ui.sse.GameEventEmitters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes game events and pushes them to the browsers watching that game.
 *
 * <p>The consumer group is deliberately unique per instance (see {@code application.yml}). Every
 * replica must see <em>every</em> event, because a browser connected to replica 2 needs moves no
 * matter which replica consumed them - broadcast semantics, not work sharing. A shared group id
 * would partition the events across replicas and silently drop updates for some users.
 *
 * <p>The consequence is that offsets are meaningless across restarts, which is why the consumer
 * starts at the latest offset: a UI replica coming up should show what happens next, not replay
 * every game the platform has ever played.
 */
@Component
public class GameEventListener {

    private static final Logger log = LoggerFactory.getLogger(GameEventListener.class);

    private final GameEventEmitters emitters;

    public GameEventListener(GameEventEmitters emitters) {
        this.emitters = emitters;
    }

    @KafkaListener(topics = GameTopics.GAME_EVENTS, groupId = "${tictactoe.ui.consumer-group}")
    public void onGameEvent(GameEvent event) {
        log.debug("Received {} for game {}", event.eventName(), event.gameId());
        emitters.broadcast(event);
    }
}
