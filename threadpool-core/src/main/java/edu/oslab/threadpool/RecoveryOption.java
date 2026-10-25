package edu.oslab.threadpool;

import java.util.Objects;

/** One bounded recovery operation that can be scheduled for a failed task. */
public record RecoveryOption(
        String id,
        String type,
        long costMillis,
        int quality,
        int maxAttempts,
        TaskSpec executionSpec) {

    public RecoveryOption {
        id = Objects.requireNonNull(id, "id").trim();
        type = Objects.requireNonNull(type, "type").trim();
        if (id.isEmpty() || type.isEmpty()) {
            throw new IllegalArgumentException("Recovery option id and type must not be blank");
        }
        if (costMillis < 0) {
            throw new IllegalArgumentException("Recovery option cost must not be negative");
        }
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("Recovery option quality must be between 0 and 100");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Recovery option maxAttempts must be at least 1");
        }
        executionSpec = Objects.requireNonNull(executionSpec, "executionSpec");
        if (!executionSpec.dependencies().isEmpty()) {
            throw new IllegalArgumentException("Recovery execution tasks cannot have dependencies");
        }
    }
}
