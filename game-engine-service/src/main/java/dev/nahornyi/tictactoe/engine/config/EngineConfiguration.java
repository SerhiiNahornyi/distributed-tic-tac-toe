package dev.nahornyi.tictactoe.engine.config;

import dev.nahornyi.tictactoe.contracts.event.GameTopics;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Clock;

@Configuration
public class EngineConfiguration {

    /**
     * Injected rather than calling {@code Instant.now()} inline, so tests can pin time and assert
     * on timestamps.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Created on startup so the topic exists with a known partition count instead of being
     * auto-created with broker defaults. Three partitions give room to scale consumers while
     * per-game ordering is still guaranteed by keying on the game id.
     */
    @Bean
    public NewTopic gameEventsTopic() {
        return TopicBuilder.name(GameTopics.GAME_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public OpenAPI engineOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Game Engine Service")
                .version("1.0.0")
                .description("Owns Tic Tac Toe rules: board state, move validation and game outcome."));
    }
}
