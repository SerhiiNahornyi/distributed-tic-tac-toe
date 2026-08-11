package dev.nahornyi.tictactoe.ui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the UI service, bound from {@code tictactoe.ui.*}.
 *
 * @param sessionBaseUrl    base URL of the Game Session Service, which this service proxies
 * @param connectTimeout    connection timeout for that proxy
 * @param readTimeout       read timeout for that proxy
 * @param streamTimeout     how long an idle SSE connection is held open before the browser is
 *                          asked to reconnect. Long, because a game is watched for minutes
 * @param heartbeatInterval how often a keep-alive comment is written to open streams, so proxies
 *                          and load balancers do not silently drop a quiet connection
 */
@ConfigurationProperties(prefix = "tictactoe.ui")
public record UiProperties(
        String sessionBaseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration streamTimeout,
        Duration heartbeatInterval) {

    public UiProperties {
        sessionBaseUrl = sessionBaseUrl == null ? "http://localhost:8082" : sessionBaseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        streamTimeout = streamTimeout == null ? Duration.ofMinutes(30) : streamTimeout;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(20) : heartbeatInterval;
    }
}
