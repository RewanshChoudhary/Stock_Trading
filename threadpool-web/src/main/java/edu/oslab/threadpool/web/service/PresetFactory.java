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
        return List.of(
                buildPipeline(),
                cycleDemo(),
                diamond(),
                wideFanOut(),
                failureCascade(),
                recoveryBuildMesh(),
                recoveryServiceGraph(),
                recoveryReleaseGate());
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
                        ioTask("deploy", 9, 2, "package")),
                "RECOVER",
                "RULE_BASED");
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
                        task("cycle-c", 10, 2, "cycle-b")),
                "STRICT",
                "RULE_BASED");
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
                        task("package", 17, 2, "unit-tests", "lint")),
                "RECOVER",
                "RULE_BASED");
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
                tasks,
                "RECOVER",
                "RULE_BASED");
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
                        task("deploy", 12, 2, "package")),
                "STRICT",
                "RULE_BASED");
    }

    /**
     * Compile diamond with every recovery outcome: cache, retry, substitute, skip, retry-then-cache,
     * exhaustion cascade, and a leaf failure with no dependents.
     */
    private static PresetResponse recoveryBuildMesh() {
        return new PresetResponse(
                "Recovery · Build Mesh",
                "Sixteen-task compile mesh: cache, retry, substitute, skip, retry-then-cache, exhaustion cascade, and a leaf failure each fire once.",
                PolicyKind.CRITICAL_PATH.name(),
                6,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("bm-workspace", 8, 8),
                        ioTask("bm-checkout", 12, 7),
                        failingTask("bm-parse", 22, 9, 8, "bm-workspace", "bm-checkout"),
                        task("bm-compile", 26, 9, "bm-parse"),
                        failingTask("bm-unit-tests", 24, 8, 10, "bm-compile"),
                        failingTask("bm-optimize", 20, 6, 9, "bm-compile"),
                        failingTask("bm-docs-lint", 14, 3, 6, "bm-checkout"),
                        task("bm-link", 16, 8, "bm-unit-tests", "bm-optimize", "bm-docs-lint"),
                        failingTask("bm-integration", 28, 9, 12, "bm-link"),
                        task("bm-package", 15, 7, "bm-integration"),
                        failingTask("bm-sign-artifact", 18, 8, 7, "bm-package"),
                        ioTask("bm-deploy", 14, 7, "bm-sign-artifact"),
                        task("bm-release-notes", 10, 2, "bm-package"),
                        failingTask("bm-telemetry-ping", 11, 1, 5, "bm-workspace"),
                        task("bm-audit", 9, 3, "bm-deploy", "bm-release-notes"),
                        ioTask("bm-notify", 7, 2, "bm-audit")),
                "RECOVER",
                "RULE_BASED");
    }

    /**
     * Multi-service fan-out/join covering the same recovery outcomes with different topology and timing.
     */
    private static PresetResponse recoveryServiceGraph() {
        return new PresetResponse(
                "Recovery · Service Graph",
                "Eighteen-task multi-service graph: auth, billing, and search branches hit every recovery path including exhaustion and a leaf failure.",
                PolicyKind.MLFQ.name(),
                6,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("sg-bootstrap", 9, 8),
                        ioTask("sg-registry", 13, 7, "sg-bootstrap"),
                        failingTask("sg-auth-compile", 21, 9, 8, "sg-registry"),
                        task("sg-auth-package", 14, 8, "sg-auth-compile"),
                        task("sg-billing-compile", 19, 7, "sg-registry"),
                        failingTask("sg-billing-tests", 25, 8, 11, "sg-billing-compile"),
                        task("sg-billing-package", 15, 7, "sg-billing-tests"),
                        failingTask("sg-search-index", 23, 6, 10, "sg-registry"),
                        task("sg-search-package", 16, 6, "sg-search-index"),
                        failingTask("sg-docs-publish", 12, 2, 5, "sg-bootstrap"),
                        task("sg-mesh-assemble", 18, 9,
                                "sg-auth-package", "sg-billing-package", "sg-search-package", "sg-docs-publish"),
                        failingTask("sg-contract-suite", 30, 9, 13, "sg-mesh-assemble"),
                        task("sg-image-build", 20, 8, "sg-contract-suite"),
                        failingTask("sg-canary-gate", 17, 9, 7, "sg-image-build"),
                        ioTask("sg-rollout", 15, 8, "sg-canary-gate"),
                        failingTask("sg-metrics-ping", 10, 1, 4, "sg-bootstrap"),
                        task("sg-postmortem-draft", 11, 3, "sg-image-build"),
                        ioTask("sg-status-page", 8, 2, "sg-rollout", "sg-postmortem-draft")),
                "RECOVER",
                "RULE_BASED");
    }

    /**
     * Staging-to-production gate with blocking I/O, covering every recovery outcome once.
     */
    private static PresetResponse recoveryReleaseGate() {
        return new PresetResponse(
                "Recovery · Release Gate",
                "Fifteen-task release gate with blocking I/O: image scan through prod health exercise every recovery outcome plus a leaf failure.",
                PolicyKind.ROUND_ROBIN.name(),
                5,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("rg-candidate", 10, 8),
                        ioTask("rg-image-build", 18, 8, "rg-candidate"),
                        failingTask("rg-image-scan", 20, 9, 8, "rg-image-build"),
                        ioTask("rg-deploy-staging", 16, 8, "rg-image-scan"),
                        failingTask("rg-smoke-tests", 22, 8, 9, "rg-deploy-staging"),
                        failingTask("rg-load-probe", 24, 6, 10, "rg-deploy-staging"),
                        failingTask("rg-changelog", 11, 2, 5, "rg-candidate"),
                        task("rg-staging-gate", 14, 9, "rg-smoke-tests", "rg-load-probe", "rg-changelog"),
                        failingTask("rg-staging-verify", 26, 9, 12, "rg-staging-gate"),
                        ioTask("rg-promote", 13, 8, "rg-staging-verify"),
                        failingTask("rg-prod-health", 19, 9, 7, "rg-promote"),
                        ioTask("rg-traffic-shift", 15, 8, "rg-prod-health"),
                        failingTask("rg-heartbeat", 9, 1, 4, "rg-candidate"),
                        task("rg-rollback-plan", 12, 4, "rg-staging-verify"),
                        ioTask("rg-announce", 8, 2, "rg-traffic-shift", "rg-rollback-plan")),
                "RECOVER",
                "RULE_BASED");
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
