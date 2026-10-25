package edu.oslab.threadpool.web.dto;

import java.util.List;

/** A named demo workload the frontend can load with one click. */
public record PresetResponse(
        String name,
        String description,
        String policy,
        Integer workerCount,
        Long quantum,
        List<TaskSpecDto> tasks,
        String recoveryMode,
        String advisor) {

    public PresetResponse(
            String name,
            String description,
            String policy,
            Integer workerCount,
            Long quantum,
            List<TaskSpecDto> tasks) {
        this(name, description, policy, workerCount, quantum, tasks, "RECOVER", "RULE_BASED");
    }
}
