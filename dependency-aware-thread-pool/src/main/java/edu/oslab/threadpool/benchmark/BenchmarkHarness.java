package edu.oslab.threadpool.benchmark;

import edu.oslab.threadpool.TaskMetrics;
import edu.oslab.threadpool.ThreadPoolManager;
import edu.oslab.threadpool.scheduler.SchedulerPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class BenchmarkHarness {
    private final int workerCount;
    private final Duration timeout;

    public BenchmarkHarness(int workerCount) {
        this(workerCount, Duration.ofSeconds(30));
    }

    public BenchmarkHarness(int workerCount, Duration timeout) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be at least 1");
        }
        this.workerCount = workerCount;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }

    public List<BenchmarkResult> compare(
            WorkloadFactory workloadFactory,
            List<Supplier<? extends SchedulerPolicy>> policyFactories) throws InterruptedException {
        Objects.requireNonNull(workloadFactory, "workloadFactory");
        Objects.requireNonNull(policyFactories, "policyFactories");
        if (policyFactories.isEmpty()) {
            throw new IllegalArgumentException("At least one scheduling policy is required");
        }

        List<BenchmarkResult> results = new ArrayList<>();
        for (Supplier<? extends SchedulerPolicy> policyFactory : policyFactories) {
            SchedulerPolicy policy = Objects.requireNonNull(policyFactory.get(), "policy");
            List<edu.oslab.threadpool.TaskSpec> workload = List.copyOf(workloadFactory.createWorkload());
            if (workload.isEmpty()) {
                throw new IllegalArgumentException("Benchmark workload must not be empty");
            }

            long runStarted = System.nanoTime();
            List<TaskMetrics> taskMetrics;
            try (ThreadPoolManager pool = new ThreadPoolManager(workerCount, policy)) {
                pool.submitBatch(workload);
                if (!pool.awaitCompletion(timeout)) {
                    throw new IllegalStateException(
                            policy.name() + " did not finish the workload within " + timeout);
                }
                taskMetrics = pool.taskMetrics();
                if (taskMetrics.stream().anyMatch(metrics -> metrics.state().isTerminal()
                        && metrics.state() != edu.oslab.threadpool.TaskState.DONE)) {
                    throw new IllegalStateException(policy.name() + " had one or more failed tasks");
                }
            }
            double elapsedSeconds = Math.max(1, System.nanoTime() - runStarted) / 1_000_000_000.0;
            double averageWaitingMillis = taskMetrics.stream()
                    .mapToLong(TaskMetrics::waitingTimeNanos)
                    .average()
                    .orElse(0) / 1_000_000.0;
            double averageTurnaroundMillis = taskMetrics.stream()
                    .mapToLong(TaskMetrics::turnaroundTimeNanos)
                    .average()
                    .orElse(0) / 1_000_000.0;
            results.add(new BenchmarkResult(
                    policy.name(),
                    taskMetrics.size(),
                    elapsedSeconds,
                    taskMetrics.size() / elapsedSeconds,
                    averageWaitingMillis,
                    averageTurnaroundMillis));
        }
        return List.copyOf(results);
    }
}
