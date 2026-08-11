package dev.nahornyi.tictactoe.session.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for session simulation, bound from {@code tictactoe.session.*}.
 *
 * @param engineBaseUrl    base URL of the Game Engine Service
 * @param connectTimeout   how long to wait for a connection to the engine
 * @param readTimeout      how long to wait for a response; must stay well below the simulation
 *                         executor's patience so a hung engine fails fast rather than pinning a thread
 * @param moveDelay        pause between generated moves. Without it a game finishes in milliseconds
 *                         and the UI has nothing to animate; it is presentation pacing, not a fix
 *                         for a race
 * @param defaultStrategy  {@code random} or {@code blocking}
 * @param maxAttempts      hard ceiling on loop iterations, so a misbehaving engine can never spin
 *                         the simulation forever
 */
@ConfigurationProperties(prefix = "tictactoe.session")
public record SessionProperties(
        String engineBaseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration moveDelay,
        String defaultStrategy,
        int maxAttempts) {

    public SessionProperties {
        engineBaseUrl = engineBaseUrl == null ? "http://localhost:8081" : engineBaseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        moveDelay = moveDelay == null ? Duration.ofMillis(600) : moveDelay;
        defaultStrategy = defaultStrategy == null ? "random" : defaultStrategy;
        maxAttempts = maxAttempts <= 0 ? 20 : maxAttempts;
    }
}
