# Dependency-Aware Thread Pool

A single-JVM operating-systems project that implements a worker pool directly
with `Thread`, `ReentrantLock`, and `Condition`. Tasks can name prerequisite
tasks, and only become schedulable after those prerequisites finish. Five
pluggable policies schedule the ready queue: FCFS, priority with aging, Round
Robin, a three-level multilevel feedback queue (MLFQ), and critical-path
priority.

## Requirements

- JDK 27
- Apache Maven 3.9+

The Maven compiler is configured with `--release 27`.

## Build, test, and run

From this directory:

```bash
mvn test
mvn exec:java
mvn exec:java -Dexec.args="--scale"
mvn exec:java -Dexec.args="--hybrid"
mvn exec:java -Dexec.args="--pinning"
```

The demo runs the same nine-task build pipeline through all five policies,
prints total and per-lane metrics, then submits a deliberately cyclic batch and
prints the rejected cycle path. Log lines include worker thread names so that
task interleaving is visible.

`--scale [runs]` runs a reproducible randomized-DAG scaling study for 50, 100,
200, 300, and 500 tasks under each policy; the optional run count defaults to
10. `--hybrid [runs]` compares platform-only, virtual-only, and hybrid
execution over independent, layered, and cross-lane workloads. It defaults to
10 repetitions per configuration. `--pinning` compares virtual threads
blocked inside `synchronized` and `ReentrantLock` regions and reports the JDK
version using a configured four-carrier virtual-thread scheduler; JDK 24 and
later substantially reduce monitor-related carrier pinning.

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
- **Critical path:** among currently ready tasks, dispatches the task with the
  greatest estimated remaining path length (its burst estimate plus the
  longest dependent path). FCFS arrival order breaks ties. This is most useful
  for workloads with a few long dependency chains.

Dependencies sit above the scheduler: policies only receive tasks that are
already ready.

### Hybrid platform/virtual execution

Set the final `blocking` component of a `TaskSpec` to `true` to route that task
to a named virtual thread. Existing platform workers continue to run
non-blocking tasks and cooperatively yielded slices. Virtual-lane work runs
through its slices to completion and shares the dependency, completion, and
failure-propagation path. Virtual concurrency defaults to 100 and can be
configured with the `ThreadPoolManager` constructor that accepts a virtual
task limit.

The opt-in `LaneClassifier` setting samples platform task slices by work
implementation type. After five samples, a mean CPU/wall ratio below 0.20
routes later tasks of that type to virtual threads. Automatic classification
is disabled by default. Scheduler quanta and cooperative requeue behavior
apply to the platform lane; virtual tasks run to completion. The semaphore
limits concurrently executing virtual task bodies, while virtual threads
waiting for a permit are still created. A virtual-only benchmark still uses
the pool's platform worker as a dispatcher.

### Randomized DAG and per-lane metrics

`RandomDagFactory` creates reproducible acyclic graphs by permitting edges only
from earlier to later task IDs. `ScalingStudy` reports mean makespan, standard
deviation, throughput, waiting time, and turnaround across repeated runs.
`ScalingReport.writeCsv(Path)` exports rows for external graphing. The regular
benchmark report includes platform and virtual task counts and per-lane average
wait and turnaround values.

## Benchmark metrics

- **Throughput:** completed tasks divided by elapsed wall-clock seconds.
- **Waiting time:** first dispatch time minus submission time.
- **Turnaround time:** completion time minus submission time.

Every policy gets fresh task instances from the same workload factory, avoiding
state reuse between runs. Results are measurements of the included small
synthetic workload; timings vary with machine load and are not general claims
that one policy is universally faster.

The nine-task demo is too small to produce a reliable policy ranking; use the
live output from `mvn exec:java` rather than treating sample timings as
general performance claims.

## Project map

```text
src/main/java/edu/oslab/threadpool/
  benchmark/       comparison harness, scaling study, and mixed workload
  classifier/      optional sampled lane classifier
  experiment/      carrier blocking experiment
  scheduler/       FCFS, priority, Round Robin, MLFQ, and critical path
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
distributed coordination. The hand-built pool owns platform worker threads;
virtual threads are started directly for tasks explicitly or automatically
classified as blocking.
