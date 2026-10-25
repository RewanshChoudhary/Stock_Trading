package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.web.RestApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
class CompareEndpointTest extends RestApiTestSupport {

    @Test
    void everyPolicyIsComparedWithMeanAndStandardDeviation() throws Exception {
        String body = "{\"repetitions\":2,\"workerCount\":3,\"tasks\":[" + String.join(",", workload()) + "]}";
        var started = postJson("/api/compare", body);

        assertEquals(202, status(started));
        JsonNode job = json(started);
        assertEquals(5, job.get("totalPolicies").asInt());
        assertEquals(2, job.get("repetitions").asInt());

        JsonNode result = awaitCompare(job.get("jobId").asText(), Duration.ofMinutes(1));
        assertEquals("COMPLETED", result.get("state").asText(), result.toString());
        assertNull(result.get("error").isNull() ? null : result.get("error").asText());
        assertEquals(5, result.get("completedPolicies").asInt());

        JsonNode results = result.get("results");
        assertEquals(5, results.size());
        List<String> policies = new ArrayList<>();
        for (JsonNode policy : results) {
            policies.add(policy.get("policy").asText());
            assertEquals(2, policy.get("repetitions").asInt(), policy.toString());
            assertEquals(6, policy.get("taskCount").asInt(), policy.toString());
            assertEquals(6, policy.get("completedCount").asInt(), policy.toString());
            assertEquals(0, policy.get("failedCount").asInt(), policy.toString());
            assertTrue(policy.get("meanMakespanMs").asDouble() > 0, policy.toString());
            assertTrue(policy.get("makespanStdDevMs").asDouble() >= 0, policy.toString());
            assertTrue(policy.get("meanThroughputTasksPerSecond").asDouble() > 0, policy.toString());
            assertTrue(policy.get("throughputStdDev").asDouble() >= 0, policy.toString());
            assertTrue(policy.get("meanFirstDispatchWaitMs").asDouble() >= 0, policy.toString());
            assertTrue(policy.get("meanTurnaroundMs").asDouble() > 0, policy.toString());
            assertTrue(policy.get("displayName").asText().length() > 3, policy.toString());
        }
        assertTrue(policies.containsAll(List.of("FCFS", "PRIORITY", "ROUND_ROBIN", "MLFQ", "CRITICAL_PATH")));
    }

    @Test
    void aSubsetOfPoliciesCanBeCompared() throws Exception {
        String body = "{\"policies\":[\"MLFQ\",\"fcfs\"],\"repetitions\":1,\"tasks\":[" + String.join(",", workload()) + "]}";
        var started = postJson("/api/compare", body);
        assertEquals(202, status(started));

        JsonNode job = json(started);
        assertEquals(2, job.get("totalPolicies").asInt());
        JsonNode result = awaitCompare(job.get("jobId").asText(), Duration.ofMinutes(1));
        assertEquals(2, result.get("results").size());
        assertEquals(0.0, result.get("results").get(0).get("makespanStdDevMs").asDouble(), 0.0001);
    }

    @Test
    void aFailingWorkloadIsReportedWithoutFailingTheJob() throws Exception {
        String body = "{\"policies\":[\"FCFS\"],\"repetitions\":1,\"tasks\":["
                + task("first", 20, 2) + "," + failingTask("broken", 30, 2, 15, "first") + ","
                + task("dependent", 20, 2, "broken") + "]}";
        var started = postJson("/api/compare", body);
        assertEquals(202, status(started));

        JsonNode result = awaitCompare(json(started).get("jobId").asText(), Duration.ofMinutes(1));
        assertEquals("COMPLETED", result.get("state").asText());
        JsonNode policy = result.get("results").get(0);
        assertEquals(1, policy.get("completedCount").asInt(), policy.toString());
        assertEquals(2, policy.get("failedCount").asInt(), policy.toString());
    }

    @Test
    void invalidCompareRequestsAreRejectedSynchronously() throws Exception {
        var cyclic = postJson("/api/compare", "{\"tasks\":["
                + task("a", 10, 2, "c") + "," + task("b", 10, 2, "a") + "," + task("c", 10, 2, "b") + "]}");
        assertEquals(422, status(cyclic));
        assertEquals("DEPENDENCY_CYCLE", json(cyclic).get("code").asText());

        assertEquals(400, status(postJson("/api/compare", "{\"tasks\":[]}")));
        assertEquals(400, status(postJson("/api/compare", "{\"repetitions\":0,\"tasks\":[" + task("a", 10, 2) + "]}")));
        assertEquals(400, status(postJson("/api/compare", "{\"repetitions\":99,\"tasks\":[" + task("a", 10, 2) + "]}")));
        assertEquals(400, status(postJson("/api/compare", "{\"policies\":[\"nope\"],\"tasks\":[" + task("a", 10, 2) + "]}")));
        assertEquals(400, status(postJson("/api/compare", "{\"workerCount\":99,\"tasks\":[" + task("a", 10, 2) + "]}")));
    }

    @Test
    void unknownCompareJobsAreNotFound() throws Exception {
        var response = getJson("/api/compare/does-not-exist");
        assertEquals(404, status(response));
        assertEquals("RUN_NOT_FOUND", json(response).get("code").asText());
    }

    private JsonNode awaitCompare(String jobId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = json(getJson("/api/compare/" + jobId));
            if (!"RUNNING".equals(last.get("state").asText())) {
                return last;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("compare job " + jobId + " did not finish, last seen: " + last);
    }

    private static List<String> workload() {
        return List.of(
                task("source", 20, 2),
                task("left", 25, 2, "source"),
                task("right", 25, 2, "source"),
                task("package", 20, 2, "left", "right"),
                blockingTask("fetch", 15, 2, "source"),
                task("report", 15, 2, "package"));
    }
}
