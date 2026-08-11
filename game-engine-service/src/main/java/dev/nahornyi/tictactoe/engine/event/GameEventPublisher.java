package dev.nahornyi.tictactoe.engine.event;

import dev.nahornyi.tictactoe.contracts.event.GameEvent;
import dev.nahornyi.tictactoe.contracts.event.GameTopics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Forwards domain events to Kafka once the transaction that produced them has committed.
 *
 * <p>{@code AFTER_COMMIT} matters: announcing a move that a later rollback erases would leave
 * consumers with state the engine does not have. {@code fallbackExecution} keeps the listener
 * working in tests that run outside a transaction.
 *
 * <p>The actual send runs on a single background thread. {@code KafkaTemplate.send} blocks while
 * the producer resolves topic metadata - and, with observation enabled, the cluster id - so calling
 * it inline would let an unreachable broker stall the HTTP thread of a move that has already been
 * committed. One thread keeps per-game ordering; a bounded queue keeps a long outage from
 * exhausting memory.
 *
 * <p>The honest consequence is at-most-once delivery: if this process dies with events queued, they
 * are gone. The outbox pattern that would fix it is discussed in the README.
 */
@Component
public class GameEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameEventPublisher.class);
    private static final int QUEUE_CAPACITY = 1_000;

    private final KafkaTemplate<String, GameEvent> kafkaTemplate;
    private final ThreadPoolExecutor publisher = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "game-event-publisher");
                thread.setDaemon(true);
                return thread;
            });

    public GameEventPublisher(KafkaTemplate<String, GameEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(GameEvent event) {
        try {
            publisher.execute(() -> send(event));
        } catch (RejectedExecutionException overloaded) {
            log.warn("Dropping {} for game {}: publisher queue is full ({} events)",
                    event.eventName(), event.gameId(), QUEUE_CAPACITY);
        }
    }

    private void send(GameEvent event) {
        try {
            // Keyed by game id so every event for one game shares a partition and stays ordered.
            kafkaTemplate.send(GameTopics.GAME_EVENTS, event.gameId().toString(), event)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            log.error("Failed to publish {} for game {}",
                                    event.eventName(), event.gameId(), failure);
                        } else {
                            log.debug("Published {} for game {}", event.eventName(), event.gameId());
                        }
                    });
        } catch (RuntimeException failure) {
            log.error("Could not publish {} for game {}", event.eventName(), event.gameId(), failure);
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
