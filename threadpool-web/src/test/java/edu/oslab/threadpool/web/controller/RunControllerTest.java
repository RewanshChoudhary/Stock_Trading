package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.web.RestApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest extends RestApiTestSupport {

    @Test
    void presetsExposeEveryDemoWorkload() throws Exception {
        var response = getJson("/api/presets");
        assertEquals(200, status(response));

        JsonNode presets = json(response);
        assertEquals(5, presets.size());
        assertEquals("Build Pipeline", presets.get(0).get("name").asText());
        assertEquals(9, presets.get(0).get("tasks").size());
        assertEquals("Cycle Demo", presets.get(1).get("name").asText());
        assertEquals(3, presets.get(1).get("tasks").size());
        assertTrue(presets.get(0).get("description").asText().toLowerCase().contains("pipeline"));
        assertFalse(presets.get(0).get("policy").asText().isBlank());
    }

    @Test
    void createStartObserveAndDeleteARun() throws Exception {
        String runId = createRun(request(
                "FCFS",
                3,
                25L,
                task("source", 20, 2),
                task("left", 30, 2, "source"),
                task("right", 30, 2, "source"),
                task("join", 20, 2, "left", "right"),
                blockingTask("fetch", 25, 2, "join")));

        JsonNode started = json(postNoBody("/api/runs/" + runId + "/start"));
        assertEquals("RUNNING", started.get("state").asText());
        assertEquals(5, started.get("taskCount").asInt());

        JsonNode finished = awaitTerminal(runId, Duration.ofSeconds(10));
        assertEquals("FINISHED", finished.get("state").asText());
        assertEquals(5, finished.get("stateCounts").get("DONE").asInt());
        assertEquals(0, finished.get("stateCounts").get("FAILED").asInt());

        JsonNode metrics = json(getJson("/api/runs/" + runId + "/metrics"));
        assertEquals("FINISHED", metrics.get("state").asText());
        assertEquals(5, metrics.get("completedCount").asInt());
        assertEquals(0, metrics.get("failedCount").asInt());
        assertTrue(metrics.get("throughputTasksPerSecond").asDouble() > 0);
        assertTrue(metrics.get("avgFirstDispatchWaitMs").asDouble() >= 0);
        assertTrue(metrics.get("avgTurnaroundMs").asDouble() > 0);
        assertEquals(5, metrics.get("perTask").size());
        assertTrue(metrics.get("perTask").get(0).get("slicesRun").asInt() >= 1);

        JsonNode trace = json(getJson("/api/runs/" + runId + "/trace"));
        assertTrue(trace.size() > 10, "the trace should contain the whole run");
        assertEquals("RUN_STARTED", trace.get(0).get("type").asText());
        assertEquals("RUN_FINISHED", trace.get(trace.size() - 1).get("type").asText());
        for (int index = 0; index < trace.size(); index++) {
            assertEquals(index + 1L, trace.get(index).get("seq").asLong(), "trace must be sequenced in order");
        }
        assertTrue(trace.toString().contains("VIRTUAL"), "the blocking task must report the virtual lane");

        assertEquals(204, status(deleteRun("/api/runs/" + runId)));
        assertEquals(404, status(getJson("/api/runs/" + runId)));
    }

    @Test
    void aDependencyCycleIsRejectedWithItsPath() throws Exception {
        String runId = createRun(request(
                "FCFS",
                2,
                25L,
                task("cycle-a", 10, 2, "cycle-c"),
                task("cycle-b", 10, 2, "cycle-a"),
                task("cycle-c", 10, 2, "cycle-b")));

        var response = postNoBody("/api/runs/" + runId + "/start");
        assertEquals(422, status(response));

        JsonNode error = json(response);
        assertEquals("DEPENDENCY_CYCLE", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("->"));
        assertEquals(4, error.get("details").size());
        assertEquals(
                error.get("details").get(0).asText(),
                error.get("details").get(3).asText(),
                "the reported cycle must close on itself");
        assertEquals("FAILED", json(getJson("/api/runs/" + runId)).get("state").asText());
    }

    @Test
    void invalidDefinitionsAreRejectedWithFourHundred() throws Exception {
        assertEquals(400, status(postJson("/api/runs", request("FCFS", 2, 25L, task("dup", 10, 2), task("dup", 10, 2)))));
        assertEquals(
                "INVALID_RUN_DEFINITION",
                json(postJson("/api/runs", request("FCFS", 2, 25L, task("dup", 10, 2), task("dup", 10, 2))))
                        .get("code")
                        .asText());

        assertEquals("INVALID_RUN_DEFINITION", json(postJson("/api/runs", request("FCFS", 2, 25L))).get("code").asText());

        JsonNode unknownPolicy = json(postJson("/api/runs", request("shortest-job-first", 2, 25L, task("a", 10, 2))));
        assertEquals("INVALID_RUN_DEFINITION", unknownPolicy.get("code").asText());
        assertTrue(unknownPolicy.get("message").asText().contains("FCFS"));

        assertEquals(
                "INVALID_RUN_DEFINITION",
                json(postJson("/api/runs", request("FCFS", 2, 5_000L, task("a", 10, 2)))).get("code").asText());
        assertEquals(
                "INVALID_RUN_DEFINITION",
                json(postJson("/api/runs", request("FCFS", 2, 25L, task("a", 10, 99)))).get("code").asText());
        assertEquals(
                "INVALID_RUN_DEFINITION",
                json(postJson("/api/runs", request("FCFS", 2, 25L, task("a", 10, 2), task("b", -5, 2)))).get("code").asText());
        assertEquals(
                "INVALID_RUN_DEFINITION",
                json(postJson("/api/runs", request("FCFS", 99, 25L, task("a", 10, 2)))).get("code").asText());
        assertEquals(
                "INVALID_RUN_DEFINITION",
                json(postJson("/api/runs", request("FCFS", 2, 25L, task("", 10, 2)))).get("code").asText());
    }

    @Test
    void malformedJsonAndUnknownRunsAreReported() throws Exception {
        var malformed = postJson("/api/runs", "{\"policy\":");
        assertEquals(400, status(malformed));
        assertEquals("MALFORMED_REQUEST", json(malformed).get("code").asText());

        assertEquals(404, status(getJson("/api/runs/missing")));
        assertEquals("RUN_NOT_FOUND", json(getJson("/api/runs/missing")).get("code").asText());
        assertEquals(404, status(postNoBody("/api/runs/missing/start")));
        assertEquals(404, status(postNoBody("/api/runs/missing/cancel")));
        assertEquals(404, status(deleteRun("/api/runs/missing")));
        assertEquals(404, status(getJson("/api/runs/missing/metrics")));
        assertEquals(404, status(getJson("/api/runs/missing/trace")));
    }

    @Test
    void anUnknownDependencyIsReportedWhenTheRunStarts() throws Exception {
        String runId = createRun(request("FCFS", 2, 25L, task("a", 10, 2, "not-submitted")));

        var response = postNoBody("/api/runs/" + runId + "/start");
        assertEquals(400, status(response));
        JsonNode error = json(response);
        assertEquals("INVALID_REQUEST", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("unknown task not-submitted"));
    }

    @Test
    void startingTheSameRunTwiceIsAConflict() throws Exception {
        String runId = createRun(request("FCFS", 1, 25L, task("slow", 250, 2)));
        assertEquals(200, status(postNoBody("/api/runs/" + runId + "/start")));

        var response = postNoBody("/api/runs/" + runId + "/start");
        assertEquals(409, status(response));
        assertEquals("INVALID_RUN_STATE", json(response).get("code").asText());
    }

    @Test
    void injectedFailuresCascadeToDependants() throws Exception {
        String runId = createRun(request(
                "FCFS",
                2,
                25L,
                task("first", 400, 2),
                task("broken", 20, 2, "first"),
                task("dependent", 20, 2, "broken"),
                task("grandchild", 20, 2, "dependent")));
        assertEquals(200, status(postNoBody("/api/runs/" + runId + "/start")));

        JsonNode afterFail = json(postNoBody("/api/runs/" + runId + "/tasks/broken/fail"));
        assertEquals(3, afterFail.get("stateCounts").get("FAILED").asInt(), afterFail.toString());
        assertEquals(1, afterFail.get("stateCounts").get("RUNNING").asInt(), afterFail.toString());

        JsonNode finished = awaitTerminal(runId, Duration.ofSeconds(10));
        assertEquals("FINISHED", finished.get("state").asText());
        assertEquals(1, finished.get("stateCounts").get("DONE").asInt());
        assertEquals(3, finished.get("stateCounts").get("FAILED").asInt());

        assertTrue(json(getJson("/api/runs/" + runId + "/metrics")).toString().contains("Failure injected externally"));
    }

    @Test
    void failingAnUnknownTaskIsRejected() throws Exception {
        String runId = createRun(request("FCFS", 1, 25L, task("only", 400, 2)));
        assertEquals(200, status(postNoBody("/api/runs/" + runId + "/start")));

        assertEquals(400, status(postNoBody("/api/runs/" + runId + "/tasks/nope/fail")));
    }

    @Test
    void cancellingARunFailsEveryTaskAndReleasesTheRun() throws Exception {
        String runId = createRun(request(
                "FCFS",
                2,
                25L,
                task("long-a", 4_000, 2),
                task("long-b", 4_000, 2),
                task("waiting", 20, 2, "long-a")));
        assertEquals(200, status(postNoBody("/api/runs/" + runId + "/start")));
        Thread.sleep(60);

        JsonNode cancelled = json(postNoBody("/api/runs/" + runId + "/cancel"));
        assertEquals("CANCELLED", cancelled.get("state").asText());

        JsonNode metrics = json(getJson("/api/runs/" + runId + "/metrics"));
        assertEquals("CANCELLED", metrics.get("state").asText());
        assertEquals(3, metrics.get("failedCount").asInt());
        assertEquals(0, metrics.get("completedCount").asInt());

        JsonNode trace = json(getJson("/api/runs/" + runId + "/trace"));
        assertEquals("RUN_ABORTED", trace.get(trace.size() - 1).get("type").asText());
    }

    @Test
    void aSimulatedFailureInsideATaskFailsTheWholeChain() throws Exception {
        String runId = createRun(request(
                "PRIORITY",
                2,
                25L,
                task("build", 30, 3),
                failingTask("compile", 40, 3, 15, "build"),
                task("package", 30, 2, "compile")));
        assertEquals(200, status(postNoBody("/api/runs/" + runId + "/start")));

        JsonNode finished = awaitTerminal(runId, Duration.ofSeconds(10));
        assertEquals("FINISHED", finished.get("state").asText());
        assertEquals(1, finished.get("stateCounts").get("DONE").asInt(), finished.toString());
        assertEquals(2, finished.get("stateCounts").get("FAILED").asInt(), finished.toString());
        assertTrue(json(getJson("/api/runs/" + runId + "/metrics")).toString().contains("Simulated failure at 15ms"));
    }

    @Test
    void mlfqRunsReportDemotionsInTheTrace() throws Exception {
        String runId = createRun(request(
                "MLFQ",
                2,
                null,
                task("sliced-a", 60, 2),
                task("sliced-b", 60, 2),
                task("sliced-c", 60, 2),
                task("sliced-d", 60, 2)));
        assertEquals(200, status(postNoBody("/api/runs/" + runId + "/start")));
        awaitTerminal(runId, Duration.ofSeconds(15));

        JsonNode trace = json(getJson("/api/runs/" + runId + "/trace"));
        assertTrue(trace.toString().contains("MLFQ_DEMOTED"), "MLFQ must report demotions");
        assertTrue(trace.toString().contains("SLICE_ENDED"));
    }
}
