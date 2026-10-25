package edu.oslab.threadpool.web.dto;

import edu.oslab.threadpool.web.service.RunState;

import java.util.List;
import java.util.Map;

public record RunMetricsResponse(
        String runId,
        RunState state,
        String policy,
        long elapsedMs,
        double throughputTasksPerSecond,
        double avgFirstDispatchWaitMs,
        double avgTurnaroundMs,
        int taskCount,
        int completedCount,
        int failedCount,
        Map<String, Integer> stateCounts,
        List<TaskMetricsDto> perTask) {
}
