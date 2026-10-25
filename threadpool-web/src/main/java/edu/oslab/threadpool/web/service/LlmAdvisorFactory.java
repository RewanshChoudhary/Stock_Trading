package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.RecoveryAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Creates an isolated advisor per run so call caps and one-call-per-task state never leak. */
@Component
public final class LlmAdvisorFactory {
    private static final String DEFAULT_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final Duration timeout;
    private final int maxCalls;

    public LlmAdvisorFactory(
            ObjectMapper objectMapper,
            @Value("${threadpool.recovery.llm.endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint,
            @Value("${threadpool.recovery.llm.timeout-ms:3000}") long timeoutMillis,
            @Value("${threadpool.recovery.llm.max-calls:10}") int maxCalls) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.maxCalls = maxCalls;
    }

    public RecoveryAdvisor newAdvisor() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        LlmTransport transport = prompt -> {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("GEMINI_API_KEY is not configured");
            }
            String separator = endpoint.contains("?") ? "&" : "?";
            java.util.Map<String, Object> body = java.util.Map.of(
                    "contents", java.util.List.of(java.util.Map.of(
                            "parts", java.util.List.of(java.util.Map.of("text", prompt)))),
                    "generationConfig", java.util.Map.of("responseMimeType", "application/json"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + separator + "key=" + apiKey))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        };
        return new LlmRecoveryAdvisor(objectMapper, transport, maxCalls);
    }
}
