package edu.oslab.threadpool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DependencyGraph {
    private final Map<String, Set<String>> dependencies = new LinkedHashMap<>();
    private final Map<String, Set<String>> dependents = new LinkedHashMap<>();
    private final Map<String, Integer> unresolvedCounts = new LinkedHashMap<>();
    private final Set<String> completed = new LinkedHashSet<>();

    /**
     * Validates a complete batch before changing the graph. Dependencies may refer
     * to tasks in this batch or tasks that have already been registered.
     */
    public Set<String> registerBatch(List<TaskSpec> batch) {
        Map<String, Set<String>> proposed = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            proposed.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }

        Set<String> batchIds = new LinkedHashSet<>();
        for (TaskSpec spec : batch) {
            if (!batchIds.add(spec.id()) || proposed.containsKey(spec.id())) {
                throw new IllegalArgumentException("Task id is already registered: " + spec.id());
            }
            proposed.put(spec.id(), new LinkedHashSet<>(spec.dependencies()));
        }

        for (TaskSpec spec : batch) {
            for (String dependency : spec.dependencies()) {
                if (!proposed.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "Task " + spec.id() + " depends on unknown task " + dependency);
                }
            }
        }

        List<String> cycle = CycleDetector.findCycle(proposed);
        if (!cycle.isEmpty()) {
            throw new DeadlockDetectedException(cycle);
        }

        for (TaskSpec spec : batch) {
            Set<String> taskDependencies = new LinkedHashSet<>(spec.dependencies());
            dependencies.put(spec.id(), taskDependencies);
            dependents.putIfAbsent(spec.id(), new LinkedHashSet<>());
            for (String dependency : taskDependencies) {
                dependents.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(spec.id());
            }
            int unresolved = (int) taskDependencies.stream().filter(id -> !completed.contains(id)).count();
            unresolvedCounts.put(spec.id(), unresolved);
        }

        Set<String> ready = new LinkedHashSet<>();
        for (TaskSpec spec : batch) {
            if (unresolvedCounts.get(spec.id()) == 0) {
                ready.add(spec.id());
            }
        }
        return Set.copyOf(ready);
    }

    public Set<String> markCompleted(String taskId) {
        if (!dependencies.containsKey(taskId)) {
            throw new IllegalArgumentException("Unknown task: " + taskId);
        }
        if (!completed.add(taskId)) {
            throw new IllegalStateException("Task already completed: " + taskId);
        }

        Set<String> newlyReady = new LinkedHashSet<>();
        for (String dependent : dependents.getOrDefault(taskId, Set.of())) {
            int remaining = unresolvedCounts.compute(dependent, (ignored, count) -> count - 1);
            if (remaining == 0) {
                newlyReady.add(dependent);
            }
        }
        return Set.copyOf(newlyReady);
    }

    public Set<String> descendantsOf(String taskId) {
        Set<String> result = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(dependents.getOrDefault(taskId, Set.of()));
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (result.add(next)) {
                queue.addAll(dependents.getOrDefault(next, Set.of()));
            }
        }
        return Set.copyOf(result);
    }

    public List<String> topologicalOrder() {
        Map<String, Integer> incomingEdges = new LinkedHashMap<>();
        for (String id : dependencies.keySet()) {
            incomingEdges.put(id, dependencies.get(id).size());
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        incomingEdges.forEach((id, count) -> {
            if (count == 0) {
                ready.addLast(id);
            }
        });

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String completedId = ready.removeFirst();
            order.add(completedId);
            for (String dependent : dependents.getOrDefault(completedId, Set.of())) {
                int remaining = incomingEdges.compute(dependent, (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        return List.copyOf(order);
    }

    public Map<String, Long> computeCriticalPathWeights(Map<String, Long> estimatedBurstMillis) {
        if (!estimatedBurstMillis.keySet().containsAll(dependencies.keySet())) {
            throw new IllegalArgumentException("Burst estimates are missing for registered tasks");
        }
        List<String> order = topologicalOrder();
        Map<String, Long> weights = new LinkedHashMap<>();
        for (int index = order.size() - 1; index >= 0; index--) {
            String id = order.get(index);
            long longestDependentPath = dependents.getOrDefault(id, Set.of()).stream()
                    .mapToLong(dependent -> weights.getOrDefault(dependent, 0L))
                    .max()
                    .orElse(0);
            weights.put(id, Math.addExact(estimatedBurstMillis.get(id), longestDependentPath));
        }
        return Map.copyOf(weights);
    }
}
