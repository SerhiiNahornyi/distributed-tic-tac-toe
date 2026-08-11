package dev.nahornyi.tictactoe.session;

import dev.nahornyi.tictactoe.session.config.SessionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(SessionProperties.class)
public class GameSessionApplication {

    public static void main(String[] args) {
        SpringApplication.run(GameSessionApplication.class, args);
    }
}
