package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.FcfsScheduler;
import edu.oslab.threadpool.scheduler.MlfqScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolEventListenerTest {

    @Test
    void completedDagReportsEveryTaskInDependencyOrder() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (ThreadPoolManager pool = new ThreadPoolManager(2, new FcfsScheduler(5))) {
            pool.setListener(listener);
            pool.submitBatch(List.of(
                    task("a", Set.of()),
                    task("b", Set.of("a")),
                    task("c", Set.of("a")),
                    task("d", Set.of("b", "c"))));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
        }

        assertEquals("RUN_STARTED:2:FCFS", listener.events.getFirst());
        assertEquals("RUN_FINISHED", listener.events.getLast());
        for (String id : List.of("a", "b", "c", "d")) {
            assertEquals(1, listener.count("TASK_SUBMITTED:" + id));
            assertEquals(1, listener.count("TASK_READY:" + id));
            assertEquals(1, listener.count("SLICE_STARTED:" + id));
            assertEquals(1, listener.count("SLICE_ENDED:" + id + ":DONE"));
            assertEquals(1, listener.count("TASK_COMPLETED:" + id));
            assertTrue(
                    listener.index("TASK_COMPLETED:" + id) > listener.index("SLICE_STARTED:" + id),
                    id + " must complete only after it has run");
        }

        // Submission is reported for the whole batch before any of it becomes ready.
        assertTrue(listener.index("TASK_READY:a") > listener.index("TASK_SUBMITTED:d"));
        assertTrue(listener.index("TASK_READY:b") > listener.index("TASK_COMPLETED:a"));
        assertTrue(listener.index("TASK_READY:c") > listener.index("TASK_COMPLETED:a"));
        assertTrue(listener.index("TASK_READY:d") > listener.index("TASK_COMPLETED:b"));
        assertTrue(listener.index("TASK_READY:d") > listener.index("TASK_COMPLETED:c"));
    }

    @Test
    void yieldedTaskReportsRepeatedSlicesWithoutRepeatingItsReadyEvent() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(10))) {
            pool.setListener(listener);
            pool.submit(new TaskSpec("sliced", 0, 50, Set.of(), new SimulatedWork(50)));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            assertEquals(TaskState.DONE, pool.taskMetrics("sliced").state());
            assertEquals(5, pool.taskMetrics("sliced").slicesRun());
        }

        assertEquals(1, listener.count("TASK_READY:sliced"));
        assertEquals(1, listener.count("SLICE_ENDED:sliced:DONE"));
        assertEquals(4, listener.count("SLICE_ENDED:sliced:YIELDED"));
        assertEquals(5, listener.count("SLICE_STARTED:sliced"));
        assertEquals(1, listener.count("TASK_COMPLETED:sliced"));
    }

    @Test
    void failureReportsTheCauseAndEveryCascadedDependant() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(5))) {
            pool.setListener(listener);
            pool.submitBatch(List.of(
                    new TaskSpec("broken", 0, 1, Set.of(), quantum -> {
                        throw new IllegalStateException("intentional");
                    }),
                    task("blocked", Set.of("broken")),
                    task("blocked-transitively", Set.of("blocked"))));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
        }

        assertEquals(1, listener.count("SLICE_ENDED:broken:FAILED"));
        assertTrue(listener.events.contains("TASK_FAILED:broken:IllegalStateException: intentional"));
        assertEquals(1, listener.count("TASK_CASCADE_FAILED:blocked:broken"));
        assertEquals(1, listener.count("TASK_CASCADE_FAILED:blocked-transitively:broken"));
        assertEquals(0, listener.count("TASK_COMPLETED:blocked"));
        assertEquals("RUN_FINISHED", listener.events.getLast());
    }

    @Test
    void levelBasedSchedulerReportsDemotionsAndBoosts() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (ThreadPoolManager pool = new ThreadPoolManager(
                2, new MlfqScheduler(new long[]{5, 10, 20}, Duration.ofMillis(20)))) {
            pool.setListener(listener);
            for (int index = 0; index < 6; index++) {
                pool.submit(new TaskSpec("mlfq-" + index, 0, 40, Set.of(), new SimulatedWork(40)));
            }
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(10)));
            assertTrue(pool.taskMetrics().stream().allMatch(metrics -> metrics.state() == TaskState.DONE));
        }

        assertTrue(listener.countPrefix("MLFQ_DEMOTED:") > 0, listener.events.toString());
        assertTrue(listener.count("MLFQ_BOOSTED") > 0, listener.events.toString());
    }

    @Test
    void resizingTheWorkerPoolIsReported() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (ThreadPoolManager pool = new ThreadPoolManager(
                1, 1, 4, new FcfsScheduler(20), Duration.ofSeconds(1))) {
            pool.setListener(listener);
            List<TaskSpec> backlog = new java.util.ArrayList<>();
            for (int index = 0; index < 20; index++) {
                backlog.add(new TaskSpec("backlog-" + index, 0, 10, Set.of(), new SimulatedWork(10)));
            }
            pool.submitBatch(backlog);
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(10)));
            assertEquals(4, pool.workerCount());
        }

        assertTrue(listener.events.contains("POOL_RESIZED:2"), listener.events.toString());
        assertTrue(listener.events.contains("POOL_RESIZED:4"), listener.events.toString());
    }

    @Test
    void aThrowingListenerNeverBreaksThePool() throws Exception {
        PoolEventListener exploding = new PoolEventListener() {
            @Override
            public void onTaskReady(String taskId, int readyQueueDepth) {
                throw new IllegalStateException("listener is broken");
            }

            @Override
            public void onSliceEnded(
                    String taskId,
                    String workerName,
                    Lane lane,
                    long sliceMs,
                    SliceOutcome outcome) {
                throw new IllegalStateException("listener is broken");
            }
        };
        try (ThreadPoolManager pool = new ThreadPoolManager(1, new FcfsScheduler(5))) {
            pool.setListener(exploding);
            pool.submit(new TaskSpec("survivor", 0, 1, Set.of(), quantum -> SliceResult.done()));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
            assertEquals(TaskState.DONE, pool.taskMetrics("survivor").state());
            pool.setListener(null);
            assertEquals(PoolEventListener.NO_OP, pool.listener());
        }
    }

    private static TaskSpec task(String id, Set<String> dependencies) {
        return new TaskSpec(id, 0, 1, dependencies, quantum -> SliceResult.done());
    }

    private static final class RecordingListener implements PoolEventListener {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onRunStarted(int workerCount, String policyName) {
            events.add("RUN_STARTED:" + workerCount + ":" + policyName);
        }

        @Override
        public void onRunFinished() {
            events.add("RUN_FINISHED");
        }

        @Override
        public void onTaskSubmitted(String taskId, int priority, long burstMs, Set<String> dependencies) {
            events.add("TASK_SUBMITTED:" + taskId);
        }

        @Override
        public void onTaskReady(String taskId, int readyQueueDepth) {
            assertTrue(readyQueueDepth > 0, "the ready queue must be non-empty when a task is queued");
            events.add("TASK_READY:" + taskId);
        }

        @Override
        public void onSliceStarted(String taskId, String workerName, Lane lane) {
            events.add("SLICE_STARTED:" + taskId);
        }

        @Override
        public void onSliceEnded(
                String taskId, String workerName, Lane lane, long sliceMs, SliceOutcome outcome) {
            events.add("SLICE_ENDED:" + taskId + ":" + outcome);
            assertTrue(sliceMs >= 0, "slice duration must not be negative");
        }

        @Override
        public void onTaskCompleted(String taskId) {
            events.add("TASK_COMPLETED:" + taskId);
        }

        @Override
        public void onTaskFailed(String taskId, String reason) {
            events.add("TASK_FAILED:" + taskId + ":" + reason);
        }

        @Override
        public void onTaskCascadeFailed(String taskId, String causeTaskId) {
            events.add("TASK_CASCADE_FAILED:" + taskId + ":" + causeTaskId);
        }

        @Override
        public void onMlfqDemoted(String taskId, int fromLevel, int toLevel) {
            events.add("MLFQ_DEMOTED:" + taskId + ":" + fromLevel + "->" + toLevel);
        }

        @Override
        public void onMlfqBoosted() {
            events.add("MLFQ_BOOSTED");
        }

        @Override
        public void onPoolResized(int newSize) {
            events.add("POOL_RESIZED:" + newSize);
        }

        @Override
        public void onRunAborted() {
            events.add("RUN_ABORTED");
        }

        private int index(String event) {
            int position = events.indexOf(event);
            assertTrue(position >= 0, "missing event " + event + " in " + events);
            return position;
        }

        private int count(String event) {
            return (int) events.stream().filter(recorded -> recorded.equals(event)).count();
        }

        private int countPrefix(String prefix) {
            return (int) events.stream().filter(recorded -> recorded.startsWith(prefix)).count();
        }
    }
}
