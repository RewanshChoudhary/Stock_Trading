package edu.oslab.threadpool.web.dto;

import java.util.List;

/** A named demo workload the frontend can load with one click. */
public record PresetResponse(
        String name,
        String description,
        String policy,
        Integer workerCount,
        Long quantum,
        List<TaskSpecDto> tasks) {
}
