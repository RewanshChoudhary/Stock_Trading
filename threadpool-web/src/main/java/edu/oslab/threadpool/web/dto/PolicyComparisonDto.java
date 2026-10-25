package edu.oslab.threadpool.web.dto;

/** Mean and standard deviation of one policy's headless repetitions. */
public record PolicyComparisonDto(
        String policy,
        String displayName,
        int repetitions,
        int taskCount,
        int completedCount,
        int failedCount,
        double meanMakespanMs,
        double makespanStdDevMs,
        double meanThroughputTasksPerSecond,
        double throughputStdDev,
        double meanFirstDispatchWaitMs,
        double meanTurnaroundMs) {
}
