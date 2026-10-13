package edu.oslab.threadpool;

import java.util.List;

public final class DeadlockDetectedException extends IllegalArgumentException {
    private final List<String> cycle;

    public DeadlockDetectedException(List<String> cycle) {
        super("Dependency cycle detected: " + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    public List<String> cycle() {
        return cycle;
    }
}
