package edu.oslab.threadpool;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable information supplied to a recovery advisor. */
public record RecoveryContext(
        String taskId,
        String failureReason,
        int priority,
        long downstreamWorkMillis,
        int workerLoad,
        List<RecoveryOption> options,
        Set<String> attemptedOptionIds) {

    public RecoveryContext {
        taskId = Objects.requireNonNull(taskId, "taskId");
        failureReason = Objects.requireNonNull(failureReason, "failureReason");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        attemptedOptionIds = Set.copyOf(Objects.requireNonNull(attemptedOptionIds, "attemptedOptionIds"));
    }
}
