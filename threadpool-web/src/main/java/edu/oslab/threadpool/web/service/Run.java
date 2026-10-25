package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.TaskMetrics;
import edu.oslab.threadpool.TaskSpec;
import edu.oslab.threadpool.TaskState;
import edu.oslab.threadpool.ThreadPoolManager;
import edu.oslab.threadpool.RecoveryAdvisor;
import edu.oslab.threadpool.RecoveryMode;
import edu.oslab.threadpool.RecoveryOptionProvider;
import edu.oslab.threadpool.web.event.EventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One simulator run: a dedicated {@link ThreadPoolManager} plus its {@link EventRecorder}.
 *
 * <p>The pool is created per run and never shared, and its workers start immediately. A supervisor
 * platform thread (not a pool worker) waits for the tasks to finish, shuts the pool down, and marks
 * the run terminal, so the run cleans itself up even if no client ever polls it.
 */
public final class Run {

    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);
    private static final Duration COMPLETION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);

    private final String id;
    private final PolicyKind policy;
    private final int workerCount;
    private final long quantumMillis;
    private final RecoveryMode recoveryMode;
    private final AdvisorKind advisorKind;
    private final List<TaskSpec> taskSpecs;
    private final EventRecorder recorder;
    private final ThreadPoolManager pool;
    private final long createdAtNanos = System.nanoTime();
    private final AtomicReference<RunState> state = new AtomicReference<>(RunState.CREATED);
    private final Thread supervisor;
    private volatile long finishedNanos;

    Run(
            String id,
            PolicyKind policy,
            int workerCount,
            long quantumMillis,
            List<TaskSpec> taskSpecs,
            EventRecorder recorder,
            RecoveryMode recoveryMode,
            AdvisorKind advisorKind,
            RecoveryAdvisor advisor,
            RecoveryOptionProvider optionProvider) {
        this.id = id;
        this.policy = policy;
        this.workerCount = workerCount;
        this.quantumMillis = quantumMillis;
        this.recoveryMode = recoveryMode;
        this.advisorKind = advisorKind;
        this.taskSpecs = List.copyOf(taskSpecs);
        this.recorder = recorder;
        this.pool = new ThreadPoolManager(
                workerCount,
                workerCount,
                workerCount,
                policy.newScheduler(quantumMillis),
                Duration.ofSeconds(2),
                100,
                false,
                recoveryMode,
                advisor,
                optionProvider);
        this.pool.setListener(recorder);
        this.supervisor = Thread.ofPlatform().name("run-supervisor-" + id).unstarted(this::supervise);
    }

    String id() {
        return id;
    }

    PolicyKind policy() {
        return policy;
    }

    int workerCount() {
        return workerCount;
    }

    long quantumMillis() {
        return quantumMillis;
    }

    RecoveryMode recoveryMode() {
        return recoveryMode;
    }

    AdvisorKind advisorKind() {
        return advisorKind;
    }

    List<TaskSpec> taskSpecs() {
        return taskSpecs;
    }

    EventRecorder recorder() {
        return recorder;
    }

    RunState state() {
        return state.get();
    }

    int taskCount() {
        return taskSpecs.size();
    }

    long createdAtNanos() {
        return createdAtNanos;
    }

    long finishedAtNanos() {
        return finishedNanos;
    }

    long elapsedMillis() {
        long end = finishedNanos == 0 ? System.nanoTime() : finishedNanos;
        return Math.max(0, (end - createdAtNanos) / 1_000_000);
    }

    /** Submits the batch and starts supervising it. Validates nothing: the pool's graph does. */
    void start() {
        if (!state.compareAndSet(RunState.CREATED, RunState.RUNNING)) {
            throw new IllegalStateException("Run " + id + " is already " + state.get());
        }
        try {
            pool.submitBatch(taskSpecs);
        } catch (RuntimeException failure) {
            // A rejected batch (cycle, unknown dependency, duplicate id) must not leave the run
            // looking active, and its workers must not leak.
            state.set(RunState.FAILED);
            pool.shutdown();
            recorder.ensureRunFinished();
            throw failure;
        }
        supervisor.start();
    }

    void cancel() {
        RunState previous;
        do {
            previous = state.get();
            if (previous.isTerminal()) {
                return;
            }
        } while (!state.compareAndSet(previous, RunState.CANCELLED));
        pool.cancel();
        if (previous == RunState.CREATED) {
            pool.shutdown();
            recorder.ensureRunFinished();
        }
    }

    void failTask(String taskId) {
        if (state.get().isTerminal()) {
            throw new IllegalStateException("Run " + id + " is already " + state.get());
        }
        pool.failTask(taskId);
    }

    List<TaskMetrics> taskMetrics() {
        return pool.taskMetrics();
    }

    int readyCount() {
        return pool.readyTaskCount();
    }

    int liveWorkerCount() {
        return pool.workerCount();
    }

    Map<String, Integer> taskStateCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TaskState candidate : TaskState.values()) {
            counts.put(candidate.name(), 0);
        }
        for (TaskMetrics metrics : pool.taskMetrics()) {
            counts.merge(metrics.state().name(), 1, Integer::sum);
        }
        return counts;
    }

    /** Cancels the run and drops its subscriptions; used when a run is deleted. */
    void discard() {
        cancel();
        pool.shutdown();
        recorder.closeAll();
    }

    /** Stops dispatch and waits for platform workers and virtual tasks during application shutdown. */
    void shutdownAndAwait(Duration timeout) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(timeout)) {
            LOGGER.warn("Run {} did not terminate within {} during application shutdown", id, timeout);
        }
        recorder.closeAll();
    }

    boolean isExpired(long nowNanos, Duration retention) {
        return state.get().isTerminal()
                && finishedNanos != 0
                && nowNanos - finishedNanos >= retention.toNanos();
    }

    private void supervise() {
        boolean completed = false;
        try {
            completed = pool.awaitCompletion(COMPLETION_TIMEOUT);
            if (!completed) {
                LOGGER.warn("Run {} exceeded {}; cancelling it", id, COMPLETION_TIMEOUT);
                pool.cancel();
            }
            pool.shutdown();
            if (!pool.awaitTermination(TERMINATION_TIMEOUT)) {
                LOGGER.warn("Run {} still has workers draining after {}", id, TERMINATION_TIMEOUT);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            finishedNanos = System.nanoTime();
            state.compareAndSet(RunState.RUNNING, completed ? RunState.FINISHED : RunState.FAILED);
            recorder.ensureRunFinished();
        }
    }

    @Override
    public String toString() {
        return "Run[" + id + " " + policy + " " + state.get() + "]";
    }
}
