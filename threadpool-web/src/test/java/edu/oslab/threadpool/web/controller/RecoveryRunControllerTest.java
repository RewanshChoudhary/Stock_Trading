package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.web.RestApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
class RecoveryRunControllerTest extends RestApiTestSupport {
    @Test
    void explicitRecoveryRunReportsDecisionSourceAndRecoveredDependency() throws Exception {
        String runId = createRun("""
                {
                  "policy":"FCFS",
                  "workerCount":1,
                  "quantum":10,
                  "recoveryMode":"RECOVER",
                  "advisor":"RULE_BASED",
                  "tasks":[
                    {"id":"broken","burstMs":5,"priority":5,"failAtMs":1},
                    {"id":"dependent","burstMs":1,"priority":5,"dependsOn":["broken"]}
                  ]
                }
                """);
        assertEquals(200, status(postNoBody("/api/runs/" + runId + "/start")));

        JsonNode finished = awaitTerminal(runId, Duration.ofSeconds(10));
        assertEquals("FINISHED", finished.get("state").asText());
        JsonNode metrics = json(getJson("/api/runs/" + runId + "/metrics"));
        assertEquals(1, metrics.get("recoveredCount").asInt());
        assertEquals(1, metrics.get("stateCounts").get("DONE").asInt());

        JsonNode trace = json(getJson("/api/runs/" + runId + "/trace"));
        assertTrue(trace.toString().contains("TASK_RECOVERING"));
        assertTrue(trace.toString().contains("TASK_RECOVERED"));
        assertTrue(trace.toString().contains("Rule-based"));
    }
}
