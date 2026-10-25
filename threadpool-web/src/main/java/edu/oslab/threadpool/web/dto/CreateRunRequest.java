package edu.oslab.threadpool.web.dto;

import java.util.List;

/**
 * A run definition: which scheduling policy to use, how many platform workers to start, the
 * cooperative quantum, and the tasks of the DAG.
 */
public record CreateRunRequest(
        String policy,
        Integer workerCount,
        Long quantum,
        List<TaskSpecDto> tasks) {
}
