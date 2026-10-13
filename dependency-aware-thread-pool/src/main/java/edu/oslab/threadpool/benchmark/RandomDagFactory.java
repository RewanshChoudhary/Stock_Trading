package edu.oslab.threadpool.benchmark;

import edu.oslab.threadpool.SliceResult;
import edu.oslab.threadpool.TaskSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class RandomDagFactory {
    private RandomDagFactory() {
    }

    public static List<TaskSpec> generate(int taskCount, double edgeDensity, long seed) {
        if (taskCount < 1) {
            throw new IllegalArgumentException("taskCount must be at least 1");
        }
        if (!Double.isFinite(edgeDensity) || edgeDensity < 0 || edgeDensity > 1) {
            throw new IllegalArgumentException("edgeDensity must be between 0 and 1");
        }

        Random random = new Random(seed);
        List<TaskSpec> specs = new ArrayList<>(taskCount);
        for (int target = 0; target < taskCount; target++) {
            Set<String> dependencies = new LinkedHashSet<>();
            for (int source = 0; source < target; source++) {
                if (random.nextDouble() < edgeDensity) {
                    dependencies.add(taskId(source));
                }
            }
            int priority = random.nextInt(10);
            long burstMillis = 1 + random.nextInt(20);
            specs.add(new TaskSpec(
                    taskId(target),
                    priority,
                    burstMillis,
                    dependencies,
                    quantum -> SliceResult.done()));
        }
        return List.copyOf(specs);
    }

    private static String taskId(int index) {
        return "random-task-" + index;
    }
}
