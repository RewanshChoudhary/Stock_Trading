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
        report.append(System.lineSeparator()).append("Per-lane metrics").append(System.lineSeparator());
        report.append(String.format(
                "%-22s %12s %12s %16s %17s %18s %19s%n",
                "Policy", "Platform tasks", "Virtual tasks", "Platform wait", "Platform turn.", "Virtual wait",
                "Virtual turn."));
        report.append("-".repeat(126)).append(System.lineSeparator());
        for (BenchmarkResult result : results) {
            report.append(String.format(
                    "%-22s %12d %12d %16.3f %17.3f %18.3f %19.3f%n",
                    result.policy(),
                    result.platformTaskCount(),
                    result.virtualTaskCount(),
                    result.platformAverageWaitingMillis(),
                    result.platformAverageTurnaroundMillis(),
                    result.virtualAverageWaitingMillis(),
                    result.virtualAverageTurnaroundMillis()));
        }
        return report.toString();
    }
}
