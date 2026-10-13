# Dependency-Aware Thread Pool: Current Status and Virtual-Thread Hybrid Dispatcher Plan

Package: `edu.oslab.threadpool` · Single JVM, in-memory · Operating Systems lab project

This file has two parts: where the project stands today (Part 1) and a plan for the next feature, a hybrid dispatcher that runs blocking tasks on Java virtual threads (Part 2). Part 2 is written so a human or an AI coding agent can pick it up directly.

---

## Part 1: Current Status

### Validation

- Main and test code compile cleanly.
- 10 of 10 unit tests pass.
- `edu.oslab.threadpool.Main` runs end to end.
- Tests were run with `javac` plus the JUnit launcher. `mvn test` has not been verified (Maven was not installed on the machine used), so surefire and `exec:java` behavior is unconfirmed.

### What is built (about 1,280 lines of Java; no `ExecutorService`, no third-party graph library)

| Area                | What exists                                                                                                                                                                                                                                                                                                                                                                                                     |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Dependency gating   | `DependencyGraph` keeps dependency and dependents adjacency plus an unresolved-count per task. A batch is validated atomically: duplicate IDs, unknown dependencies, then a white/gray/black DFS cycle check that throws `DeadlockDetectedException` with the cycle path. Workers never block on prerequisites; finishing a task decrements only its direct dependents, and tasks that reach zero become ready. |
| Failure propagation | A task that throws is marked FAILED and all transitive dependents are failed too (`ThreadPoolManager.failDescendantsLocked`), so the graph cannot hang.                                                                                                                                                                                                                                                         |
| Worker pool         | `WorkerThread extends Thread`, one shared `ReentrantLock` with `Condition` (no busy-waiting), graceful drain on shutdown, automatic sizing (grow when the ready queue exceeds 2x the workers, shrink to the minimum after an idle delay).                                                                                                                                                                       |
| Cooperative slices  | Work runs through `CooperativeWork.runSlice(quantum)`, which returns `yielded()` or `done()`. The quantum is a suggestion; a task that never yields can pin its worker. Documented in the README.                                                                                                                                                                                                               |
| Scheduling policies | Behind `SchedulerPolicy` (`offer` / `poll` / `requeueAfterYield` / `onTaskFinished`): FCFS, Priority with aging, Round Robin, and 3-level MLFQ with per-level quanta and periodic boost. All unit-tested.                                                                                                                                                                                                       |
| Benchmark and demos | Harness reports throughput, first-dispatch wait, and turnaround, using fresh task instances per policy run. Includes a 9-task build-pipeline demo and a deliberate cycle-rejection demo.                                                                                                                                                                                                                        |

### First benchmark run (9 tasks, single run, illustrative only)

| Policy           | Elapsed (s) | Tasks/sec | Avg wait (ms) | Avg turnaround (ms) |
| ---------------- | ----------- | --------- | ------------- | ------------------- |
| FCFS             | 0.0639      | 140.83    | 21.277        | 31.035              |
| Priority (aging) | 0.0724      | 124.30    | 32.833        | 42.694              |
| Round Robin      | 0.0583      | 154.31    | 20.145        | 29.822              |
| MLFQ (3 levels)  | 0.0598      | 150.59    | 21.026        | 30.868              |

Cycle demo output: `Dependency cycle detected: cycle-a -> cycle-b -> cycle-c -> cycle-a`

These numbers show that the harness works. They do not rank the policies: nine tasks of roughly 60 ms are too few and too noisy, and the gaps between FCFS, Round Robin and MLFQ are within run-to-run variation.

### Known limitations

- Time slicing is cooperative, not preemptive (Java cannot safely preempt platform threads).
- Single JVM, in-memory only.
- Benchmark workload is small and synthetic.
- The repository root contains unrelated Replit/TypeScript scaffolding that should be removed.

### Honest novelty assessment

The components are standard: DAG-gated execution, white/gray/black cycle detection, and textbook schedulers all have prior art (for example `CompletableFuture`, `ForkJoinPool`, and eBay's BascomTask). The contribution is the combination: dependency gating over a swappable policy interface, OS-style time slices through the policies, atomic batch validation with cycle-path reporting, and cascading failure. Describe it as integration-level novelty in reviews.

### Roadmap before this feature

1. Randomized-DAG scaling study (50 to 500 tasks, repeated runs, mean and standard deviation).
2. Critical-path-aware scheduling policy.
3. Virtual-thread hybrid dispatcher (this document, Part 2).
4. Housekeeping: verify `mvn test`, remove unrelated scaffolding, tighten the README.

---

## Part 2: Feature Plan, Hybrid Dispatcher with Virtual Threads

### Goal

Route blocking tasks (waiting on I/O) to Java virtual threads, and keep CPU-bound tasks on the existing hand-built platform-thread workers, so that blocked workers can no longer starve CPU work. The scheduling policies, dependency graph, and cycle detection stay unchanged.

### Why

In a pure platform-thread pool, a worker blocked in an I/O wait is idle but still holds an OS thread. When every worker is blocked, CPU-bound tasks queue behind them even though the CPU is free. A blocked virtual thread releases its OS carrier thread, so blocking is nearly free. This is also a direct real-world example of the many-to-many (M:N) threading model and user-level versus kernel-level threads from OS coursework.

### Requirements

- JDK 25  (JEP 444). Update the `pom.xml`- No new third-party dependencies.
- Do not use `ExecutorService` or `Executors` for the core pool (existing project rule). For the virtual lane, start threads directly with `Thread.ofVirtual()` to stay consistent with that rule.

### Design

**Two execution lanes behind the same scheduler.**

1. The active `SchedulerPolicy` still picks the next ready task. Nothing about policy logic changes.
2. A new dispatcher step decides the lane for the picked task:
   - **Platform lane:** the existing `WorkerThread` pool, including cooperative slices and requeue-after-yield.
   - **Virtual lane:** one new virtual thread per task, started directly.
3. Completion on either lane calls the same completion path, so dependents are decremented and newly ready tasks are offered to the scheduler exactly as today.

**Lane selection (build in this order).**

1. **Manual flag first.** Add a `blocking` attribute to the task (default false). Tasks flagged blocking go to the virtual lane. This alone delivers the feature.
2. **Automatic classification second.** For each task type, measure the first few runs on the platform lane: compare the thread's CPU time with wall-clock time (via `ThreadMXBean`). If CPU time is a small fraction of wall time (suggested starting threshold: under 20 percent), tag the type as blocking and route later tasks of that type to the virtual lane. Keep the threshold and the number of sample runs configurable. Note that per-thread CPU time is not supported for virtual threads, so classification must be measured on the platform lane only.

**Rules for the virtual lane.**

- Virtual-lane tasks run to completion in one piece. Cooperative slicing and requeue-after-yield apply only to the platform lane. Document this clearly, since MLFQ demotion and Round Robin rotation therefore affect only the platform lane.
- **Concurrency cap:** a `Semaphore` limits how many virtual-lane tasks run at once (default 100, configurable). A task acquires a permit before starting its blocking work and releases it in a `finally` block.
- **Failure propagation:** an exception in a virtual-lane task must mark it FAILED and fail all transitive dependents, using the same code path as the platform lane.
- **Shutdown:** graceful shutdown must wait for all in-flight virtual threads (track a count of active virtual-lane tasks under the existing lock, and signal the existing condition when it reaches zero). Shutdown must not return while virtual tasks are still running.
- **Pool sizing:** the auto grow/shrink logic should consider only platform-lane queue depth, since virtual-lane tasks never wait for a platform worker.

**Metrics additions.** Record the lane used for each task. Report per-lane task counts and, for each lane, average wait and turnaround. Keep first-dispatch wait defined the same way as today.

### Implementation steps

1. Inspect the existing code first (`ThreadPoolManager`, `WorkerThread`, `SchedulerPolicy`, `DependencyGraph`, `CooperativeWork`, the benchmark harness) and keep new code consistent with its style and locking discipline. This plan was written from a status report, so exact method names and locking details may differ.
2. Bump the compiler release to 21 and confirm the existing 10 tests still pass.
3. Add the `blocking` flag and a lane enum to the task model, with no behavior change yet.
4. Add the dispatcher step and the virtual lane with the semaphore cap, using the manual flag only.
5. Wire completion and failure propagation from the virtual lane into the existing paths.
6. Update shutdown to wait for in-flight virtual tasks.
7. Add per-lane metrics.
8. Add the mixed-workload benchmark (below).
9. Add automatic classification behind a configuration switch (off by default).
10. Add the pinning experiment (optional, below).
11. Update the README: how to enable the hybrid mode, the lane rules, and the limitations.

### Tests to add

- A blocking task routed to the virtual lane completes, and its dependents run afterward.
- A CPU task depending on a blocking task, and the reverse, both resolve in the correct order.
- A failing virtual-lane task fails all transitive dependents and the run still terminates.
- The semaphore cap is respected: with a cap of N, no more than N virtual tasks run concurrently (verify with an atomic counter).
- Shutdown waits for in-flight virtual tasks.
- With the feature off (no blocking flags, classification off), behavior and benchmark output match the current system.
- Cycle detection still rejects cyclic batches that mix both lane types.
- Classification: a sleeping task type is tagged blocking, a busy-loop task type is not.

### Benchmark: mixed workload

Run the same mixed workload (for example 8 CPU-bound tasks plus 200 simulated I/O tasks that call `Thread.sleep`) in three configurations, with a few DAG shapes (independent tasks, a layered pipeline, and some CPU tasks depending on I/O tasks):

| Configuration                              | Expected behavior (prediction, to be measured)                         |
| ------------------------------------------ | ---------------------------------------------------------------------- |
| Platform pool only (for example 4 workers) | CPU tasks wait behind blocked workers; slow overall                    |
| Virtual threads only                       | I/O finishes fast, but scheduling policies have no effect on ordering  |
| Hybrid                                     | Both kinds finish quickly, and the policies still control the CPU lane |

Repeat each configuration at least 10 times and report mean and standard deviation of makespan, plus per-lane average wait and turnaround. Do not claim a winner unless the difference is larger than the run-to-run spread.

### Optional experiment: carrier pinning

Add a small standalone experiment comparing a virtual thread that blocks while holding a `synchronized` monitor against one that blocks while holding a `ReentrantLock`. On JDK 21, the `synchronized` case pins the carrier thread and the `ReentrantLock` case does not. JDK 24 and later reduced pinning for `synchronized` (JEP 491), so note the JDK version used when reporting results.

### Acceptance criteria

- All previous tests plus the new tests pass.
- With the feature disabled, the existing demos and benchmark behave as before.
- The mixed-workload benchmark runs for all three configurations and prints the per-lane metrics.
- No use of `ExecutorService` or `Executors` anywhere in the pool code.
- The README documents the feature, its settings, and its limitations.

### Risks and open questions

- **Classification accuracy:** short or noisy samples can misclassify a task type. Keep it opt-in and configurable, and report the classification outcome in the benchmark output.
- **Policy semantics on the virtual lane:** because virtual tasks are not sliced, Round Robin and MLFQ behavior applies only to the platform lane. State this plainly rather than implying the policies govern both lanes.
- **Measurement noise:** timing small workloads is unreliable. Use enough tasks and repetitions before drawing conclusions.
- **Version bump:** moving to JDK 21 affects the build environment (including any Maven setup). Confirm the toolchain before starting.

### Suggested prompt for an AI coding agent

> Read this file, then inspect the existing `edu.oslab.threadpool` code before changing anything. Implement the hybrid dispatcher in Part 2 step by step in the listed order, keeping all existing tests passing after each step. Do not use `ExecutorService` or `Executors`; start virtual threads with `Thread.ofVirtual()` directly. Keep the feature opt-in, put automatic classification behind a configuration switch that is off by default, and stop to ask if any step conflicts with the existing locking design. When finished, run the full test suite and the mixed-workload benchmark, and report the measured results with their spread rather than a single run.
