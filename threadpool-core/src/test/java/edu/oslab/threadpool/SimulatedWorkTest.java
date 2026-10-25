package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.FcfsScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedWorkTest {

    @Test
    void runsOneSlicePerQuantumUntilTheBurstIsSpent() throws Exception {
        SimulatedWork work = new SimulatedWork(100);
        int slices = 0;
        while (!work.runSlice(10).isComplete()) {
            slices++;
            assertTrue(slices < 100, "work should complete once its burst is spent");
        }

        assertEquals(10, slices + 1);
        assertEquals(10, work.slices());
        assertEquals(100, work.elapsedMs());
        assertEquals(100, work.totalBurstMs());
        assertEquals(-1, work.failAtMs());
    }

    @Test
    void failsOnTheSliceThatCrossesTheConfiguredFailureOffset() throws Exception {
        SimulatedWork work = new SimulatedWork(50, 25);

        assertEquals(SliceResult.yielded(), work.runSlice(10));
        assertEquals(SliceResult.yielded(), work.runSlice(10));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> work.runSlice(10));
        assertTrue(failure.getMessage().contains("Simulated failure at 25ms"));
        assertTrue(work.elapsedMs() >= 25 && work.elapsedMs() <= 35, "failed at " + work.elapsedMs());
    }

    @Test
    void aZeroLengthBurstCompletesWithoutYielding() throws Exception {
        assertEquals(SliceResult.done(), new SimulatedWork(0).runSlice(10));
    }

    @Test
    void thePoolDrivesSimulatedWorkThroughDeterministicSlices() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submit(new TaskSpec("simulated", 0, 60, Set.of(), new SimulatedWork(60)));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            TaskMetrics metrics = pool.taskMetrics("simulated");
            assertEquals(TaskState.DONE, metrics.state());
            assertEquals(6, metrics.slicesRun());
            assertTrue(metrics.turnaroundTimeNanos() >= Duration.ofMillis(50).toNanos());
        }
    }

    @Test
    void simulatedFailureSurfacesAsAFailedTask() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submit(new TaskSpec("doomed", 0, 50, Set.of(), new SimulatedWork(50, 25)));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            TaskMetrics metrics = pool.taskMetrics("doomed");
            assertEquals(TaskState.FAILED, metrics.state());
            assertTrue(metrics.failureMessage().contains("Simulated failure at 25ms"), metrics.failureMessage());
        }
    }

    @Test
    void rejectsNegativeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatedWork(-1));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedWork(10, -2));
    }
}
