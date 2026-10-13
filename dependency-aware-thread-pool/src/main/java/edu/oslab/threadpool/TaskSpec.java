package edu.oslab.threadpool;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record TaskSpec(
        String id,
        int priority,
        long estimatedBurstMillis,
        Set<String> dependencies,
        CooperativeWork work) {

    public TaskSpec {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Task id must not be blank");
        }
        if (estimatedBurstMillis < 0) {
            throw new IllegalArgumentException("Estimated burst time must not be negative");
        }
        Objects.requireNonNull(dependencies, "dependencies");
        dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
        work = Objects.requireNonNull(work, "work");
    }
}
