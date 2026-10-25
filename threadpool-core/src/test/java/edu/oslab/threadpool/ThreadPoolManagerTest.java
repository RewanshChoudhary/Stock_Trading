package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.FcfsScheduler;
import edu.oslab.threadpool.scheduler.CriticalPathScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    @Test
    void blockingTaskRunsOnVirtualLaneAndOnlyThenUnblocksItsDependent() throws Exception {
        AtomicBoolean blockingCompleted = new AtomicBoolean(false);
        AtomicBoolean dependentObservedBlocking = new AtomicBoolean(false);
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler())) {
            TaskSpec blocking = new TaskSpec(
                    "io-step",
                    0,
                    1,
                    Set.of(),
                    quantum -> {
                        Thread.sleep(25);
                        blockingCompleted.set(true);
                        return SliceResult.done();
                    },
                    true);
            TaskSpec dependent = new TaskSpec(
                    "after-io",
                    0,
                    1,
                    Set.of("io-step"),
                    quantum -> {
                        dependentObservedBlocking.set(blockingCompleted.get());
                        return SliceResult.done();
                    });

            pool.submitBatch(List.of(blocking, dependent));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            assertEquals(Lane.VIRTUAL, pool.taskMetrics("io-step").lane());
            assertTrue(dependentObservedBlocking.get());
        }
    }

    @Test
    void awaitTerminationWaitsForVirtualTasksToFinish() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler())) {
            pool.submit(new TaskSpec(
                    "sleepy",
                    0,
                    1,
                    Set.of(),
                    quantum -> {
                        Thread.sleep(50);
                        return SliceResult.done();
                    },
                    true));
            pool.shutdown();
            assertFalse(pool.awaitTermination(Duration.ofMillis(5)));
            assertTrue(pool.awaitTermination(Duration.ofSeconds(2)));
            assertEquals(TaskState.DONE, pool.taskMetrics("sleepy").state());
        }
    }

    @Test
    void virtualSemaphoreCapsConcurrentTaskBodies() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<TaskSpec> tasks = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            tasks.add(new TaskSpec("limited-" + index, 0, 1, Set.of(), quantum -> {
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(20);
                } finally {
                    active.decrementAndGet();
                }
                return SliceResult.done();
            }, true));
        }
        try (ThreadPoolManager pool = new ThreadPoolManager(
                2, 2, 2, new FcfsScheduler(), Duration.ofSeconds(1), 2)) {
            pool.submitBatch(tasks);
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));
            assertEquals(2, peak.get());
        }
    }

    @Test
    void virtualTaskFailurePropagatesToAllDependents() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler())) {
            pool.submitBatch(List.of(
                    new TaskSpec("virtual-broken", 0, 1, Set.of(), quantum -> {
                        throw new IllegalArgumentException("virtual failure");
                    }, true),
                    completedTask("platform-dependent", Set.of("virtual-broken")),
                    new TaskSpec(
                            "virtual-descendant",
                            0,
                            1,
                            Set.of("platform-dependent"),
                            quantum -> SliceResult.done(),
                            true)));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));
            assertEquals(TaskState.FAILED, pool.taskMetrics("virtual-broken").state());
            assertEquals(TaskState.FAILED, pool.taskMetrics("platform-dependent").state());
            assertEquals(TaskState.FAILED, pool.taskMetrics("virtual-descendant").state());
        }
    }

    @Test
    void dependenciesResolveAcrossPlatformAndVirtualLanes() throws Exception {
        AtomicBoolean firstFinished = new AtomicBoolean();
        AtomicBoolean secondFinished = new AtomicBoolean();
        try (ThreadPoolManager pool = new ThreadPoolManager(2, new FcfsScheduler())) {
            pool.submitBatch(List.of(
                    new TaskSpec("platform-first", 0, 1, Set.of(), quantum -> {
                        firstFinished.set(true);
                        return SliceResult.done();
                    }),
                    new TaskSpec("virtual-middle", 0, 1, Set.of("platform-first"), quantum -> {
                        assertTrue(firstFinished.get());
                        secondFinished.set(true);
                        return SliceResult.done();
                    }, true),
                    new TaskSpec("platform-last", 0, 1, Set.of("virtual-middle"), quantum -> {
                        assertTrue(secondFinished.get());
                        return SliceResult.done();
                    })));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));
            assertEquals(Lane.PLATFORM, pool.taskMetrics("platform-first").lane());
            assertEquals(Lane.VIRTUAL, pool.taskMetrics("virtual-middle").lane());
            assertEquals(Lane.PLATFORM, pool.taskMetrics("platform-last").lane());
        }
    }

    @Test
    void mixedLaneCycleIsRejectedAtomically() {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler())) {
            assertThrows(
                    DeadlockDetectedException.class,
                    () -> pool.submitBatch(List.of(
                            completedTask("platform-a", Set.of("virtual-b")),
                            new TaskSpec(
                                    "virtual-b",
                                    0,
                                    1,
                                    Set.of("platform-a"),
                                    quantum -> SliceResult.done(),
                                    true))));
            assertTrue(pool.taskMetrics().isEmpty());
        }
    }

    @Test
    void automaticClassifierRoutesSleepingTaskTypeAfterItsSample() throws Exception {
        List<TaskSpec> tasks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            tasks.add(new TaskSpec("classified-" + index, 0, 1, Set.of(), new SleepingWork()));
            tasks.add(new TaskSpec("cpu-classified-" + index, 0, 1, Set.of(), new BusyWork()));
        }
        try (ThreadPoolManager pool = new ThreadPoolManager(
                1, 1, 1, new FcfsScheduler(), Duration.ofSeconds(1), 100, true)) {
            pool.submitBatch(tasks);
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));
            assertTrue(pool.taskMetrics().stream()
                    .filter(metrics -> metrics.id().startsWith("classified-"))
                    .anyMatch(metrics -> metrics.lane() == Lane.VIRTUAL));
            assertTrue(pool.taskMetrics().stream()
                    .filter(metrics -> metrics.id().startsWith("cpu-classified-"))
                    .allMatch(metrics -> metrics.lane() == Lane.PLATFORM));
        }
    }

    @Test
    void laneClassificationIsDisabledByDefault() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler())) {
            pool.submit(new TaskSpec("ordinary-task", 0, 1, Set.of(), new SleepingWork()));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(2)));
            assertEquals(Lane.PLATFORM, pool.taskMetrics("ordinary-task").lane());
        }
    }

    @Test
    void criticalPathWeightsMakeTheLongestChainRunBeforeIndependentWork() throws Exception {
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new CriticalPathScheduler())) {
            List<Task> submitted = pool.submitBatch(List.of(
                    recordingTask("chain-a", 10, Set.of(), order),
                    recordingTask("chain-b", 10, Set.of("chain-a"), order),
                    recordingTask("chain-c", 10, Set.of("chain-b"), order),
                    recordingTask("independent", 1, Set.of(), order)));
            assertEquals(30, submitted.get(0).criticalPathWeight());
            assertEquals(20, submitted.get(1).criticalPathWeight());
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));
            assertEquals(List.of("chain-a", "chain-b", "chain-c", "independent"), List.copyOf(order));
        }
    }

    @Test
    void criticalPathSchedulerRefreshesQueuedWeightsWhenLaterBatchExtendsAChain() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new CriticalPathScheduler())) {
            pool.submit(new TaskSpec("blocker", 0, 1, Set.of(), quantum -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return SliceResult.done();
            }));
            assertTrue(blockerStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            pool.submitBatch(List.of(
                    recordingTask("waiting-chain-head", 10, Set.of(), order),
                    recordingTask("waiting-independent", 20, Set.of(), order)));
            pool.submit(recordingTask("late-tail", 90, Set.of("waiting-chain-head"), order));
            releaseBlocker.countDown();
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(3)));
            assertEquals(
                    List.of("waiting-chain-head", "late-tail", "waiting-independent"),
                    List.copyOf(order));
        } finally {
            releaseBlocker.countDown();
        }
    }

    private static TaskSpec completedTask(String id, Set<String> dependencies) {
        return new TaskSpec(id, 0, 1, dependencies, quantum -> SliceResult.done());
    }

    private static TaskSpec recordingTask(
            String id,
            long estimate,
            Set<String> dependencies,
            ConcurrentLinkedQueue<String> order) {
        return new TaskSpec(id, 0, estimate, dependencies, quantum -> {
            order.add(id);
            return SliceResult.done();
        });
    }

    private static final class SleepingWork implements CooperativeWork {
        @Override
        public SliceResult runSlice(long quantumMillis) throws InterruptedException {
            Thread.sleep(5);
            return SliceResult.done();
        }
    }

    private static final class BusyWork implements CooperativeWork {
        @Override
        public SliceResult runSlice(long quantumMillis) {
            long value = 1;
            for (int index = 0; index < 500_000; index++) {
                value = 31 * value + index;
            }
            return value == 0 ? SliceResult.yielded() : SliceResult.done();
        }
    }
}
