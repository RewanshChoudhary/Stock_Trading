package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.FcfsScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTest {

    @Test
    void cancelFailsEveryNonTerminalTaskAndDrainsTheWorkers() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        try (ThreadPoolManager pool = new ThreadPoolManager(2, new FcfsScheduler(20))) {
            pool.setListener(new PoolEventListener() {
                @Override
                public void onTaskCompleted(String taskId) {
                    completed.incrementAndGet();
                }

                @Override
                public void onRunAborted() {
                    events.add("RUN_ABORTED");
                }

                @Override
                public void onRunFinished() {
                    events.add("RUN_FINISHED");
                }
            });

            List<TaskSpec> batch = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                batch.add(new TaskSpec(
                        "long-" + index, 0, 5_000, Set.of(), new SimulatedWork(5_000)));
            }
            batch.add(new TaskSpec(
                    "waiting-on-long-0", 0, 1, Set.of("long-0"), new SimulatedWork(1)));
            pool.submitBatch(batch);

            Thread.sleep(150);
            pool.cancel();

            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)), "cancellation must resolve every task");
            assertTrue(pool.awaitTermination(Duration.ofSeconds(5)), "cancelled workers must exit");
            assertEquals(0, pool.workerCount());

            for (TaskMetrics metrics : pool.taskMetrics()) {
                assertEquals(TaskState.FAILED, metrics.state(), metrics.id() + " must end up failed");
                assertTrue(
                        metrics.failureMessage().contains("Run cancelled"),
                        metrics.id() + " failure message was: " + metrics.failureMessage());
            }
            assertEquals(0, completed.get(), "no task may complete after cancellation");
            assertEquals(List.of("RUN_ABORTED"), events);
        }
    }

    @Test
    void cancellingAnIdleRunIsImmediateAndRejectsLaterWork() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(20))) {
            pool.cancel();
            pool.cancel();
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(1)));
            assertTrue(pool.awaitTermination(Duration.ofSeconds(2)));
            assertTrue(pool.taskMetrics().isEmpty());
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> pool.submitBatch(List.of(
                            new TaskSpec("late", 0, 1, Set.of(), new SimulatedWork(1)))));
            assertTrue(exception.getMessage().contains("shutting down"));
        }
    }

    @Test
    void tasksThatAlreadyFinishedStayDoneWhenTheRunIsCancelled() throws Exception {
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(5))) {
            pool.submit(new TaskSpec("quick", 0, 1, Set.of(), new SimulatedWork(1)));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            pool.cancel();
            assertEquals(TaskState.DONE, pool.taskMetrics("quick").state());
            assertTrue(pool.awaitTermination(Duration.ofSeconds(2)));
        }
    }
}
