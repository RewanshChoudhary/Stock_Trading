package edu.oslab.threadpool.benchmark;

import java.util.List;

public final class MetricsReporter {
    private MetricsReporter() {
    }

    public static String format(List<BenchmarkResult> results) {
        StringBuilder report = new StringBuilder();
        report.append(String.format(
                "%-22s %10s %12s %14s %16s %19s%n",
                "Policy", "Tasks", "Elapsed (s)", "Tasks / sec", "Avg wait (ms)", "Avg turnaround (ms)"));
        report.append("-".repeat(98)).append(System.lineSeparator());
        for (BenchmarkResult result : results) {
            report.append(String.format(
                    "%-22s %10d %12.4f %14.2f %16.3f %19.3f%n",
                    result.policy(),
                    result.tasks(),
                    result.elapsedSeconds(),
                    result.tasksPerSecond(),
                    result.averageWaitingMillis(),
                    result.averageTurnaroundMillis()));
        }
        return report.toString();
    }
}
