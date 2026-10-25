package edu.oslab.threadpool.web.dto;

import edu.oslab.threadpool.Lane;
import edu.oslab.threadpool.TaskState;

public record TaskMetricsDto(
        String id,
        TaskState state,
        Lane lane,
        int slicesRun,
        double firstDispatchWaitMs,
        double turnaroundMs,
        String failureMessage) {
}
