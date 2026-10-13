package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.FcfsScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThreadPoolManagerTest {
    @Test
    void concurrentSubmissionsExecuteEveryTaskExactlyOnce() throws Exception {
        int submitterCount = 4;
        int tasksPerSubmitter = 60;
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(submitterCount);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> submissionFailures = new ConcurrentLinkedQueue<>();

        try (ThreadPoolManager pool = new ThreadPoolManager(4, new FcfsScheduler())) {
            List<Thread> submitters = new ArrayList<>();
            for (int submitter = 0; submitter < submitterCount; submitter++) {
                int submitterId = submitter;
                Thread thread = new Thread(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        List<TaskSpec> batch = new ArrayList<>();
                        for (int index = 0; index < tasksPerSubmitter; index++) {
                            String id = "task-" + submitterId + "-" + index;
                            batch.add(new TaskSpec(
                                    id, 0, 1, Set.of(),
                                    quantum -> {
                                        executions.incrementAndGet();
                                        return SliceResult.done();
                                    }));
                        }
                        pool.submitBatch(batch);
                    } catch (Throwable failure) {
                        submissionFailures.add(failure);
                    }
                }, "submitter-" + submitterId);
                submitters.add(thread);
                thread.start();
            }

            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (Thread submitter : submitters) {
                submitter.join(2_000);
                assertFalse(submitter.isAlive(), "Submission thread did not finish");
            }
            assertTrue(submissionFailures.isEmpty(), submissionFailures.toString());
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(10)));
            assertEquals(submitterCount * tasksPerSubmitter, executions.get());
            assertEquals(submitterCount * tasksPerSubmitter, pool.taskMetrics().size());
            assertTrue(pool.taskMetrics().stream().allMatch(metrics -> metrics.state() == TaskState.DONE));
        }
    }

    @Test
    void dependenciesBecomeRunnableOnlyAfterPredecessorsFinish() throws Exception {
        AtomicBoolean firstFinished = new AtomicBoolean();
        AtomicBoolean secondFinished = new AtomicBoolean();
        AtomicBoolean thirdObservedValidOrder = new AtomicBoolean();

        try (ThreadPoolManager pool = new ThreadPoolManager(3, new FcfsScheduler())) {
            pool.submitBatch(List.of(
                    new TaskSpec("first", 0, 1, Set.of(), quantum -> {
                        Thread.sleep(15);
                        firstFinished.set(true);
                        return SliceResult.done();
                    }),
                    new TaskSpec("second", 0, 1, Set.of("first"), quantum -> {
                        if (!firstFinished.get()) {
                            throw new AssertionError("Second started before first finished");
                        }
                        secondFinished.set(true);
                        return SliceResult.done();
                    }),
                    new TaskSpec("third", 0, 1, Set.of("second"), quantum -> {
                        thirdObservedValidOrder.set(firstFinished.get() && secondFinished.get());
                        return SliceResult.done();
                    }),
                    new TaskSpec("independent", 0, 1, Set.of(), quantum -> SliceResult.done())));

            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));
            assertTrue(thirdObservedValidOrder.get());
            assertEquals(TaskState.DONE, pool.taskMetrics("third").state());
        }
    }

    @Test
    void rejectsCyclicBatchAtomicallyAndListsTheCycle() {
        try (ThreadPoolManager pool = new ThreadPoolManager(2, new FcfsScheduler())) {
            DeadlockDetectedException exception = assertThrows(
                    DeadlockDetectedException.class,
                    () -> pool.submitBatch(List.of(
                            completedTask("a", Set.of("b")),
                            completedTask("b", Set.of("c")),
                            completedTask("c", Set.of("a")))));

            assertEquals(List.of("a", "b", "c", "a"), exception.cycle());
            assertTrue(exception.getMessage().contains("a -> b -> c -> a"));
            assertTrue(pool.taskMetrics().isEmpty(), "A rejected batch must not partially register tasks");
        }
    }

    @Test
    void rejectsUnknownDependenciesWithoutRegisteringAnyTask() {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler())) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> pool.submitBatch(List.of(
                            completedTask("known", Set.of()),
                            completedTask("missing-parent-child", Set.of("not-submitted")))));
            assertTrue(pool.taskMetrics().isEmpty());
        }
    }

    @Test
    void taskFailureMarksItsDependentsFailedInsteadOfLeavingThemPending() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(2, new FcfsScheduler())) {
            pool.submitBatch(List.of(
                    new TaskSpec("broken", 0, 1, Set.of(), quantum -> {
                        throw new IllegalStateException("expected demo failure");
                    }),
                    completedTask("blocked", Set.of("broken"))));

            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));
            assertEquals(TaskState.FAILED, pool.taskMetrics("broken").state());
            assertEquals(TaskState.FAILED, pool.taskMetrics("blocked").state());
        }
    }

    @Test
    void automaticSizingGrowsForBacklogAndShrinksWhenIdle() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(
                1, 1, 4, new FcfsScheduler(), Duration.ofMillis(100))) {
            List<TaskSpec> batch = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                batch.add(new TaskSpec("resize-" + index, 0, 5, Set.of(), quantum -> {
                    Thread.sleep(5);
                    return SliceResult.done();
                }));
            }
            pool.submitBatch(batch);
            assertEquals(4, pool.workerCount(), "The backlog should grow the pool up to its configured maximum");
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));

            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (pool.workerCount() > 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, pool.workerCount(), "Idle workers should shrink back to the configured minimum");
        }
    }

    private static TaskSpec completedTask(String id, Set<String> dependencies) {
        return new TaskSpec(id, 0, 1, dependencies, quantum -> SliceResult.done());
    }
}
