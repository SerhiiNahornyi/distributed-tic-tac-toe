package dev.nahornyi.tictactoe.ui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class UiConfiguration {

    /**
     * Client for the Game Session Service. Same reasoning as in the session service: explicit
     * timeouts, and HTTP/1.1 pinned so no h2c upgrade is attempted on a plaintext hop.
     */
    @Bean
    public RestClient sessionServiceRestClient(RestClient.Builder builder, UiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.sessionBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
