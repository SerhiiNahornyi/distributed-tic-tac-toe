package dev.nahornyi.tictactoe.engine;

import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.api.CreateGameRequest;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.MoveRequest;
import dev.nahornyi.tictactoe.contracts.event.GameEvent;
import dev.nahornyi.tictactoe.contracts.event.GameFinished;
import dev.nahornyi.tictactoe.contracts.event.GameTopics;
import dev.nahornyi.tictactoe.contracts.event.MoveApplied;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the service the way the Game Session Service does: over HTTP, against a real
 * (embedded) Kafka broker, with the full Spring context. No Docker required.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = GameTopics.GAME_EVENTS)
class GameEngineIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private org.springframework.kafka.test.EmbeddedKafkaBroker broker;

    private Consumer<String, GameEvent> consumer;

    @BeforeEach
    void subscribeToGameEvents() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "engine-integration-test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        JsonDeserializer<GameEvent> valueDeserializer = new JsonDeserializer<>(GameEvent.class);
        valueDeserializer.addTrustedPackages("*");

        consumer = new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer)
                .createConsumer();
        consumer.subscribe(List.of(GameTopics.GAME_EVENTS));
        consumer.poll(Duration.ofMillis(500)); // force partition assignment before the game starts
    }

    @AfterEach
    void closeConsumer() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("a full game over HTTP produces the right board, status and events")
    void playsAFullGameAndPublishesAnEventPerMove() {
        UUID gameId = createGame();

        // X takes the top row while O answers in the middle row.
        playExpectingOk(gameId, PlayerSymbol.X, 0);
        playExpectingOk(gameId, PlayerSymbol.O, 3);
        playExpectingOk(gameId, PlayerSymbol.X, 1);
        playExpectingOk(gameId, PlayerSymbol.O, 4);
        GameStateResponse finalState = playExpectingOk(gameId, PlayerSymbol.X, 2);

        assertThat(finalState.status()).isEqualTo(GameStatus.X_WON);
        assertThat(finalState.winner()).isEqualTo(PlayerSymbol.X);
        assertThat(finalState.board()).isEqualTo("XXXOO----");
        assertThat(finalState.nextTurn()).isNull();

        List<GameEvent> events = drainEvents(gameId, 6);

        assertThat(events).hasSize(6);
        assertThat(events.subList(0, 5)).allSatisfy(event -> assertThat(event).isInstanceOf(MoveApplied.class));
        assertThat(events.get(5)).isInstanceOfSatisfying(GameFinished.class, finished -> {
            assertThat(finished.status()).isEqualTo(GameStatus.X_WON);
            assertThat(finished.winner()).isEqualTo(PlayerSymbol.X);
            assertThat(finished.totalMoves()).isEqualTo(5);
        });

        // Events for one game must stay in move order; that is what keying by game id buys us.
        assertThat(events.subList(0, 5))
                .map(event -> ((MoveApplied) event).move().moveNumber())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void readingBackAGameReturnsThePersistedState() {
        UUID gameId = createGame();
        playExpectingOk(gameId, PlayerSymbol.X, 8);

        ResponseEntity<GameStateResponse> response =
                restTemplate.getForEntity("/games/{gameId}", GameStateResponse.class, gameId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().board()).isEqualTo("--------X");
        assertThat(response.getBody().nextTurn()).isEqualTo(PlayerSymbol.O);
    }

    @Test
    void creatingTheSameGameTwiceIsIdempotentSoRetriesAreSafe() {
        UUID gameId = UUID.randomUUID();

        ResponseEntity<GameStateResponse> first = restTemplate.postForEntity(
                "/games", new CreateGameRequest(gameId), GameStateResponse.class);
        ResponseEntity<GameStateResponse> retry = restTemplate.postForEntity(
                "/games", new CreateGameRequest(gameId), GameStateResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isNotNull();
        assertThat(retry.getBody().gameId()).isEqualTo(gameId);
    }

    @Test
    void anIllegalMoveIsReportedAsProblemJsonAndChangesNothing() {
        UUID gameId = createGame();
        playExpectingOk(gameId, PlayerSymbol.X, 4);

        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/games/{gameId}/move", new MoveRequest(PlayerSymbol.O, 4), ProblemDetail.class, gameId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "cell-occupied");

        ResponseEntity<GameStateResponse> state =
                restTemplate.getForEntity("/games/{gameId}", GameStateResponse.class, gameId);
        assertThat(state.getBody()).isNotNull();
        assertThat(state.getBody().board()).isEqualTo("----X----");
    }

    @Test
    void movingOnAnUnknownGameReturns404() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/games/{gameId}/move", new MoveRequest(PlayerSymbol.X, 0), ProblemDetail.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The assignment's "concurrency handling" enhancement. Ten clients race for the same cell on
     * the same game; optimistic locking plus rule validation must let exactly one through, and the
     * board must end up with exactly one mark on it.
     */
    @Test
    void concurrentMovesOnTheSameCellLeaveExactlyOneWinner() throws Exception {
        UUID gameId = createGame();
        int contenders = 10;

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Callable<HttpStatusCode>> attempts = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                attempts.add(() -> restTemplate.postForEntity(
                        "/games/{gameId}/move", new MoveRequest(PlayerSymbol.X, 4), String.class, gameId)
                        .getStatusCode());
            }

            List<Future<HttpStatusCode>> results = pool.invokeAll(attempts, 30, TimeUnit.SECONDS);

            long accepted = 0;
            for (Future<HttpStatusCode> result : results) {
                HttpStatusCode status = result.get();
                if (status.equals(HttpStatus.OK)) {
                    accepted++;
                } else {
                    // Losers are rejected as client errors (occupied cell, wrong turn, or a lost
                    // optimistic lock race) - never as a server error.
                    assertThat(status.is4xxClientError())
                            .as("losing request should be a 4xx, was %s", status)
                            .isTrue();
                }
            }
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        ResponseEntity<GameStateResponse> state =
                restTemplate.getForEntity("/games/{gameId}", GameStateResponse.class, gameId);
        assertThat(state.getBody()).isNotNull();
        assertThat(state.getBody().board()).isEqualTo("----X----");
        assertThat(state.getBody().moveCount()).isEqualTo(1);
    }

    private UUID createGame() {
        ResponseEntity<GameStateResponse> response = restTemplate.postForEntity(
                "/games", new CreateGameRequest(UUID.randomUUID()), GameStateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().gameId();
    }

    private GameStateResponse playExpectingOk(UUID gameId, PlayerSymbol symbol, int position) {
        ResponseEntity<GameStateResponse> response = restTemplate.postForEntity(
                "/games/{gameId}/move", new MoveRequest(symbol, position), GameStateResponse.class, gameId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<GameEvent> drainEvents(UUID gameId, int expected) {
        List<GameEvent> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(300)).forEach(record -> {
                if (gameId.equals(record.value().gameId())) {
                    received.add(record.value());
                }
            });
            assertThat(received).hasSize(expected);
        });
        return received;
    }
}
