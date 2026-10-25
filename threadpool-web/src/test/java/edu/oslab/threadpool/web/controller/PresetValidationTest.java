package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.web.RestApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every shipped preset must be accepted by the real API, and the cycle demo must be rejected. */
@SpringBootTest
@AutoConfigureMockMvc
class PresetValidationTest extends RestApiTestSupport {

    @Test
    void everyPresetRunsAndTheCycleDemoIsRejected() throws Exception {
        JsonNode presets = json(getJson("/api/presets"));
        Map<String, Integer> expectedTaskCounts = new LinkedHashMap<>();
        expectedTaskCounts.put("Build Pipeline", 9);
        expectedTaskCounts.put("Cycle Demo", 3);
        expectedTaskCounts.put("Diamond", 4);
        expectedTaskCounts.put("Wide Fan-Out", 10);
        expectedTaskCounts.put("Failure Cascade", 5);
        expectedTaskCounts.put("Recovery · Build Mesh", 16);
        expectedTaskCounts.put("Recovery · Service Graph", 18);
        expectedTaskCounts.put("Recovery · Release Gate", 15);

        assertEquals(expectedTaskCounts.size(), presets.size());
        for (int index = 0; index < presets.size(); index++) {
            JsonNode preset = presets.get(index);
            String name = preset.get("name").asText();
            assertEquals(expectedTaskCounts.get(name), preset.get("tasks").size(), name + " has the wrong task count");

            // A preset is already a valid create-run body: it carries policy, workers, quantum and tasks.
            String runId = createRun(preset.toString());
            var startResponse = postNoBody("/api/runs/" + runId + "/start");

            if ("Cycle Demo".equals(name)) {
                assertEquals(422, status(startResponse), name + " must be rejected as cyclic");
                assertEquals("DEPENDENCY_CYCLE", json(startResponse).get("code").asText());
                continue;
            }

            assertEquals(200, status(startResponse), name + " did not start: " + body(startResponse));
            JsonNode finished = awaitTerminal(runId, Duration.ofSeconds(30));
            assertEquals("FINISHED", finished.get("state").asText(), name + " did not finish");
            JsonNode metrics = json(getJson("/api/runs/" + runId + "/metrics"));
            assertEquals(preset.get("tasks").size(), metrics.get("taskCount").asInt(), name);
            if ("Failure Cascade".equals(name)) {
                assertEquals(1, metrics.get("completedCount").asInt(), name + ": only the first task may succeed");
                assertEquals(4, metrics.get("failedCount").asInt(), name + ": the cascade must fail the chain");
            } else if (name.startsWith("Recovery · ")) {
                assertRecoveryPreset(name, runId, metrics);
            } else {
                assertEquals(0, metrics.get("failedCount").asInt(), name + ": unexpected failures");
            }
        }
    }

    private void assertRecoveryPreset(String name, String runId, JsonNode metrics) throws Exception {
        // Five profiled recoveries succeed; exhaustion + leaf leave failed roots; cascade adds victims.
        assertEquals(5, metrics.get("recoveredCount").asInt(), name + ": five recoveries should succeed");
        assertTrue(metrics.get("failedCount").asInt() >= 3,
                name + ": source exhaust + leaf + at least one cascade victim");
        assertTrue(metrics.get("recoveryDecisionCount").asInt() >= 6,
                name + ": retry-then-cache and exhaust need multiple advisor decisions");

        String trace = body(getJson("/api/runs/" + runId + "/trace"));
        assertTrue(trace.contains("TASK_RECOVERED"), name + ": trace should include recovery events");
        assertTrue(trace.contains("TASK_CASCADE_FAILED"), name + ": exhaustion should cascade");

        Set<String> optionIds = new HashSet<>();
        JsonNode events = json(getJson("/api/runs/" + runId + "/trace"));
        for (JsonNode event : events) {
            if ("AI_DECISION".equals(event.path("type").asText()) && event.hasNonNull("recoveryOptionId")) {
                optionIds.add(event.get("recoveryOptionId").asText());
            }
        }
        assertTrue(optionIds.contains("cached-result"), name + ": missing cache recovery");
        assertTrue(optionIds.contains("retry"), name + ": missing retry recovery");
        assertTrue(optionIds.contains("substitute"), name + ": missing substitute recovery");
        assertTrue(optionIds.contains("skip-optional"), name + ": missing skip recovery");
    }

    @Test
    void presetsDescribeThemselvesForTheFrontend() throws Exception {
        JsonNode presets = json(getJson("/api/presets"));
        for (JsonNode preset : presets) {
            assertTrue(preset.get("workerCount").asInt() >= 1, preset.toString());
            assertTrue(preset.get("quantum").asLong() >= 1, preset.toString());
            assertTrue(preset.get("description").asText().length() > 20, preset.toString());
            for (JsonNode task : preset.get("tasks")) {
                assertTrue(task.get("burstMs").asLong() > 0, task.toString());
            }
        }
    }
}
