package me.jittagornp.ai.config;

import io.micrometer.core.instrument.util.IOUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class OpenAiApiConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    log.info("Request URL: {}", request.getURI());
                    log.info("Request Body: {}", new String(body, StandardCharsets.UTF_8));
                    ClientHttpResponse clientHttpResponse = execution.execute(request, body);
                    try (InputStream inputStream = clientHttpResponse.getBody()) {
                        String json = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                        String modifiedJson = rewriteFinishReason(json);

                        log.info("Original JSON Response: {}", json);
                        log.info("Modified JSON Response: {}", modifiedJson);
                        return new ModifyClientResponse(
                                clientHttpResponse,
                                new ByteArrayInputStream(modifiedJson.getBytes(StandardCharsets.UTF_8))
                        );
                    }
                });
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .filter((request, next) -> {
                    log.info("Request URL: {}", request.url());
                    return next.exchange(request)
                            .flatMap(response -> response.bodyToMono(String.class)
                                    .map(json -> {
                                        String modifiedJson = rewriteFinishReason(json);

                                        log.info("Original JSON Response: {}", json);
                                        log.info("Modified JSON Response: {}", modifiedJson);

                                        return response.mutate()
                                                .body(modifiedJson)
                                                .build();
                                    }));
                });
    }

    private String rewriteFinishReason(String json) {
        //Fixed bug for self-hosting LLM
        //Invalid finish_reason value
        return json.replaceAll("\"finish_reason\"\s*:\s*\"(.*?)\"", "\"finish_reason\":\"stop\"");
    }

    @RequiredArgsConstructor
    public static class ModifyClientResponse implements ClientHttpResponse {

        private final ClientHttpResponse original;

        private final InputStream newInputStream;

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return original.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return original.getStatusText();
        }

        @Override
        public void close() {
            try {
                newInputStream.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public InputStream getBody() {
            return newInputStream;
        }

        @Override
        public HttpHeaders getHeaders() {
            return original.getHeaders();
        }

    }

}
