package dev.nahornyi.tictactoe.e2e;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Runs one packaged service as a real operating-system process and waits for it to report healthy.
 *
 * <p>Separate JVMs rather than three Spring contexts in this one: the services have overlapping
 * classpaths (the engine brings JPA and H2, the others do not), and co-hosting them would make
 * auto-configuration behave differently here than in production. Launching the actual jars tests
 * what is actually shipped.
 */
final class ServiceProcess implements AutoCloseable {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    private final String name;
    private final int port;
    private final Process process;
    private final Path logFile;

    private ServiceProcess(String name, int port, Process process, Path logFile) {
        this.name = name;
        this.port = port;
        this.process = process;
        this.logFile = logFile;
    }

    /**
     * @param module        directory name of the service module, e.g. {@code game-engine-service}
     * @param port          port to bind
     * @param extraSettings additional Spring properties in {@code --key=value} form
     */
    static ServiceProcess start(String module, int port, String... extraSettings) throws IOException {
        Path jar = locateJar(module);
        Path logs = Path.of("target", "e2e-logs");
        Files.createDirectories(logs);
        Path logFile = logs.resolve(module + ".log");

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(),
                "--server.port=" + port));
        command.addAll(List.of(extraSettings));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();

        ServiceProcess service = new ServiceProcess(module, port, process, logFile);
        service.awaitHealthy();
        return service;
    }

    private static Path locateJar(String module) throws IOException {
        // The reactor builds this module last, so the service jars already exist next door.
        Path targetDir = Path.of("..", module, "target");
        try (Stream<Path> files = Files.list(targetDir)) {
            Optional<Path> jar = files
                    .filter(path -> path.getFileName().toString().startsWith(module))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .findFirst();

            return jar.orElseThrow(() -> new IllegalStateException(
                    "No packaged jar in " + targetDir.toAbsolutePath()
                            + ". Run `mvn package` for the whole project first."));
        }
    }

    private void awaitHealthy() {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        HttpRequest health = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/actuator/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        try {
            while (Instant.now().isBefore(deadline)) {
                if (!process.isAlive()) {
                    throw new IllegalStateException(
                            "%s exited during startup with code %d. Log: %s"
                                    .formatted(name, process.exitValue(), logFile.toAbsolutePath()));
                }
                try {
                    HttpResponse<String> response = client.send(health, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"")) {
                        return;
                    }
                } catch (IOException notUpYet) {
                    // Still booting; the loop's deadline is the real guard.
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + name, interrupted);
        } finally {
            client.shutdownNow();
        }

        throw new IllegalStateException(
                "%s did not become healthy within %s. Log: %s"
                        .formatted(name, STARTUP_TIMEOUT, logFile.toAbsolutePath()));
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    File log() {
        return logFile.toFile();
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
