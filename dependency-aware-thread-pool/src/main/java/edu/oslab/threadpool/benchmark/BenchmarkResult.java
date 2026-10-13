package edu.oslab.threadpool.benchmark;

import edu.oslab.threadpool.Lane;

public record BenchmarkResult(
        String policy,
        int tasks,
        double elapsedSeconds,
        double tasksPerSecond,
        double averageWaitingMillis,
        double averageTurnaroundMillis,
        long platformTaskCount,
        long virtualTaskCount,
        double platformAverageWaitingMillis,
        double virtualAverageWaitingMillis,
        double platformAverageTurnaroundMillis,
        double virtualAverageTurnaroundMillis) {

    public BenchmarkResult(
            String policy,
            int tasks,
            double elapsedSeconds,
            double tasksPerSecond,
            double averageWaitingMillis,
            double averageTurnaroundMillis) {
        this(policy, tasks, elapsedSeconds, tasksPerSecond, averageWaitingMillis, averageTurnaroundMillis,
                tasks, 0, averageWaitingMillis, 0, averageTurnaroundMillis, 0);
    }

    public long taskCount(Lane lane) {
        return lane == Lane.VIRTUAL ? virtualTaskCount : platformTaskCount;
    }
}
