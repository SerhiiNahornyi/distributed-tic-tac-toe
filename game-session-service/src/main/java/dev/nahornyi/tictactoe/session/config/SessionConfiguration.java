package dev.nahornyi.tictactoe.session.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.Executor;

@Configuration
public class SessionConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The engine client, built on the auto-configured builder so it inherits Boot's observability
     * instrumentation and trace propagation.
     *
     * <p>Both timeouts are set explicitly. A client with no read timeout is the classic way to
     * exhaust a thread pool: one unresponsive engine would hold every simulation thread open
     * indefinitely, and no amount of retry configuration would help.
     *
     * <p>HTTP/1.1 is pinned deliberately. The JDK client otherwise attempts an h2c upgrade on every
     * plaintext connection, which buys nothing here - the engine serves HTTP/1.1 - and makes the
     * handshake dependent on how the peer handles the upgrade.
     */
    @Bean
    public RestClient gameEngineRestClient(RestClient.Builder builder, SessionProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.engineBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Runs simulations off the request thread so {@code POST /simulate} can answer 202 immediately.
     *
     * <p>Bounded pool and bounded queue: under load the service rejects new simulations with a
     * clear error rather than accumulating unbounded work and running out of memory.
     */
    @Bean("simulationExecutor")
    public Executor simulationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("simulation-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    public OpenAPI sessionOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Game Session Service")
                .version("1.0.0")
                .description("Creates sessions and drives automated play against the Game Engine Service."));
    }
}
