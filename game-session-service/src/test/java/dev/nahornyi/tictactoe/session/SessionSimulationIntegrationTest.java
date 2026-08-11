package dev.nahornyi.tictactoe.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.SessionStatus;
import dev.nahornyi.tictactoe.contracts.api.CreateSessionRequest;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.SessionResponse;
import dev.nahornyi.tictactoe.contracts.event.GameEvent;
import dev.nahornyi.tictactoe.contracts.event.GameTopics;
import dev.nahornyi.tictactoe.contracts.event.SessionCreated;
import dev.nahornyi.tictactoe.contracts.event.SimulationFailed;
import dev.nahornyi.tictactoe.contracts.event.SimulationStarted;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the service against a stubbed Game Engine Service.
 *
 * <p>WireMock rather than a mocked client on purpose: the behaviour under test - retry on 5xx, no
 * retry on 4xx, timeouts, RFC 7807 decoding - lives in the HTTP layer, and a Mockito stub of
 * {@code GameEngineClient} would assert that the test author understands it rather than that the
 * code does.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // No pacing delay: the animation pause is a presentation concern, not behaviour.
                "tictactoe.session.move-delay=0ms",
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
        })
@EmbeddedKafka(partitions = 3, topics = GameTopics.GAME_EVENTS)
class SessionSimulationIntegrationTest {

    private static final WireMockServer ENGINE = new WireMockServer(options().dynamicPort());

    static {
        // Started in a static initialiser so the port is known before Spring resolves the
        // dynamic property below.
        ENGINE.start();
    }

    @AfterAll
    static void stopEngine() {
        ENGINE.stop();
    }

    @DynamicPropertySource
    static void engineUrl(DynamicPropertyRegistry registry) {
        registry.add("tictactoe.session.engine-base-url", () -> "http://localhost:" + ENGINE.port());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private Consumer<String, GameEvent> consumer;

    @BeforeEach
    void resetStubsAndSubscribe() {
        ENGINE.resetAll();

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "session-integration-test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        JsonDeserializer<GameEvent> valueDeserializer = new JsonDeserializer<>(GameEvent.class);
        valueDeserializer.addTrustedPackages("*");

        consumer = new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer)
                .createConsumer();
        consumer.subscribe(List.of(GameTopics.GAME_EVENTS));
        consumer.poll(Duration.ofMillis(500));
    }

    @AfterEach
    void closeConsumer() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("plays a whole game, alternating players, and records every move")
    void simulatesAFullGame() {
        stubGameCreation();
        stubGameLookup(state("---------", GameStatus.IN_PROGRESS, PlayerSymbol.X, 0));
        stubScriptedGame(
                state("X--------", GameStatus.IN_PROGRESS, PlayerSymbol.O, 1),
                state("XO-------", GameStatus.IN_PROGRESS, PlayerSymbol.X, 2),
                state("XO--X----", GameStatus.IN_PROGRESS, PlayerSymbol.O, 3),
                state("XOO-X----", GameStatus.IN_PROGRESS, PlayerSymbol.X, 4),
                won("XOO-X---X", PlayerSymbol.X, 5));

        UUID sessionId = createSession(null);
        startSimulation(sessionId);

        SessionResponse finished = awaitTerminal(sessionId);

        assertThat(finished.sessionStatus()).isEqualTo(SessionStatus.FINISHED);
        assertThat(finished.gameStatus()).isEqualTo(GameStatus.X_WON);
        assertThat(finished.winner()).isEqualTo(PlayerSymbol.X);
        assertThat(finished.board()).isEqualTo("XOO-X---X");
        assertThat(finished.failureReason()).isNull();
        assertThat(finished.moves()).hasSize(5);

        // The two automated players must alternate, starting with X. This is the contract the
        // engine enforces and the runner has to honour.
        assertThat(finished.moves()).map(move -> move.symbol().name())
                .containsExactly("X", "O", "X", "O", "X");
        assertThat(finished.moves()).map(move -> move.moveNumber())
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(sentMoveSymbols()).containsExactly("X", "O", "X", "O", "X");
    }

    @Test
    @DisplayName("session lifecycle events reach Kafka")
    void publishesSessionEvents() {
        stubGameCreation();
        stubGameLookup(won("XOO-X---X", PlayerSymbol.X, 5));

        UUID sessionId = createSession(null);
        startSimulation(sessionId);
        awaitTerminal(sessionId);

        List<GameEvent> events = drainEvents(sessionId, 2);

        assertThat(events.get(0)).isInstanceOfSatisfying(SessionCreated.class,
                created -> assertThat(created.strategy()).isEqualTo("random"));
        assertThat(events.get(1)).isInstanceOf(SimulationStarted.class);
    }

    @Test
    @DisplayName("a transient engine failure is retried, not surfaced to the caller")
    void retriesTransientFailuresWhenCreatingASession() {
        ENGINE.stubFor(post(urlEqualTo("/games")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(503)));
        ENGINE.stubFor(post(urlEqualTo("/games")).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(json(state("---------", GameStatus.IN_PROGRESS, PlayerSymbol.X, 0)))));

        ResponseEntity<SessionResponse> response = restTemplate.postForEntity(
                "/sessions", new CreateSessionRequest(null), SessionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ENGINE.verify(2, postRequestedFor(urlEqualTo("/games")));
    }

    @Test
    @DisplayName("an engine that stays down is reported as 503, not as a hung request")
    void reportsAnUnreachableEngineAsServiceUnavailable() {
        ENGINE.stubFor(post(urlEqualTo("/games")).willReturn(aResponse().withStatus(500)));

        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/sessions", new CreateSessionRequest(null), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "engine-unavailable");
        // Three configured attempts, then give up rather than hammering a service that is down.
        ENGINE.verify(3, postRequestedFor(urlEqualTo("/games")));
    }

    @Test
    @DisplayName("a rejected move is never retried and fails the session with a reason")
    void doesNotRetryARejectedMove() {
        stubGameCreation();
        stubGameLookup(state("---------", GameStatus.IN_PROGRESS, PlayerSymbol.X, 0));
        ENGINE.stubFor(post(urlPathMatching("/games/[^/]+/move"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"code\":\"position-out-of-range\",\"detail\":\"nope\"}")));

        UUID sessionId = createSession(null);
        startSimulation(sessionId);

        SessionResponse failed = awaitTerminal(sessionId);

        assertThat(failed.sessionStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(failed.failureReason()).contains("position-out-of-range");
        ENGINE.verify(1, postRequestedFor(urlPathMatching("/games/[^/]+/move")));
    }

    @Test
    @DisplayName("an engine outage mid-game fails the session and announces it")
    void failsTheSessionAndPublishesAnEventWhenTheEngineDies() {
        stubGameCreation();
        stubGameLookup(state("---------", GameStatus.IN_PROGRESS, PlayerSymbol.X, 0));
        ENGINE.stubFor(post(urlPathMatching("/games/[^/]+/move"))
                .willReturn(aResponse().withStatus(500)));

        UUID sessionId = createSession(null);
        startSimulation(sessionId);

        SessionResponse failed = awaitTerminal(sessionId);

        assertThat(failed.sessionStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(failed.failureReason()).isNotBlank();

        List<GameEvent> events = drainEvents(sessionId, 3);
        assertThat(events.get(2)).isInstanceOf(SimulationFailed.class);
    }

    @Test
    void aSecondSimulationRequestIsRejected() {
        stubGameCreation();
        stubGameLookup(won("XOO-X---X", PlayerSymbol.X, 5));

        UUID sessionId = createSession(null);
        startSimulation(sessionId);

        ResponseEntity<ProblemDetail> second = restTemplate.postForEntity(
                "/sessions/{sessionId}/simulate", null, ProblemDetail.class, sessionId);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().getProperties()).containsEntry("code", "simulation-already-started");
    }

    @Test
    void anUnknownSessionReturns404() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/sessions/{sessionId}", ProblemDetail.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnknownStrategyIsRejectedBeforeTheEngineIsCalled() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/sessions", new CreateSessionRequest("telepathy"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "unknown-strategy");
        ENGINE.verify(0, postRequestedFor(urlEqualTo("/games")));
    }

    @Test
    void theBlockingStrategyCanBeSelectedPerSession() {
        stubGameCreation();
        stubGameLookup(won("XXX------", PlayerSymbol.X, 5));

        UUID sessionId = createSession("blocking");

        ResponseEntity<SessionResponse> response = restTemplate.getForEntity(
                "/sessions/{sessionId}", SessionResponse.class, sessionId);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().strategy()).isEqualTo("blocking");
    }

    // --- helpers -------------------------------------------------------------------------------

    private void stubGameCreation() {
        ENGINE.stubFor(post(urlEqualTo("/games")).willReturn(
                okJson(json(state("---------", GameStatus.IN_PROGRESS, PlayerSymbol.X, 0)))));
    }

    private void stubGameLookup(GameStateResponse response) {
        ENGINE.stubFor(get(urlPathMatching("/games/[^/]+")).willReturn(okJson(json(response))));
    }

    /** Scripts successive responses to {@code POST /move}, one per scenario state. */
    private void stubScriptedGame(GameStateResponse... states) {
        String scenario = "scripted-game";
        for (int i = 0; i < states.length; i++) {
            String current = i == 0 ? Scenario.STARTED : "move-" + i;
            ENGINE.stubFor(post(urlPathMatching("/games/[^/]+/move")).inScenario(scenario)
                    .whenScenarioStateIs(current)
                    .willSetStateTo("move-" + (i + 1))
                    .willReturn(okJson(json(states[i]))));
        }
    }

    private UUID createSession(String strategy) {
        ResponseEntity<SessionResponse> response = restTemplate.postForEntity(
                "/sessions", new CreateSessionRequest(strategy), SessionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().sessionId();
    }

    private void startSimulation(UUID sessionId) {
        ResponseEntity<SessionResponse> response = restTemplate.postForEntity(
                "/sessions/{sessionId}/simulate", null, SessionResponse.class, sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private SessionResponse awaitTerminal(UUID sessionId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> readSession(sessionId).sessionStatus().isTerminal());
        return readSession(sessionId);
    }

    private SessionResponse readSession(UUID sessionId) {
        return restTemplate.getForObject("/sessions/{sessionId}", SessionResponse.class, sessionId);
    }

    private List<String> sentMoveSymbols() {
        List<LoggedRequest> requests = ENGINE.findAll(postRequestedFor(urlPathMatching("/games/[^/]+/move")));
        List<String> symbols = new ArrayList<>();
        for (LoggedRequest request : requests) {
            symbols.add(readSymbol(request.getBodyAsString()));
        }
        return symbols;
    }

    private String readSymbol(String body) {
        try {
            return objectMapper.readTree(body).get("symbol").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("Unreadable move body: " + body, exception);
        }
    }

    private List<GameEvent> drainEvents(UUID sessionId, int expected) {
        List<GameEvent> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(300)).forEach(record -> {
                if (sessionId.equals(record.value().gameId())) {
                    received.add(record.value());
                }
            });
            assertThat(received).hasSize(expected);
        });
        return received;
    }

    private GameStateResponse state(String board, GameStatus status, PlayerSymbol nextTurn, int moveCount) {
        return new GameStateResponse(UUID.randomUUID(), board, status, nextTurn, null, moveCount);
    }

    private GameStateResponse won(String board, PlayerSymbol winner, int moveCount) {
        return new GameStateResponse(
                UUID.randomUUID(), board, GameStatus.wonBy(winner), null, winner, moveCount);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialise " + value, exception);
        }
    }
}
