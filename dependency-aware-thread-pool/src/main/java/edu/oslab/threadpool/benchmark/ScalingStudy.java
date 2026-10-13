package edu.oslab.threadpool.benchmark;

import edu.oslab.threadpool.TaskMetrics;
import edu.oslab.threadpool.TaskState;
import edu.oslab.threadpool.ThreadPoolManager;
import edu.oslab.threadpool.scheduler.CriticalPathScheduler;
import edu.oslab.threadpool.scheduler.FcfsScheduler;
import edu.oslab.threadpool.scheduler.MlfqScheduler;
import edu.oslab.threadpool.scheduler.PriorityScheduler;
import edu.oslab.threadpool.scheduler.RoundRobinScheduler;
import edu.oslab.threadpool.scheduler.SchedulerPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ScalingStudy {
    private static final int WORKER_COUNT = 4;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final List<Supplier<? extends SchedulerPolicy>> POLICIES = List.of(
            FcfsScheduler::new,
            PriorityScheduler::new,
            RoundRobinScheduler::new,
            MlfqScheduler::new,
            CriticalPathScheduler::new);

    private final double edgeDensity;

    public ScalingStudy() {
        this(0.25);
    }

    public ScalingStudy(double edgeDensity) {
        if (!Double.isFinite(edgeDensity) || edgeDensity < 0 || edgeDensity > 1) {
            throw new IllegalArgumentException("edgeDensity must be between 0 and 1");
        }
        this.edgeDensity = edgeDensity;
    }

    public ScalingReport run(int[] taskCounts, int runsPerPoint, int seed) throws InterruptedException {
        Objects.requireNonNull(taskCounts, "taskCounts");
        if (taskCounts.length == 0 || runsPerPoint < 1) {
            throw new IllegalArgumentException("At least one task count and one run per point are required");
        }
        List<ScalingReport.Row> rows = new ArrayList<>();
        for (int taskCount : taskCounts) {
            if (taskCount < 1) {
                throw new IllegalArgumentException("Task counts must be positive");
            }
            for (Supplier<? extends SchedulerPolicy> policyFactory : POLICIES) {
                List<RunMetrics> samples = new ArrayList<>(runsPerPoint);
                for (int run = 0; run < runsPerPoint; run++) {
                    long runSeed = seed + (long) taskCount * 1_000_003L + run;
                    samples.add(runOnce(taskCount, runSeed, policyFactory.get()));
                }
                rows.add(summarize(taskCount, policyFactory.get().name(), samples));
            }
        }
        return new ScalingReport(rows);
    }

    private RunMetrics runOnce(int taskCount, long seed, SchedulerPolicy policy) throws InterruptedException {
        List<edu.oslab.threadpool.TaskSpec> specs =
                RandomDagFactory.generate(taskCount, edgeDensity, seed);
        long started = System.nanoTime();
        List<TaskMetrics> metrics;
        try (ThreadPoolManager pool = new ThreadPoolManager(WORKER_COUNT, policy)) {
            pool.submitBatch(specs);
            if (!pool.awaitCompletion(TIMEOUT)) {
                throw new IllegalStateException(policy.name() + " exceeded scaling-study timeout " + TIMEOUT);
            }
            metrics = pool.taskMetrics();
        }
        if (metrics.stream().anyMatch(task -> task.state() != TaskState.DONE)) {
            throw new IllegalStateException(policy.name() + " failed one or more scaling-study tasks");
        }
        double elapsedSeconds = Math.max(1, System.nanoTime() - started) / 1_000_000_000.0;
        return new RunMetrics(
                elapsedSeconds * 1_000,
                taskCount / elapsedSeconds,
                mean(metrics.stream().mapToLong(TaskMetrics::waitingTimeNanos).toArray()) / 1_000_000.0,
                mean(metrics.stream().mapToLong(TaskMetrics::turnaroundTimeNanos).toArray()) / 1_000_000.0);
    }

    private static ScalingReport.Row summarize(int taskCount, String policy, List<RunMetrics> samples) {
        double[] makespans = samples.stream().mapToDouble(RunMetrics::makespanMillis).toArray();
        double meanMakespan = mean(makespans);
        double variance = 0;
        for (double makespan : makespans) {
            variance += Math.pow(makespan - meanMakespan, 2);
        }
        double stdDev = Math.sqrt(variance / makespans.length);
        return new ScalingReport.Row(
                taskCount,
                policy,
                meanMakespan,
                stdDev,
                mean(samples.stream().mapToDouble(RunMetrics::throughput).toArray()),
                mean(samples.stream().mapToDouble(RunMetrics::averageWaitMillis).toArray()),
                mean(samples.stream().mapToDouble(RunMetrics::averageTurnaroundMillis).toArray()));
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private record RunMetrics(
            double makespanMillis,
            double throughput,
            double averageWaitMillis,
            double averageTurnaroundMillis) {
    }
}
