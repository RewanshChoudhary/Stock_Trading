package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.web.RestApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "threadpool.limits.max-concurrent-runs=2",
        "threadpool.limits.max-tasks-per-run=3",
        "threadpool.limits.max-total-tasks=4"
})
@AutoConfigureMockMvc
class RunLimitsTest extends RestApiTestSupport {

    @Test
    void atMostTwoRunsMayBeActiveAtATime() throws Exception {
        String first = createRun(request("FCFS", 1, 25L, task("a", 300, 2), task("b", 20, 2, "a"), task("c", 20, 2, "b")));
        String second = createRun(request("FCFS", 1, 25L, task("only", 20, 2)));

        var third = postJson("/api/runs", request("FCFS", 1, 25L, task("late", 20, 2)));
        assertEquals(429, status(third), body(third));
        assertEquals("TOO_MANY_RUNS", json(third).get("code").asText());

        // A terminal run frees its slot.
        assertEquals(200, status(postNoBody("/api/runs/" + first + "/start")));
        assertEquals(200, status(postNoBody("/api/runs/" + second + "/start")));
        assertEquals("FINISHED", awaitTerminal(first, Duration.ofSeconds(10)).get("state").asText());
        assertEquals("FINISHED", awaitTerminal(second, Duration.ofSeconds(10)).get("state").asText());
        String fourth = createRun(request("FCFS", 1, 25L, task("later", 20, 2)));
        assertEquals(200, status(postNoBody("/api/runs/" + fourth + "/start")));
    }

    @Test
    void theTaskBudgetIsEnforcedPerRunAndAcrossRuns() throws Exception {
        var tooManyTasks = postJson("/api/runs", request(
                "FCFS", 1, 25L, task("a", 10, 2), task("b", 10, 2), task("c", 10, 2), task("d", 10, 2)));
        assertEquals(422, status(tooManyTasks), body(tooManyTasks));
        assertEquals("RUN_CAPACITY_EXCEEDED", json(tooManyTasks).get("code").asText());

        // Three tasks are already in flight, so only one more fits in the four-task budget.
        String first = createRun(request(
                "FCFS", 1, 25L, task("a", 300, 2), task("b", 20, 2, "a"), task("c", 20, 2, "b")));
        var overTotal = postJson("/api/runs", request("FCFS", 1, 25L, task("x", 10, 2), task("y", 10, 2)));
        assertEquals(422, status(overTotal), body(overTotal));
        assertEquals("RUN_CAPACITY_EXCEEDED", json(overTotal).get("code").asText());

        assertEquals(200, status(postNoBody("/api/runs/" + first + "/start")));
        assertEquals("FINISHED", awaitTerminal(first, Duration.ofSeconds(10)).get("state").asText());

        // With the first run finished its tasks no longer count against the budget.
        String afterFinish = createRun(request("FCFS", 1, 25L, task("x", 10, 2), task("y", 10, 2)));
        assertEquals(200, status(postNoBody("/api/runs/" + afterFinish + "/start")));
    }
}
