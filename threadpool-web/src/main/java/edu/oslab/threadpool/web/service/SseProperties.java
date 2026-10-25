package edu.oslab.threadpool.web.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SSE tuning, bound from {@code threadpool.sse.*}. */
@ConfigurationProperties(prefix = "threadpool.sse")
public record SseProperties(Integer queueCapacity, Integer heartbeatSeconds, Integer streamTimeoutMillis) {

    public SseProperties {
        queueCapacity = positive(queueCapacity, 1024, "queue-capacity");
        heartbeatSeconds = positive(heartbeatSeconds, 15, "heartbeat-seconds");
        streamTimeoutMillis = positive(streamTimeoutMillis, 1_800_000, "stream-timeout-millis");
    }

    private static Integer positive(Integer value, int fallback, String name) {
        int resolved = value == null ? fallback : value;
        if (resolved < 1) {
            throw new IllegalArgumentException("threadpool.sse." + name + " must be at least 1");
        }
        return resolved;
    }
}
