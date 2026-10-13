package edu.oslab.threadpool.benchmark;

import edu.oslab.threadpool.Lane;
import edu.oslab.threadpool.SliceResult;
import edu.oslab.threadpool.TaskMetrics;
import edu.oslab.threadpool.TaskSpec;
import edu.oslab.threadpool.TaskState;
import edu.oslab.threadpool.ThreadPoolManager;
import edu.oslab.threadpool.scheduler.FcfsScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MixedWorkloadBenchmark {
    private static final int CPU_TASKS = 8;
    private static final int IO_TASKS = 200;
    private static final int TOTAL_TASKS = CPU_TASKS + IO_TASKS;
    private static final int WORKERS = 4;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    public record Result(
            String shape,
            String configuration,
            double meanMakespanMillis,
            double stdDevMakespanMillis,
            double platformAverageWaitMillis,
            double virtualAverageWaitMillis,
            double platformAverageTurnaroundMillis,
            double virtualAverageTurnaroundMillis) {
    }

    private record Sample(
            double elapsedMillis,
            double platformWaitMillis,
            double virtualWaitMillis,
            double platformTurnaroundMillis,
            double virtualTurnaroundMillis) {
    }

    public List<Result> run(int repeats) throws InterruptedException {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats must be at least 1");
        }
        List<Result> results = new ArrayList<>();
        for (String shape : List.of("independent", "layered", "cross-lane")) {
            for (String configuration : List.of("platform-only", "virtual-only", "hybrid")) {
                List<Sample> samples = new ArrayList<>(repeats);
                for (int repeat = 0; repeat < repeats; repeat++) {
                    samples.add(runOnce(shape, configuration));
                }
                results.add(summarize(shape, configuration, samples));
            }
        }
        return List.copyOf(results);
    }

    public static String format(List<Result> results) {
        StringBuilder report = new StringBuilder();
        report.append(String.format(
                "%-14s %-16s %17s %12s %16s %16s %20s %20s%n",
                "Shape", "Configuration", "Mean ms", "Std dev", "Platform wait", "Virtual wait",
                "Platform turn.", "Virtual turn."));
        report.append("-".repeat(140)).append(System.lineSeparator());
        for (Result result : results) {
            report.append(String.format(
                    "%-14s %-16s %17.3f %12.3f %16.3f %16.3f %20.3f %20.3f%n",
                    result.shape(),
                    result.configuration(),
                    result.meanMakespanMillis(),
                    result.stdDevMakespanMillis(),
                    result.platformAverageWaitMillis(),
                    result.virtualAverageWaitMillis(),
                    result.platformAverageTurnaroundMillis(),
                    result.virtualAverageTurnaroundMillis()));
        }
        return report.toString();
    }

    private Sample runOnce(String shape, String configuration) throws InterruptedException {
        List<TaskSpec> workload = workload(shape, configuration);
        List<TaskMetrics> metrics;
        try (ThreadPoolManager pool = new ThreadPoolManager(WORKERS, new FcfsScheduler())) {
            pool.submitBatch(workload);
            if (!pool.awaitCompletion(TIMEOUT)) {
                throw new IllegalStateException("Mixed workload did not finish within " + TIMEOUT);
            }
            metrics = pool.taskMetrics();
        }
        if (metrics.stream().anyMatch(metric -> metric.state() != TaskState.DONE)) {
            throw new IllegalStateException("Mixed workload contained failed tasks");
        }
        long submittedAt = metrics.stream().mapToLong(TaskMetrics::submittedNanos).min().orElseThrow();
        long finishedAt = metrics.stream().mapToLong(TaskMetrics::terminalNanos).max().orElseThrow();
        double elapsed = (finishedAt - submittedAt) / 1_000_000.0;
        List<TaskMetrics> platform = metrics.stream().filter(metric -> metric.lane() == Lane.PLATFORM).toList();
        List<TaskMetrics> virtual = metrics.stream().filter(metric -> metric.lane() == Lane.VIRTUAL).toList();
        return new Sample(
                elapsed,
                average(platform, true),
                average(virtual, true),
                average(platform, false),
                average(virtual, false));
    }

    private static List<TaskSpec> workload(String shape, String configuration) {
        boolean virtualAll = configuration.equals("virtual-only");
        boolean hybrid = configuration.equals("hybrid");
        List<String> ioIds = new ArrayList<>(IO_TASKS);
        for (int index = 0; index < IO_TASKS; index++) {
            ioIds.add("io-" + index);
        }
        List<TaskSpec> tasks = new ArrayList<>(TOTAL_TASKS);
        List<String> orderedIds = new ArrayList<>(TOTAL_TASKS);
        if (shape.equals("cross-lane")) {
            orderedIds.addAll(ioIds);
            for (int index = 0; index < CPU_TASKS; index++) {
                orderedIds.add("cpu-" + index);
            }
        } else {
            for (int index = 0; index < CPU_TASKS; index++) {
                orderedIds.add("cpu-" + index);
            }
            orderedIds.addAll(ioIds);
        }

        List<String> previousLayer = List.of();
        for (int index = 0; index < orderedIds.size(); index++) {
            String id = orderedIds.get(index);
            boolean cpu = id.startsWith("cpu-");
            Set<String> dependencies = new LinkedHashSet<>();
            if (shape.equals("layered")) {
                int layer = index / 52;
                if (layer > 0) {
                    dependencies.addAll(previousLayer);
                }
            } else if (shape.equals("cross-lane") && cpu) {
                dependencies.addAll(ioIds.subList(0, 20));
            }
            boolean blocking = virtualAll || (hybrid && !cpu);
            CooperativeKind kind = cpu ? CooperativeKind.CPU : CooperativeKind.IO;
            tasks.add(new TaskSpec(
                    id,
                    cpu ? 5 : 1,
                    cpu ? 2 : 30,
                    dependencies,
                    quantum -> kind.run(),
                    blocking));
            if (shape.equals("layered") && (index + 1) % 52 == 0) {
                previousLayer = List.copyOf(orderedIds.subList(index - 51, index + 1));
            }
        }
        return List.copyOf(tasks);
    }

    private static double average(List<TaskMetrics> metrics, boolean waiting) {
        return metrics.stream()
                .mapToLong(waiting ? TaskMetrics::waitingTimeNanos : TaskMetrics::turnaroundTimeNanos)
                .average()
                .orElse(0) / 1_000_000.0;
    }

    private static Result summarize(String shape, String configuration, List<Sample> samples) {
        double mean = samples.stream().mapToDouble(Sample::elapsedMillis).average().orElse(0);
        double variance = samples.stream()
                .mapToDouble(sample -> Math.pow(sample.elapsedMillis() - mean, 2))
                .average()
                .orElse(0);
        return new Result(
                shape,
                configuration,
                mean,
                Math.sqrt(variance),
                samples.stream().mapToDouble(Sample::platformWaitMillis).average().orElse(0),
                samples.stream().mapToDouble(Sample::virtualWaitMillis).average().orElse(0),
                samples.stream().mapToDouble(Sample::platformTurnaroundMillis).average().orElse(0),
                samples.stream().mapToDouble(Sample::virtualTurnaroundMillis).average().orElse(0));
    }

    private enum CooperativeKind {
        CPU {
            @Override
            SliceResult run() {
                long value = 17;
                for (int i = 0; i < 25_000; i++) {
                    value = value * 31 + i;
                }
                return value == Long.MIN_VALUE ? SliceResult.yielded() : SliceResult.done();
            }
        },
        IO {
            @Override
            SliceResult run() throws InterruptedException {
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(10, 51));
                return SliceResult.done();
            }
        };

        abstract SliceResult run() throws InterruptedException;
    }
}
