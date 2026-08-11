package dev.nahornyi.tictactoe.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.SessionStatus;
import dev.nahornyi.tictactoe.contracts.api.MoveRecord;
import dev.nahornyi.tictactoe.contracts.api.SessionResponse;
import dev.nahornyi.tictactoe.contracts.event.GameEvent;
import dev.nahornyi.tictactoe.contracts.event.GameFinished;
import dev.nahornyi.tictactoe.contracts.event.GameTopics;
import dev.nahornyi.tictactoe.contracts.event.MoveApplied;
import dev.nahornyi.tictactoe.ui.sse.GameEventEmitters;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Covers the hop that only exists in this service: a Kafka event arriving on the broker and coming
 * out of an HTTP connection held open by a browser.
 *
 * <p>The test connects a real SSE client rather than inspecting the emitter registry, because the
 * thing worth proving is that bytes reach the client - a registry that holds the right objects but
 * never flushes them would pass a unit test and fail in a browser.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = GameTopics.GAME_EVENTS)
class GameUiIntegrationTest {

    private static final WireMockServer SESSION_SERVICE = new WireMockServer(options().dynamicPort());

    static {
        SESSION_SERVICE.start();
    }

    @AfterAll
    static void stopSessionService() {
        SESSION_SERVICE.stop();
    }

    @DynamicPropertySource
    static void sessionServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("tictactoe.ui.session-base-url", () -> "http://localhost:" + SESSION_SERVICE.port());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private GameEventEmitters emitters;

    private KafkaTemplate<String, GameEvent> producer;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        SESSION_SERVICE.resetAll();
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        producer = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new JsonSerializer<>()));

        // Nothing published before the listener owns its partitions would ever be delivered, since
        // this consumer starts at the latest offset.
        listenerRegistry.getListenerContainers()
                .forEach(container -> ContainerTestUtils.waitForAssignment(container, 3));
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.destroy();
        }
        if (httpClient != null) {
            // Each HttpClient owns a selector thread that outlives the test and holds up JVM exit.
            httpClient.shutdownNow();
        }
    }

    @Test
    @DisplayName("the stream opens with a snapshot of the current session")
    void sendsASnapshotWhenTheStreamOpens() throws Exception {
        UUID sessionId = UUID.randomUUID();
        stubSession(sessionId, sessionWith(SessionStatus.RUNNING, "X---O----", List.of()));

        try (SseClient stream = openStream(sessionId)) {
            String snapshot = stream.awaitEvent("snapshot");

            assertThat(snapshot).contains("\"board\":\"X---O----\"");
            assertThat(snapshot).contains("\"sessionStatus\":\"RUNNING\"");
        }
    }

    @Test
    @DisplayName("a move published to Kafka reaches the browser")
    void pushesMoveEventsToTheBrowser() throws Exception {
        UUID sessionId = UUID.randomUUID();
        stubSession(sessionId, sessionWith(SessionStatus.RUNNING, "---------", List.of()));

        try (SseClient stream = openStream(sessionId)) {
            stream.awaitEvent("snapshot");
            await().atMost(Duration.ofSeconds(5)).until(() -> emitters.subscriberCount(sessionId) == 1);

            publish(new MoveApplied(sessionId,
                    new MoveRecord(1, PlayerSymbol.X, 4, "----X----", GameStatus.IN_PROGRESS, Instant.now()),
                    Instant.now()));
            publish(new GameFinished(sessionId, GameStatus.X_WON, PlayerSymbol.X, "XX-XX--O-", 5, Instant.now()));

            assertThat(stream.awaitEvent("move-applied")).contains("\"position\":4");
            assertThat(stream.awaitEvent("game-finished")).contains("\"status\":\"X_WON\"");
        }
    }

    @Test
    @DisplayName("a browser only receives events for the game it is watching")
    void doesNotLeakEventsAcrossGames() throws Exception {
        UUID watched = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        stubSession(watched, sessionWith(SessionStatus.RUNNING, "---------", List.of()));

        try (SseClient stream = openStream(watched)) {
            stream.awaitEvent("snapshot");
            await().atMost(Duration.ofSeconds(5)).until(() -> emitters.subscriberCount(watched) == 1);

            publish(new MoveApplied(other,
                    new MoveRecord(1, PlayerSymbol.X, 0, "X--------", GameStatus.IN_PROGRESS, Instant.now()),
                    Instant.now()));
            publish(new MoveApplied(watched,
                    new MoveRecord(1, PlayerSymbol.X, 8, "--------X", GameStatus.IN_PROGRESS, Instant.now()),
                    Instant.now()));

            // The first event the stream sees must be the watched game's, proving the other one was
            // filtered out rather than merely arriving later.
            assertThat(stream.awaitEvent("move-applied")).contains("\"position\":8");
        }
    }

    @Test
    void closingTheBrowserConnectionReleasesTheEmitter() throws Exception {
        UUID sessionId = UUID.randomUUID();
        stubSession(sessionId, sessionWith(SessionStatus.RUNNING, "---------", List.of()));

        try (SseClient stream = openStream(sessionId)) {
            stream.awaitEvent("snapshot");
            await().atMost(Duration.ofSeconds(5)).until(() -> emitters.subscriberCount(sessionId) == 1);
        }

        // The next write to a dead connection is what detects the close, so nudge it.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            emitters.send(sessionId, "ping", "{}");
            assertThat(emitters.subscriberCount(sessionId)).isZero();
        });
    }

    @Test
    @DisplayName("upstream errors are relayed untouched so the UI can explain them")
    void relaysUpstreamErrorsUnchanged() {
        UUID sessionId = UUID.randomUUID();
        SESSION_SERVICE.stubFor(get(urlPathMatching("/sessions/.*")).willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"code\":\"session-not-found\"}")));

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/sessions/{sessionId}", String.class, sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("session-not-found");
    }

    @Test
    void reportsAnUnreachableSessionServiceAs503() {
        SESSION_SERVICE.stubFor(get(urlPathMatching("/sessions/.*"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/sessions/{sessionId}", String.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("session-service-unavailable");
    }

    // --- helpers -------------------------------------------------------------------------------

    private void publish(GameEvent event) {
        producer.send(GameTopics.GAME_EVENTS, event.gameId().toString(), event);
        producer.flush();
    }

    private void stubSession(UUID sessionId, SessionResponse response) {
        SESSION_SERVICE.stubFor(get(urlPathMatching("/sessions/" + sessionId))
                .willReturn(okJson(json(response))));
    }

    private SessionResponse sessionWith(SessionStatus status, String board, List<MoveRecord> moves) {
        return new SessionResponse(
                UUID.randomUUID(), status, GameStatus.IN_PROGRESS, board, null, "random",
                moves, null, Instant.now(), Instant.now());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialise " + value, exception);
        }
    }

    private SseClient openStream(UUID sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/sessions/" + sessionId + "/stream"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);

        return new SseClient(response);
    }

    /** Minimal SSE reader: collects {@code event:}/{@code data:} pairs off a background thread. */
    private static final class SseClient implements AutoCloseable {

        private final HttpResponse<java.io.InputStream> response;
        private final BlockingQueue<String[]> events = new LinkedBlockingQueue<>();
        private final Thread reader;

        private SseClient(HttpResponse<java.io.InputStream> response) {
            this.response = response;
            this.reader = new Thread(this::read, "sse-test-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void read() {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String eventName = null;
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:") && eventName != null) {
                        events.add(new String[]{eventName, line.substring("data:".length()).trim()});
                        eventName = null;
                    }
                }
            } catch (Exception ignored) {
                // Stream closed by the test or by the server; nothing left to read.
            }
        }

        /** Waits for the next event with the given name, ignoring heartbeats and other events. */
        String awaitEvent(String name) throws InterruptedException {
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            while (System.currentTimeMillis() < deadline) {
                String[] event = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (event != null && event[0].equals(name)) {
                    return event[1];
                }
            }
            throw new AssertionError("Timed out waiting for SSE event '" + name + "'");
        }

        @Override
        public void close() throws Exception {
            response.body().close();
            reader.interrupt();
        }
    }
}
