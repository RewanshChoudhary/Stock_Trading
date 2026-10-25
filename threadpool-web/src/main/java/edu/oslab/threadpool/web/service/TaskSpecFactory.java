package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.SimulatedWork;
import edu.oslab.threadpool.TaskSpec;
import edu.oslab.threadpool.web.dto.TaskSpecDto;
import edu.oslab.threadpool.web.error.RunValidationException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Turns request DTOs into core {@link TaskSpec}s, applying the documented bounds. */
public final class TaskSpecFactory {

    public static final long DEFAULT_BURST_MS = 20;
    public static final long MAX_BURST_MS = 60_000;
    public static final int DEFAULT_PRIORITY = 5;
    public static final int MIN_PRIORITY = 1;
    public static final int MAX_PRIORITY = 10;

    private TaskSpecFactory() {
    }

    /**
     * @throws RunValidationException for blank or duplicate ids, out-of-range values, or blank
     *                                dependency names
     */
    public static List<TaskSpec> fromDtos(List<TaskSpecDto> tasks) {
        Set<String> ids = new LinkedHashSet<>();
        List<TaskSpec> specs = new ArrayList<>(tasks.size());
        for (TaskSpecDto task : tasks) {
            if (task == null) {
                throw new RunValidationException("Task definitions must not be null");
            }
            String id = task.id() == null ? "" : task.id().trim();
            if (id.isEmpty()) {
                throw new RunValidationException("Every task needs a non-blank id");
            }
            if (!ids.add(id)) {
                throw new RunValidationException("Duplicate task id: " + id);
            }
            long burstMs = task.burstMs() == null ? DEFAULT_BURST_MS : task.burstMs();
            if (burstMs < 0 || burstMs > MAX_BURST_MS) {
                throw new RunValidationException(
                        "Task " + id + " burstMs must be between 0 and " + MAX_BURST_MS + ", but was " + burstMs);
            }
            int priority = task.priority() == null ? DEFAULT_PRIORITY : task.priority();
            if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
                throw new RunValidationException("Task " + id + " priority must be between " + MIN_PRIORITY
                        + " and " + MAX_PRIORITY + ", but was " + priority);
            }
            Long failAtMs = task.failAtMs();
            if (failAtMs != null && (failAtMs < 0 || failAtMs > MAX_BURST_MS)) {
                throw new RunValidationException(
                        "Task " + id + " failAtMs must be between 0 and " + MAX_BURST_MS);
            }
            Set<String> dependencies = new LinkedHashSet<>();
            for (String dependency : task.dependencies()) {
                if (dependency == null || dependency.isBlank()) {
                    throw new RunValidationException("Task " + id + " has a blank dependency");
                }
                dependencies.add(dependency.trim());
            }
            SimulatedWork work =
                    failAtMs == null ? new SimulatedWork(burstMs) : new SimulatedWork(burstMs, failAtMs);
            specs.add(new TaskSpec(
                    id, priority, burstMs, dependencies, work, Boolean.TRUE.equals(task.blocking())));
        }
        return List.copyOf(specs);
    }
}
