package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.web.service.RunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the SSE endpoint over a real HTTP connection, including the {@code Last-Event-ID} resume
 * protocol and idle heartbeats.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "threadpool.sse.heartbeat-seconds=1")
class SseStreamTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RunService runService;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> createdRuns = new CopyOnWriteArrayList<>();

    @AfterEach
    void deleteCreatedRuns() {
        for (String runId : createdRuns) {
            try {
                runService.deleteRun(runId);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        createdRuns.clear();
    }

    @Test
    void theLiveStreamCarriesTheWholeRunInOrder() throws Exception {
        String runId = createRun(workload(4));
        try (SseStream stream = openStream(runId, null)) {
            startRun(runId);
            assertTrue(
                    stream.awaitFrames(frames -> frames.stream().anyMatch(frame -> frame.event().equals("RUN_FINISHED")),
                            Duration.ofSeconds(15)),
                    "the stream must deliver RUN_FINISHED");
            assertTrue(stream.awaitClosed(Duration.ofSeconds(5)), "the server must complete the stream");

            List<Frame> frames = stream.frames();
            assertEquals("RUN_STARTED", frames.getFirst().event());
            assertEquals("RUN_FINISHED", frames.getLast().event());
            for (int index = 0; index < frames.size(); index++) {
                assertEquals(index + 1L, frames.get(index).seq(), "frames must be sequenced in order");
                JsonNode data = objectMapper.readTree(frames.get(index).data());
                assertEquals(frames.get(index).seq(), data.get("seq").asLong(), data.toString());
                assertEquals(frames.get(index).event(), data.get("type").asText(), data.toString());
            }
            assertTrue(frames.stream().anyMatch(frame -> frame.event().equals("TASK_COMPLETED")));
            assertTrue(frames.stream().anyMatch(frame -> frame.event().equals("SLICE_ENDED")));
        }
    }

    @Test
    void reconnectingWithLastEventIdReplaysOnlyTheMissedEvents() throws Exception {
        String runId = createRun(workload(3));
        startRun(runId);
        awaitFinished(runId);

        try (SseStream stream = openStream(runId, 3L)) {
            assertTrue(stream.awaitClosed(Duration.ofSeconds(10)), "a finished run completes after the replay");

            List<Frame> frames = stream.frames();
            assertFalse(frames.isEmpty(), "the replay must include the events after sequence 3");
            assertEquals(4L, frames.getFirst().seq());
            for (int index = 0; index < frames.size(); index++) {
                assertEquals(4L + index, frames.get(index).seq(), "the replay must be gapless");
            }
        }
    }

    @Test
    void subscribingAfterTheRunEndedReplaysEverythingAndCompletes() throws Exception {
        String runId = createRun(workload(2));
        startRun(runId);
        awaitFinished(runId);

        try (SseStream stream = openStream(runId, null)) {
            assertTrue(stream.awaitClosed(Duration.ofSeconds(10)));
            List<Frame> frames = stream.frames();
            assertEquals("RUN_STARTED", frames.getFirst().event());
            assertEquals("RUN_FINISHED", frames.getLast().event());
            assertEquals(1L, frames.getFirst().seq());
        }
    }

    @Test
    void anIdleStreamSendsHeartbeats() throws Exception {
        String runId = createRun(workload(2));
        try (SseStream stream = openStream(runId, null)) {
            assertTrue(stream.awaitComments(2, Duration.ofSeconds(10)), "an idle stream must send heartbeat comments");
            assertTrue(stream.frames().size() <= 1, "an unstarted run must not emit anything beyond RUN_STARTED");
        }
    }

    @Test
    void theStreamIsClosedWhenTheRunIsDeleted() throws Exception {
        String runId = createRun(workload(2));
        try (SseStream stream = openStream(runId, null)) {
            startRun(runId);
            assertTrue(stream.awaitFrames(frames -> !frames.isEmpty(), Duration.ofSeconds(10)));

            runService.deleteRun(runId);
            assertTrue(stream.awaitClosed(Duration.ofSeconds(10)), "deleting a run must close its subscribers");
        }
    }

    private String createRun(String requestJson) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/api/runs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        String runId = objectMapper.readTree(response.body()).get("runId").asText();
        createdRuns.add(runId);
        return runId;
    }

    private void startRun(String runId) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/api/runs/" + runId + "/start"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private void awaitFinished(String runId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri("/api/runs/" + runId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if ("FINISHED".equals(objectMapper.readTree(response.body()).get("state").asText())) {
                return;
            }
            Thread.sleep(15);
        }
        throw new AssertionError("run " + runId + " did not finish");
    }

    private SseStream openStream(String runId, Long lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/runs/" + runId + "/events"))
                .header("Accept", "text/event-stream")
                .GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", Long.toString(lastEventId));
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        String contentType = response.headers().firstValue("content-type").orElse("");
        assertTrue(contentType.startsWith("text/event-stream"), "unexpected content type: " + contentType);
        return new SseStream(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String workload(int taskCount) {
        StringBuilder json = new StringBuilder("{\"policy\":\"FCFS\",\"workerCount\":2,\"quantum\":25,\"tasks\":[");
        for (int index = 0; index < taskCount; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"task-").append(index).append("\",\"burstMs\":30,\"priority\":2");
            if (index > 0) {
                json.append(",\"dependsOn\":[\"task-").append(index - 1).append("\"]");
            }
            json.append('}');
        }
        return json.append("]}").toString();
    }

    private record Frame(long seq, String event, String data) {
    }

    /** Minimal SSE client: reads frames and heartbeat comments on a background thread. */
    private static final class SseStream implements AutoCloseable {
        private final List<Frame> frames = new CopyOnWriteArrayList<>();
        private final List<String> comments = new CopyOnWriteArrayList<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final BufferedReader body;
        private final Thread reader;

        SseStream(InputStream body) {
            this.body = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            this.reader = Thread.ofPlatform().daemon(true).name("sse-test-reader").unstarted(this::read);
            this.reader.start();
        }

        List<Frame> frames() {
            return List.copyOf(frames);
        }

        boolean awaitFrames(Predicate<List<Frame>> condition, Duration timeout) throws InterruptedException {
            return await(() -> condition.test(frames()), timeout);
        }

        boolean awaitComments(int count, Duration timeout) throws InterruptedException {
            return await(() -> comments.size() >= count, timeout);
        }

        boolean awaitClosed(Duration timeout) throws InterruptedException {
            return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private boolean await(java.util.function.BooleanSupplier condition, Duration timeout)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(20);
            }
            return condition.getAsBoolean();
        }

        private void read() {
            try {
                String id = null;
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = body.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            frames.add(new Frame(id == null ? -1 : Long.parseLong(id), event, data.toString()));
                        }
                        id = null;
                        event = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith(":")) {
                        comments.add(line);
                    } else if (line.startsWith("id:")) {
                        id = line.substring(3).trim();
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // The stream closing or the connection dropping ends the read loop.
            } finally {
                closed.countDown();
            }
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            reader.interrupt();
        }
    }
}
