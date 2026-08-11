package dev.nahornyi.tictactoe.session.event;

import dev.nahornyi.tictactoe.contracts.event.GameEvent;
import dev.nahornyi.tictactoe.contracts.event.GameTopics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Publishes session lifecycle events. Move events come from the engine, which is the only writer of
 * game state - duplicating them here would give consumers two sources of truth for one fact.
 *
 * <p>Sends are handed to a single background thread rather than executed inline, because
 * {@code KafkaTemplate.send} is not as asynchronous as it looks: it blocks while the producer
 * fetches topic metadata, and with observation enabled it also resolves the cluster id through an
 * admin call. Against a healthy broker that is microseconds; against an unreachable one it is tens
 * of seconds on whichever thread called it - which would turn a Kafka outage into a session service
 * outage. Publishing is best-effort, so it belongs off the request path.
 *
 * <p>Exactly one thread, so events for a game keep their order. A bounded queue, so a prolonged
 * outage drops events with a warning instead of exhausting memory.
 */
@Component
public class SessionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SessionEventPublisher.class);
    private static final int QUEUE_CAPACITY = 1_000;

    private final KafkaTemplate<String, GameEvent> kafkaTemplate;
    private final ThreadPoolExecutor publisher = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "session-event-publisher");
                thread.setDaemon(true);
                return thread;
            });

    public SessionEventPublisher(KafkaTemplate<String, GameEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(GameEvent event) {
        try {
            publisher.execute(() -> send(event));
        } catch (RejectedExecutionException overloaded) {
            log.warn("Dropping {} for session {}: publisher queue is full ({} events)",
                    event.eventName(), event.gameId(), QUEUE_CAPACITY);
        }
    }

    private void send(GameEvent event) {
        try {
            kafkaTemplate.send(GameTopics.GAME_EVENTS, event.gameId().toString(), event)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            log.error("Failed to publish {} for session {}",
                                    event.eventName(), event.gameId(), failure);
                        } else {
                            log.debug("Published {} for session {}", event.eventName(), event.gameId());
                        }
                    });
        } catch (RuntimeException failure) {
            log.error("Could not publish {} for session {}", event.eventName(), event.gameId(), failure);
        }
    }

    @PreDestroy
    public void drain() {
        publisher.shutdown();
        try {
            if (!publisher.awaitTermination(5, TimeUnit.SECONDS)) {
                publisher.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            publisher.shutdownNow();
        }
    }
}
