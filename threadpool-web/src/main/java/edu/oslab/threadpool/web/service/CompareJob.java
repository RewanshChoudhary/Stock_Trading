package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.TaskMetrics;
import edu.oslab.threadpool.TaskSpec;
import edu.oslab.threadpool.TaskState;
import edu.oslab.threadpool.ThreadPoolManager;
import edu.oslab.threadpool.web.dto.PolicyComparisonDto;
import edu.oslab.threadpool.web.dto.TaskSpecDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs one workload under several policies, headlessly and sequentially, on a single platform
 * thread that is not a pool worker.
 *
 * <p>No {@link edu.oslab.threadpool.PoolEventListener} is attached: event recording must not be
 * able to influence the numbers the benchmark reports. A fresh set of {@link TaskSpec}s (and so
 * fresh {@link edu.oslab.threadpool.SimulatedWork} instances) is built for every repetition, so no
 * state leaks between runs.
 */
public final class CompareJob {

    public enum State {
        RUNNING,
        COMPLETED,
        FAILED
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CompareJob.class);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(2);

    private final String id;
    private final List<TaskSpecDto> taskDtos;
    private final List<PolicyKind> policies;
    private final int workerCount;
    private final long quantumMillis;
    private final int repetitions;
    private final List<PolicyComparisonDto> results = new CopyOnWriteArrayList<>();
    private final Thread runner;
    private volatile State state = State.RUNNING;
    private volatile String error;

    CompareJob(
            String id,
            List<TaskSpecDto> taskDtos,
            List<PolicyKind> policies,
            int workerCount,
            long quantumMillis,
            int repetitions) {
        this.id = id;
        this.taskDtos = List.copyOf(taskDtos);
        this.policies = List.copyOf(policies);
        this.workerCount = workerCount;
        this.quantumMillis = quantumMillis;
        this.repetitions = repetitions;
        this.runner = Thread.ofPlatform().name("compare-" + id).unstarted(this::run);
    }

    void start() {
        runner.start();
    }

    String id() {
        return id;
    }

    State state() {
        return state;
    }

    int repetitions() {
        return repetitions;
    }

    int totalPolicies() {
        return policies.size();
    }

    String error() {
        return error;
    }

    List<PolicyComparisonDto> results() {
        return List.copyOf(results);
    }

    private void run() {
        try {
            for (PolicyKind policy : policies) {
                results.add(measure(policy));
            }
            state = State.COMPLETED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            state = State.FAILED;
            error = "Compare job was interrupted";
        } catch (RuntimeException failure) {
            LOGGER.error("Compare job {} failed", id, failure);
            state = State.FAILED;
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        }
    }

    private PolicyComparisonDto measure(PolicyKind policy) throws InterruptedException {
        List<Double> makespans = new ArrayList<>(repetitions);
        List<Double> throughputs = new ArrayList<>(repetitions);
        List<Double> waits = new ArrayList<>(repetitions);
        List<Double> turnarounds = new ArrayList<>(repetitions);
        int taskCount = 0;
        int completedCount = 0;
        int failedCount = 0;

        for (int repetition = 0; repetition < repetitions; repetition++) {
            List<TaskSpec> workload = TaskSpecFactory.fromDtos(taskDtos);
            List<TaskMetrics> metrics;
            long startedNanos = System.nanoTime();
            try (ThreadPoolManager pool = new ThreadPoolManager(workerCount, policy.newScheduler(quantumMillis))) {
                pool.submitBatch(workload);
                if (!pool.awaitCompletion(RUN_TIMEOUT)) {
                    throw new IllegalStateException(
                            policy + " did not finish the workload within " + RUN_TIMEOUT);
                }
                metrics = pool.taskMetrics();
            }
            long elapsedNanos = Math.max(1, System.nanoTime() - startedNanos);
            double makespanMs = elapsedNanos / 1_000_000.0;
            makespans.add(makespanMs);
            throughputs.add(metrics.size() / (elapsedNanos / 1_000_000_000.0));
            waits.add(metrics.stream().mapToLong(TaskMetrics::waitingTimeNanos).average().orElse(0) / 1_000_000.0);
            turnarounds.add(
                    metrics.stream().mapToLong(TaskMetrics::turnaroundTimeNanos).average().orElse(0) / 1_000_000.0);
            taskCount = metrics.size();
            completedCount = (int) metrics.stream().filter(task -> task.state() == TaskState.DONE).count();
            failedCount = (int) metrics.stream().filter(task -> task.state() == TaskState.FAILED).count();
        }

        return new PolicyComparisonDto(
                policy.name(),
                policy.displayName(),
                repetitions,
                taskCount,
                completedCount,
                failedCount,
                mean(makespans),
                stdDev(makespans),
                mean(throughputs),
                stdDev(throughputs),
                mean(waits),
                mean(turnarounds));
    }

    private static double mean(List<Double> samples) {
        return samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Sample standard deviation; a single repetition has no spread to report. */
    private static double stdDev(List<Double> samples) {
        if (samples.size() < 2) {
            return 0;
        }
        double mean = mean(samples);
        double variance = samples.stream()
                .mapToDouble(sample -> (sample - mean) * (sample - mean))
                .sum() / (samples.size() - 1);
        return Math.sqrt(variance);
    }
}
