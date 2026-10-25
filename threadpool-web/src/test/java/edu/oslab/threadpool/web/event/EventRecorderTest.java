package edu.oslab.threadpool.web.event;

import edu.oslab.threadpool.SimulatedWork;
import edu.oslab.threadpool.TaskSpec;
import edu.oslab.threadpool.ThreadPoolManager;
import edu.oslab.threadpool.scheduler.FcfsScheduler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRecorderTest {

    @Test
    void aSubscriberReceivesEveryEventOfARealRun() throws Exception {
        EventRecorder recorder = new EventRecorder("run-1", Duration.ofSeconds(1), 512);
        RecordingWriter writer = new RecordingWriter();

        try (ThreadPoolManager pool = new ThreadPoolManager(2, new FcfsScheduler(10))) {
            recorder.subscribe(writer, 0);
            pool.setListener(recorder);
            pool.submitBatch(List.of(
                    new TaskSpec("a", 0, 20, Set.of(), new SimulatedWork(20)),
                    new TaskSpec("b", 0, 20, Set.of("a"), new SimulatedWork(20)),
                    new TaskSpec("c", 0, 40, Set.of("b"), new SimulatedWork(40))));
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(5)));
        }

        assertTrue(writer.completed.await(5, TimeUnit.SECONDS), "the stream must be completed");
        assertEquals(PoolEventType.RUN_STARTED, writer.sent.getFirst().type());
        assertEquals(PoolEventType.RUN_FINISHED, writer.sent.getLast().type());
        assertSequentialFromOne(writer.seqs());
        assertTrue(writer.types().contains(PoolEventType.SLICE_ENDED));
        assertTrue(writer.types().contains(PoolEventType.TASK_COMPLETED));
        assertEquals(0, recorder.subscriberCount(), "a finished run must drop its subscribers");
    }

    @Test
    void concurrentSubscribersNeverMissOrDuplicateEvents() throws Exception {
        EventRecorder recorder = new EventRecorder("run-fanout", Duration.ofSeconds(1), 4_096);
        List<RecordingWriter> writers = new CopyOnWriteArrayList<>();

        try (ThreadPoolManager pool = new ThreadPoolManager(3, new FcfsScheduler(5))) {
            pool.setListener(recorder);
            List<TaskSpec> workload = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                workload.add(new TaskSpec("fan-" + index, 0, 15, Set.of(), new SimulatedWork(15)));
            }
            pool.submitBatch(workload);

            // Subscribe while the run is emitting, with the default Last-Event-ID of 0.
            for (int index = 0; index < 4; index++) {
                RecordingWriter writer = new RecordingWriter();
                writers.add(writer);
                recorder.subscribe(writer, 0);
                Thread.sleep(5);
            }
            assertTrue(pool.awaitCompletion(Duration.ofSeconds(10)));
        }

        for (RecordingWriter writer : writers) {
            assertTrue(writer.completed.await(5, TimeUnit.SECONDS));
            // Registering mid-run must still produce the complete trace: replay plus live, with no
            // gap and no duplicate.
            assertSequentialFromOne(writer.seqs());
        }
    }

    @Test
    void replayDeliversOnlyTheEventsAfterTheRequestedSequence() throws Exception {
        EventRecorder recorder = new EventRecorder("run-replay", Duration.ofSeconds(1), 128);
        recorder.onRunStarted(2, "FCFS");
        recorder.onTaskSubmitted("a", 5, 20, Set.of());
        recorder.onTaskReady("a", 1);
        recorder.onTaskCompleted("a");

        RecordingWriter writer = new RecordingWriter();
        SseSubscriber subscriber = recorder.subscribe(writer, 2);

        assertTrue(waitFor(() -> writer.seqs().size() == 2));
        assertEquals(List.of(3L, 4L), writer.seqs());
        assertEquals(PoolEventType.TASK_READY, writer.sent.getFirst().type());
        subscriber.close();
    }

    @Test
    void subscribingToAFinishedRunReplaysAndCompletesImmediately() throws Exception {
        EventRecorder recorder = new EventRecorder("run-done", Duration.ofSeconds(1), 64);
        recorder.onRunStarted(1, "FCFS");
        recorder.onRunFinished();
        recorder.ensureRunFinished();

        RecordingWriter writer = new RecordingWriter();
        recorder.subscribe(writer, 0);

        assertTrue(writer.completed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(PoolEventType.RUN_STARTED, PoolEventType.RUN_FINISHED), writer.types());
        assertEquals(1, writer.types().stream().filter(type -> type == PoolEventType.RUN_FINISHED).count());
    }

    @Test
    void aLaggingSubscriberIsClosedWithAnError() throws Exception {
        EventRecorder recorder = new EventRecorder("run-slow", Duration.ofSeconds(1), 2);
        BlockingWriter writer = new BlockingWriter();
        recorder.subscribe(writer, 0);

        recorder.onRunStarted(1, "FCFS");
        assertTrue(writer.firstSendStarted.await(5, TimeUnit.SECONDS));
        for (int index = 0; index < 6; index++) {
            recorder.onTaskReady("task-" + index, index + 1);
        }
        writer.release.countDown();

        assertTrue(writer.completed.await(5, TimeUnit.SECONDS));
        assertNotNull(writer.failure, "an overflowing subscriber must be closed with an error");
        assertTrue(waitFor(() -> recorder.subscriberCount() == 0));
    }

    @Test
    void heartbeatsKeepAnIdleStreamAlive() throws Exception {
        EventRecorder recorder = new EventRecorder("run-idle", Duration.ofMillis(100), 16);
        RecordingWriter writer = new RecordingWriter();
        SseSubscriber subscriber = recorder.subscribe(writer, 0);

        assertTrue(waitFor(() -> writer.comments.size() >= 2));
        assertTrue(writer.sent.isEmpty(), "an idle stream sends comments, not events");
        subscriber.close();
    }

    @Test
    void sequenceNumbersAreAssignedInOrderUnderConcurrentEmission() throws Exception {
        EventRecorder recorder = new EventRecorder("run-concurrent", Duration.ofSeconds(1), 4_096);
        RecordingWriter writer = new RecordingWriter();
        recorder.subscribe(writer, 0);

        int threads = 4;
        int eventsPerThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> emitters = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            String prefix = "t" + thread;
            emitters.add(Thread.ofPlatform().unstarted(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int index = 0; index < eventsPerThread; index++) {
                    recorder.onTaskReady(prefix + "-" + index, index + 1);
                }
            }));
        }
        emitters.forEach(Thread::start);
        start.countDown();
        for (Thread emitter : emitters) {
            emitter.join(5_000);
        }
        recorder.onRunFinished();

        assertTrue(writer.completed.await(5, TimeUnit.SECONDS));
        List<PoolEvent> trace = recorder.events();
        assertEquals(threads * eventsPerThread + 1, trace.size());
        assertSequentialFromOne(trace.stream().map(PoolEvent::seq).toList());
    }

    private static void assertSequentialFromOne(List<Long> sequences) {
        for (int index = 0; index < sequences.size(); index++) {
            assertEquals(index + 1L, sequences.get(index), "unexpected sequence at index " + index + ": " + sequences);
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static class RecordingWriter implements SseWriter {
        final List<PoolEvent> sent = new CopyOnWriteArrayList<>();
        final List<String> comments = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        volatile Throwable failure;

        @Override
        public void send(PoolEvent event) throws IOException {
            sent.add(event);
        }

        @Override
        public void comment(String comment) {
            comments.add(comment);
        }

        @Override
        public void complete() {
            completed.countDown();
        }

        @Override
        public void completeWithError(Throwable failure) {
            this.failure = failure;
            completed.countDown();
        }

        List<Long> seqs() {
            return sent.stream().map(PoolEvent::seq).toList();
        }

        List<PoolEventType> types() {
            return sent.stream().map(PoolEvent::type).toList();
        }
    }

    private static final class BlockingWriter extends RecordingWriter {
        final CountDownLatch firstSendStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void send(PoolEvent event) throws IOException {
            firstSendStarted.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test writer was never released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while blocking the subscriber", exception);
            }
            super.send(event);
        }
    }
}
