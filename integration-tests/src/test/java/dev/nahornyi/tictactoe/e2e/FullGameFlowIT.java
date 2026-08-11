package dev.nahornyi.tictactoe.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole system, end to end: a real Kafka broker, the three services running as separate
 * processes from their packaged jars, and requests entering where a browser would - at the UI
 * service.
 *
 * <p>Nothing is stubbed. A passing run means a session was created, the session service generated
 * moves and sent them to the engine over REST, the engine validated and applied them, the resulting
 * events travelled through Kafka, and the UI service delivered them to a subscriber over SSE.
 *
 * <p>Requires Docker; run with {@code mvn verify -Pe2e}.
 */
class FullGameFlowIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int ENGINE_PORT = 18081;
    private static final int SESSION_PORT = 18082;
    private static final int UI_PORT = 18080;

    private static ConfluentKafkaContainer kafka;
    private static ServiceProcess engine;
    private static ServiceProcess session;
    private static ServiceProcess ui;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    static void startTheStack() throws Exception {
        // Confluent's image rather than the apache/kafka one used in docker-compose: Testcontainers
        // has to advertise a listener on a port it only learns after the container starts, and this
        // image's handling of that is the better-trodden path. The broker is the broker either way.
        kafka = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));
        kafka.start();

        String bootstrap = "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers();

        engine = ServiceProcess.start("game-engine-service", ENGINE_PORT, bootstrap);
        session = ServiceProcess.start("game-session-service", SESSION_PORT, bootstrap,
                "--tictactoe.session.engine-base-url=http://localhost:" + ENGINE_PORT,
                // Fast enough to keep the test short, slow enough that moves arrive as a sequence
                // of events rather than one indistinguishable burst.
                "--tictactoe.session.move-delay=150ms");
        ui = ServiceProcess.start("game-ui-service", UI_PORT, bootstrap,
                "--tictactoe.ui.session-base-url=http://localhost:" + SESSION_PORT);
    }

    @AfterAll
    static void stopTheStack() {
        closeQuietly(ui);
        closeQuietly(session);
        closeQuietly(engine);
        if (kafka != null) {
            kafka.stop();
        }
        HTTP.shutdownNow();
    }

    @Test
    @DisplayName("a simulation started through the UI plays a complete, legal game")
    void playsACompleteGameThroughTheWholeStack() throws Exception {
        String sessionId = createSession();

        startSimulation(sessionId);
        JsonNode finished = awaitTerminalSession(sessionId);

        assertThat(finished.get("sessionStatus").asText()).isEqualTo("FINISHED");

        String status = finished.get("gameStatus").asText();
        assertThat(status).isIn("X_WON", "O_WON", "DRAW");

        // The shortest possible win takes five moves; a full board takes nine. Anything outside
        // that range means the loop stopped early or kept going past a terminal state.
        int moveCount = finished.get("moves").size();
        assertThat(moveCount).isBetween(5, 9);

        assertMovesAlternateStartingWithX(finished.get("moves"));
        assertBoardMatchesMoveCount(finished.get("board").asText(), moveCount);

        // The engine is the authority. If these two disagree, the session drifted from real state.
        JsonNode engineState = getJson(engine.baseUrl() + "/games/" + sessionId);
        assertThat(engineState.get("board").asText()).isEqualTo(finished.get("board").asText());
        assertThat(engineState.get("status").asText()).isEqualTo(status);
    }

    @Test
    @DisplayName("every move reaches a browser over SSE, in order")
    void streamsEveryMoveToASubscriber() throws Exception {
        String sessionId = createSession();

        try (SseSubscription stream = subscribe(sessionId)) {
            stream.awaitEvent("snapshot");

            startSimulation(sessionId);
            JsonNode finished = awaitTerminalSession(sessionId);
            int expectedMoves = finished.get("moves").size();

            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(stream.eventsNamed("move-applied")).hasSize(expectedMoves));

            List<Integer> streamed = new ArrayList<>();
            for (String event : stream.eventsNamed("move-applied")) {
                streamed.add(JSON.readTree(event).get("move").get("moveNumber").asInt());
            }

            // Per-game ordering is what keying Kafka messages by game id buys; without it the
            // board would repaint out of sequence.
            assertThat(streamed).isSorted();
            assertThat(streamed).containsExactlyElementsOf(
                    java.util.stream.IntStream.rangeClosed(1, expectedMoves).boxed().toList());

            assertThat(stream.eventsNamed("game-finished")).hasSize(1);
        }
    }

    @Test
    @DisplayName("an unknown session is a 404 all the way through the UI service")
    void relaysNotFoundFromTheSessionService() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ui.baseUrl() + "/api/sessions/" + java.util.UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("session-not-found");
    }

    // --- assertions ----------------------------------------------------------------------------

    private void assertMovesAlternateStartingWithX(JsonNode moves) {
        for (int i = 0; i < moves.size(); i++) {
            String expected = i % 2 == 0 ? "X" : "O";
            assertThat(moves.get(i).get("symbol").asText())
                    .as("move %d should be played by %s", i + 1, expected)
                    .isEqualTo(expected);
        }
    }

    private void assertBoardMatchesMoveCount(String board, int moveCount) {
        assertThat(board).hasSize(9);
        assertThat(board.chars().filter(cell -> cell != '-').count())
                .as("board %s should hold exactly one mark per move", board)
                .isEqualTo(moveCount);
        assertThat(Set.of('X', 'O', '-')).containsAll(
                board.chars().mapToObj(cell -> (char) cell).toList());
    }

    // --- driving the system --------------------------------------------------------------------

    private String createSession() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ui.baseUrl() + "/api/sessions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"strategy\":\"random\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("creating a session, body: %s", response.body())
                .isEqualTo(201);

        return JSON.readTree(response.body()).get("sessionId").asText();
    }

    private void startSimulation(String sessionId) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ui.baseUrl() + "/api/sessions/" + sessionId + "/simulate"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("starting the simulation, body: %s", response.body())
                .isEqualTo(202);
    }

    private JsonNode awaitTerminalSession(String sessionId) throws Exception {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
            String status = getJson(ui.baseUrl() + "/api/sessions/" + sessionId)
                    .get("sessionStatus").asText();
            return status.equals("FINISHED") || status.equals("FAILED");
        });

        return getJson(ui.baseUrl() + "/api/sessions/" + sessionId);
    }

    private JsonNode getJson(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("GET %s returned %s", url, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private SseSubscription subscribe(String sessionId) throws Exception {
        HttpResponse<InputStream> response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(ui.baseUrl() + "/api/sessions/" + sessionId + "/stream"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.statusCode()).isEqualTo(200);
        return new SseSubscription(response.body());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Shutting down; a failure here would only mask the real test result.
        }
    }

    /** Minimal SSE client that records every {@code event:}/{@code data:} pair it sees. */
    private static final class SseSubscription implements AutoCloseable {

        private final InputStream body;
        private final List<String[]> events = new CopyOnWriteArrayList<>();
        private final Thread reader;

        private SseSubscription(InputStream body) {
            this.body = body;
            this.reader = new Thread(this::read, "sse-e2e-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void read() {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String name = null;
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        name = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:") && name != null) {
                        events.add(new String[]{name, line.substring("data:".length()).trim()});
                        name = null;
                    }
                }
            } catch (IOException closed) {
                // Stream ended; nothing further to read.
            }
        }

        List<String> eventsNamed(String name) {
            return events.stream().filter(event -> event[0].equals(name)).map(event -> event[1]).toList();
        }

        void awaitEvent(String name) {
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(eventsNamed(name)).isNotEmpty());
        }

        @Override
        public void close() throws IOException {
            body.close();
            reader.interrupt();
        }
    }
}
