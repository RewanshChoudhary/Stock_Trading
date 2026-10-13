package edu.oslab.threadpool.benchmark;

public record BenchmarkResult(
        String policy,
        int tasks,
        double elapsedSeconds,
        double tasksPerSecond,
        double averageWaitingMillis,
        double averageTurnaroundMillis) {
}
