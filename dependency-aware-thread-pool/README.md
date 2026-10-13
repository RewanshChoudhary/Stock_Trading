# Dependency-Aware Thread Pool

A single-JVM operating-systems project that implements a worker pool directly
with `Thread`, `ReentrantLock`, and `Condition`. Tasks can name prerequisite
tasks, and only become schedulable after those prerequisites finish. Four
pluggable policies schedule the ready queue: FCFS, priority with aging, Round
Robin, and a three-level multilevel feedback queue (MLFQ).

## Requirements

- JDK 25
- Apache Maven 3.9+

The Maven compiler is configured with `--release 25`.

## Build, test, and run

From this directory:

```bash
mvn test
mvn exec:java
```

The demo runs the same nine-task build pipeline through all four policies,
prints throughput, average waiting time, and average turnaround time, then
submits a deliberately cyclic batch and prints the rejected cycle path. Log
lines include worker thread names so that task interleaving is visible.

## Design

### Dependency graph

`DependencyGraph` stores both dependency and dependent adjacency lists. A whole
submitted batch is validated before any task is registered: duplicate IDs and
unknown dependencies are rejected, then `CycleDetector` uses white/gray/black
DFS marking to find a cycle. `DeadlockDetectedException` includes the cycle
path. When a task completes, only its direct dependents' unresolved counts are
updated; those reaching zero enter the scheduler. Workers never block waiting
for prerequisites.

Tasks may be submitted in later batches and may depend on already-registered
tasks. Unknown IDs are rejected rather than implicitly treated as future
tasks. A rejected batch is atomic and does not partially change the pool.

### Worker pool and lifecycle

Workers extend `Thread` and wait on a shared `Condition` guarded by a
`ReentrantLock`; there is no busy waiting. Shutdown stops accepting new work
and drains accepted work before workers exit. The configurable constructor
supports automatic growth when the ready queue exceeds twice the live worker
count, up to a maximum, and idle workers shrink back to a configured minimum.
The simpler constructor creates a fixed-size pool.

An exception from task work marks that task `FAILED` and marks its dependent
descendants failed as well, instead of leaving the graph permanently waiting.

### Cooperative scheduling and Java's thread model

Java cannot safely preempt arbitrary code running on a platform thread. The
pool therefore uses **cooperative slices**: `CooperativeWork.runSlice()` must
perform a bounded amount of work and return `SliceResult.yielded()` when it
needs another turn, or `SliceResult.done()` when complete. The scheduler's
quantum is a suggested budget, not a forced timeout. A task that ignores that
contract can occupy its worker until it returns.

- **FCFS:** selects ready tasks in arrival order; a yielded task continues
  ahead of tasks that arrived later.
- **Priority with aging:** selects the highest effective priority; each aging
  interval spent waiting adds one to a task's base priority.
- **Round Robin:** places a yielded task at the back of the ready queue.
- **MLFQ:** has three queues, demotes tasks after each yielded slice, uses a
  different suggested budget per level, and periodically boosts priorities to
  prevent starvation.

Dependencies sit above the scheduler: policies only receive tasks that are
already ready.

## Benchmark metrics

- **Throughput:** completed tasks divided by elapsed wall-clock seconds.
- **Waiting time:** first dispatch time minus submission time.
- **Turnaround time:** completion time minus submission time.

Every policy gets fresh task instances from the same workload factory, avoiding
state reuse between runs. Results are measurements of the included small
synthetic workload; timings vary with machine load and are not general claims
that one policy is universally faster.

One run of the nine-task demo measured approximately 136.9 tasks/sec for FCFS,
125.8 for priority with aging, 142.0 for Round Robin, and 154.7 for MLFQ.
Because this is a tiny synthetic workload, use the live output from
`mvn exec:java` for current measurements rather than treating these sample
figures as a reliable ranking.

## Project map

```text
src/main/java/edu/oslab/threadpool/
  benchmark/       comparison harness and report formatter
  scheduler/       FCFS, priority, Round Robin, and MLFQ policies
  DependencyGraph.java
  CycleDetector.java
  ThreadPoolManager.java
  WorkerThread.java
  Main.java
src/test/java/     concurrency, dependency, cycle, and policy tests
```

## Scope

The implementation is in-memory and single-JVM. It does not use
`ExecutorService`, `Executors`, a third-party graph library, persistence, or
distributed coordination. The thread pool manages platform threads; virtual
threads are intentionally not substituted for the hand-built core.
