package edu.oslab.threadpool.web;

import edu.oslab.threadpool.web.service.RunService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared helpers for driving the REST API through MockMvc. */
public abstract class RestApiTestSupport {

    private static final Set<String> TERMINAL_STATES = Set.of("FINISHED", "FAILED", "CANCELLED");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected RunService runService;

    private final List<String> createdRuns = new CopyOnWriteArrayList<>();

    /** Creates a run and remembers it for cleanup. */
    protected String createRun(String json) throws Exception {
        MvcResult result = postJson("/api/runs", json);
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("creating the run failed with " + result.getResponse().getStatus()
                    + ": " + result.getResponse().getContentAsString());
        }
        String runId = json(result).get("runId").asText();
        createdRuns.add(runId);
        return runId;
    }

    @AfterEach
    void deleteCreatedRuns() {
        for (String runId : createdRuns) {
            try {
                runService.deleteRun(runId);
            } catch (RuntimeException ignored) {
                // The test may have deleted it already.
            }
        }
        createdRuns.clear();
    }

    protected MvcResult postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    protected MvcResult postNoBody(String path) throws Exception {
        return mockMvc.perform(post(path)).andReturn();
    }

    protected MvcResult getJson(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn();
    }

    protected MvcResult deleteRun(String path) throws Exception {
        return mockMvc.perform(delete(path)).andReturn();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(body(result));
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    protected int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /** Polls {@code GET /api/runs/{id}} until the run reaches a terminal state. */
    protected JsonNode awaitTerminal(String runId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = json(getJson("/api/runs/" + runId));
            if (TERMINAL_STATES.contains(last.get("state").asText())) {
                return last;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + runId + " did not reach a terminal state, last seen: " + last);
    }

    protected static String request(String policy, Integer workers, Long quantum, String... tasks) {
        StringBuilder json = new StringBuilder("{\"policy\":\"").append(policy).append('"');
        if (workers != null) {
            json.append(",\"workerCount\":").append(workers);
        }
        if (quantum != null) {
            json.append(",\"quantum\":").append(quantum);
        }
        json.append(",\"tasks\":[");
        for (int index = 0; index < tasks.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(tasks[index]);
        }
        return json.append("]}").toString();
    }

    protected static String task(String id, long burstMs, int priority, String... dependencies) {
        StringBuilder json = new StringBuilder("{\"id\":\"").append(id).append("\",\"burstMs\":").append(burstMs)
                .append(",\"priority\":").append(priority);
        if (dependencies.length > 0) {
            json.append(",\"dependsOn\":[");
            for (int index = 0; index < dependencies.length; index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append('"').append(dependencies[index]).append('"');
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    protected static String failingTask(String id, long burstMs, int priority, long failAtMs, String... dependencies) {
        return withExtraField(task(id, burstMs, priority, dependencies), "\"failAtMs\":" + failAtMs);
    }

    protected static String blockingTask(String id, long burstMs, int priority, String... dependencies) {
        return withExtraField(task(id, burstMs, priority, dependencies), "\"blocking\":true");
    }

    private static String withExtraField(String taskJson, String extraField) {
        return taskJson.substring(0, taskJson.length() - 1) + "," + extraField + "}";
    }
}
