package edu.oslab.threadpool.web.dto;

import edu.oslab.threadpool.web.service.RunState;

import java.util.Map;

public record RunStateResponse(
        String runId,
        RunState state,
        String policy,
        int taskCount,
        int readyCount,
        int workerCount,
        long elapsedMs,
        Map<String, Integer> stateCounts) {
}
