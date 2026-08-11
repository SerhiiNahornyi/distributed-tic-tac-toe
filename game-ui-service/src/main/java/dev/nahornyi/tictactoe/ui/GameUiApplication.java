package dev.nahornyi.tictactoe.ui;

import dev.nahornyi.tictactoe.ui.config.UiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(UiProperties.class)
public class GameUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(GameUiApplication.class, args);
    }
}
