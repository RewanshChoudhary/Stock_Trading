package edu.oslab.threadpool.web.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resource limits for the simulator, so one client cannot exhaust a shared instance.
 * Bound from {@code threadpool.limits.*}.
 */
@ConfigurationProperties(prefix = "threadpool.limits")
public record RunLimitsProperties(
        Integer maxConcurrentRuns,
        Integer maxTasksPerRun,
        Integer maxTotalTasks,
        Integer maxWorkersPerRun,
        Integer runRetentionSeconds) {

    public RunLimitsProperties {
        maxConcurrentRuns = positive(maxConcurrentRuns, 5, "max-concurrent-runs");
        maxTasksPerRun = positive(maxTasksPerRun, 500, "max-tasks-per-run");
        maxTotalTasks = positive(maxTotalTasks, 2000, "max-total-tasks");
        maxWorkersPerRun = positive(maxWorkersPerRun, 16, "max-workers-per-run");
        runRetentionSeconds = positive(runRetentionSeconds, 300, "run-retention-seconds");
    }

    private static Integer positive(Integer value, int fallback, String name) {
        int resolved = value == null ? fallback : value;
        if (resolved < 1) {
            throw new IllegalArgumentException("threadpool.limits." + name + " must be at least 1");
        }
        return resolved;
    }
}
