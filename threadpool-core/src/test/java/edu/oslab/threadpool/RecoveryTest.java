package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.FcfsScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    @Test
    void recoverySatisfiesAFailedPrerequisiteAndRunsItsDependent() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecoveryOptionProvider provider = (task, reason) -> List.of(new RecoveryOption(
                "cache", "CACHE", 1, 90, 1,
                new TaskSpec("cache-attempt", 1, 1, Set.of(), new SimulatedWork(1))));
        try (ThreadPoolManager pool = new ThreadPoolManager(
                1,
                new FcfsScheduler(10),
                RecoveryMode.RECOVER,
                new RuleBasedRecoveryAdvisor(),
                provider)) {
            pool.setListener(new PoolEventListener() {
                @Override
                public void onTaskRecovering(String taskId, String reason) {
                    events.add("recovering:" + taskId);
                }

                @Override
                public void onTaskRecovered(String taskId, String optionId) {
                    events.add("recovered:" + taskId + ":" + optionId);
                }
            });
            pool.submitBatch(List.of(
                    new TaskSpec("broken", 1, 5, Set.of(), new SimulatedWork(5, 1)),
                    new TaskSpec("dependent", 1, 5, Set.of("broken"), new SimulatedWork(1))));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            assertEquals(TaskState.FAILED, pool.taskMetrics("broken").state());
            assertEquals(TaskState.DONE, pool.taskMetrics("dependent").state());
            assertTrue(events.contains("recovering:dependent"));
            assertTrue(events.contains("recovered:broken:cache"));
        }
    }
}
