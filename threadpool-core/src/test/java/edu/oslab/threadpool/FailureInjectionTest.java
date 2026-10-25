package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.FcfsScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureInjectionTest {

    @Test
    void failingAWaitingTaskAlsoFailsItsDescendants() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submitBatch(List.of(
                    new TaskSpec("first", 0, 1, Set.of(), quantum -> {
                        firstStarted.countDown();
                        releaseFirst.await();
                        return SliceResult.done();
                    }),
                    completedTask("second", Set.of("first")),
                    completedTask("third", Set.of("second"))));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            pool.failTask("second");

            assertEquals(TaskState.FAILED, pool.taskMetrics("second").state());
            assertTrue(pool.taskMetrics("second").failureMessage().contains("Failure injected externally"));
            assertEquals(TaskState.FAILED, pool.taskMetrics("third").state());
            assertEquals(TaskState.RUNNING, pool.taskMetrics("first").state());

            releaseFirst.countDown();
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            assertEquals(TaskState.DONE, pool.taskMetrics("first").state());
        }
    }

    @Test
    void failingARunningTaskTakesEffectAtTheNextSliceBoundary() throws Exception {
        CountDownLatch sliceStarted = new CountDownLatch(1);
        CountDownLatch releaseSlice = new CountDownLatch(1);
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submit(new TaskSpec("running", 0, 5, Set.of(), quantum -> {
                sliceStarted.countDown();
                releaseSlice.await();
                return SliceResult.yielded();
            }));
            assertTrue(sliceStarted.await(2, TimeUnit.SECONDS));

            pool.failTask("running");

            // Cooperative cancellation cannot interrupt the in-flight slice.
            assertEquals(TaskState.RUNNING, pool.taskMetrics("running").state());
            releaseSlice.countDown();

            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            TaskMetrics metrics = pool.taskMetrics("running");
            assertEquals(TaskState.FAILED, metrics.state());
            assertEquals("Failure injected externally", metrics.failureMessage());
            assertEquals(1, metrics.slicesRun(), "the flagged slice is the task's last");
        }
    }

    @Test
    void failingAReadyTaskThatIsStillQueuedIsNeverDispatched() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        List<String> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submit(new TaskSpec("blocker", 0, 1, Set.of(), quantum -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return SliceResult.done();
            }));
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
            // The only worker is busy, so this task becomes READY while it waits in the FCFS queue,
            // which never drops finished tasks from its deque.
            pool.submit(new TaskSpec("queued", 0, 1, Set.of(), quantum -> {
                executed.add("queued");
                return SliceResult.done();
            }));
            pool.submit(new TaskSpec("also-queued", 0, 1, Set.of(), quantum -> {
                executed.add("also-queued");
                return SliceResult.done();
            }));

            pool.failTask("queued");
            releaseBlocker.countDown();

            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            assertEquals(TaskState.FAILED, pool.taskMetrics("queued").state());
            assertEquals(TaskState.DONE, pool.taskMetrics("also-queued").state());
            assertEquals(List.of("also-queued"), executed);
        }
    }

    @Test
    void cancellingQueuedTasksNeverDispatchesThem() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        List<String> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submit(new TaskSpec("blocker", 0, 1, Set.of(), quantum -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return SliceResult.done();
            }));
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
            pool.submitBatch(List.of(
                    new TaskSpec("queued-a", 0, 1, Set.of(), quantum -> {
                        executed.add("queued-a");
                        return SliceResult.done();
                    }),
                    new TaskSpec("queued-b", 0, 1, Set.of(), quantum -> {
                        executed.add("queued-b");
                        return SliceResult.done();
                    })));

            pool.cancel();
            releaseBlocker.countDown();

            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            assertTrue(pool.awaitTermination(Duration.ofSeconds(5)), "cancelled workers must exit");
            assertTrue(pool.taskMetrics().stream().allMatch(metrics -> metrics.state() == TaskState.FAILED));
            assertTrue(executed.isEmpty(), "queued tasks must not run after cancellation: " + executed);
        }
    }

    @Test
    void failingAWaitingTaskLeavesItsSiblingsRunnable() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submitBatch(List.of(
                    new TaskSpec("first", 0, 1, Set.of(), quantum -> {
                        firstStarted.countDown();
                        releaseFirst.await();
                        return SliceResult.done();
                    }),
                    completedTask("doomed-sibling", Set.of("first")),
                    completedTask("healthy-sibling", Set.of("first"))));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            pool.failTask("doomed-sibling");
            releaseFirst.countDown();

            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            assertEquals(TaskState.FAILED, pool.taskMetrics("doomed-sibling").state());
            assertEquals(TaskState.DONE, pool.taskMetrics("healthy-sibling").state());
        }
    }

    @Test
    void failingAnUnknownTaskIsRejected() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submit(completedTask("known", Set.of()));
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class, () -> pool.failTask("unknown"));
            assertTrue(exception.getMessage().contains("Unknown task: unknown"));
        }
    }

    @Test
    void failingAnAlreadyFinishedTaskIsANoOp() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.submit(new TaskSpec("fast", 0, 1, Set.of(), new SimulatedWork(1)));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            pool.failTask("fast");
            assertEquals(TaskState.DONE, pool.taskMetrics("fast").state());
        }
    }

    private static TaskSpec completedTask(String id, Set<String> dependencies) {
        return new TaskSpec(id, 0, 1, dependencies, quantum -> SliceResult.done());
    }
}
