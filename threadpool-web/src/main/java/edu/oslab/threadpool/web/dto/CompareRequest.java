package edu.oslab.threadpool.web.dto;

import java.util.List;

/**
 * Runs one workload headlessly under several policies and reports the measured means and standard
 * deviations. {@code policies} defaults to all five when omitted.
 */
public record CompareRequest(
        List<TaskSpecDto> tasks,
        List<String> policies,
        Integer workerCount,
        Long quantum,
        Integer repetitions) {
}
