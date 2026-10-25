package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.web.dto.PresetResponse;
import edu.oslab.threadpool.web.dto.TaskSpecDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Hard-coded demo workloads. Each preset is a normal run definition, so the frontend only has to
 * POST it to {@code /api/runs} and start it.
 */
public final class PresetFactory {

    private static final int DEFAULT_WORKERS = 4;

    /**
     * A short quantum keeps the demos interesting: burst times of 9-28 ms then take two to three
     * cooperative slices, so the frontend sees yields (and MLFQ demotions) instead of one slice per task.
     */
    private static final long DEFAULT_QUANTUM_MS = 10;

    private PresetFactory() {
    }

    public static List<PresetResponse> presets() {
        return List.of(buildPipeline(), cycleDemo(), diamond(), wideFanOut(), failureCascade());
    }

    /** A nine-task build pipeline with a branch that rejoins before linking. */
    private static PresetResponse buildPipeline() {
        return new PresetResponse(
                "Build Pipeline",
                "Nine-task build pipeline: parse and compile, then a parallel optimize/lint branch that rejoins for link, test, package and deploy.",
                PolicyKind.FCFS.name(),
                DEFAULT_WORKERS,
                DEFAULT_QUANTUM_MS,
                List.of(
                        ioTask("checkout", 14, 3),
                        task("parse", 11, 3, "checkout"),
                        task("compile", 27, 3, "parse"),
                        task("optimize", 23, 2, "compile"),
                        task("lint", 20, 2, "compile"),
                        task("link", 17, 3, "optimize", "lint"),
                        task("test", 28, 3, "link"),
                        task("package", 14, 2, "test"),
                        ioTask("deploy", 9, 2, "package")));
    }

    /** A deliberately cyclic graph; starting it must be rejected with the cycle path. */
    private static PresetResponse cycleDemo() {
        return new PresetResponse(
                "Cycle Demo",
                "Three tasks that depend on each other in a ring. Creating the run succeeds; starting it is rejected with the full cycle path.",
                PolicyKind.FCFS.name(),
                2,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("cycle-a", 10, 2, "cycle-c"),
                        task("cycle-b", 10, 2, "cycle-a"),
                        task("cycle-c", 10, 2, "cycle-b")));
    }

    /** Classic diamond: one root, two parallel branches, one join. */
    private static PresetResponse diamond() {
        return new PresetResponse(
                "Diamond",
                "One root fans out to two branches that must both finish before the join runs.",
                PolicyKind.ROUND_ROBIN.name(),
                DEFAULT_WORKERS,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("source", 18, 2),
                        task("unit-tests", 28, 2, "source"),
                        task("lint", 15, 2, "source"),
                        task("package", 17, 2, "unit-tests", "lint")));
    }

    /** One root, nine parallel dependants: grows the worker pool and shows scheduling order. */
    private static PresetResponse wideFanOut() {
        List<TaskSpecDto> tasks = new ArrayList<>();
        tasks.add(task("checkout", 12, 3));
        for (int index = 1; index <= 9; index++) {
            tasks.add(task("fan-out-" + index, 10 + (index % 4) * 6L, 1 + (index % 3), "checkout"));
        }
        return new PresetResponse(
                "Wide Fan-Out",
                "Ten tasks: one root with nine parallel dependants, which grows the pool and exposes the ready queue depth.",
                PolicyKind.MLFQ.name(),
                DEFAULT_WORKERS,
                DEFAULT_QUANTUM_MS,
                tasks);
    }

    /** A linear chain whose second task fails, so the cascade reaches every dependant. */
    private static PresetResponse failureCascade() {
        return new PresetResponse(
                "Failure Cascade",
                "Five-task chain where the second task blows up after 15 ms, failing the whole downstream chain.",
                PolicyKind.PRIORITY.name(),
                3,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("build", 20, 3),
                        failingTask("compile", 25, 3, 15, "build"),
                        task("unit-tests", 18, 2, "compile"),
                        task("package", 14, 2, "unit-tests"),
                        task("deploy", 12, 2, "package")));
    }

    private static TaskSpecDto task(String id, long burstMs, int priority, String... dependsOn) {
        return new TaskSpecDto(id, burstMs, priority, List.of(dependsOn), null, false);
    }

    /** A task that blocks on I/O, so it is routed to the virtual lane. */
    private static TaskSpecDto ioTask(String id, long burstMs, int priority, String... dependsOn) {
        return new TaskSpecDto(id, burstMs, priority, List.of(dependsOn), null, true);
    }

    private static TaskSpecDto failingTask(
            String id, long burstMs, int priority, long failAtMs, String... dependsOn) {
        return new TaskSpecDto(id, burstMs, priority, List.of(dependsOn), failAtMs, false);
    }
}
