package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.SchedulerPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class ThreadPoolManager implements AutoCloseable {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();
    private final Condition stateChanged = lock.newCondition();
    private final SchedulerPolicy scheduler;
    private final DependencyGraph dependencyGraph = new DependencyGraph();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Set<WorkerThread> workers = new LinkedHashSet<>();
    private final int minWorkerCount;
    private final int maxWorkerCount;
    private final Duration idleShrinkDelay;
    private int nextWorkerId;
    private boolean acceptingTasks = true;
    private boolean shutdownRequested;

    public ThreadPoolManager(int workerCount, SchedulerPolicy scheduler) {
        this(workerCount, workerCount, workerCount, scheduler, Duration.ofSeconds(2));
    }

    public ThreadPoolManager(
            int initialWorkerCount,
            int minWorkerCount,
            int maxWorkerCount,
            SchedulerPolicy scheduler) {
        this(initialWorkerCount, minWorkerCount, maxWorkerCount, scheduler, Duration.ofSeconds(2));
    }

    public ThreadPoolManager(
            int initialWorkerCount,
            int minWorkerCount,
            int maxWorkerCount,
            SchedulerPolicy scheduler,
            Duration idleShrinkDelay) {
        if (minWorkerCount < 1 || initialWorkerCount < minWorkerCount || maxWorkerCount < initialWorkerCount) {
            throw new IllegalArgumentException("Worker counts must satisfy 1 <= min <= initial <= max");
        }
        Objects.requireNonNull(idleShrinkDelay, "idleShrinkDelay");
        if (idleShrinkDelay.isNegative() || idleShrinkDelay.isZero()) {
            throw new IllegalArgumentException("Idle shrink delay must be positive");
        }
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.minWorkerCount = minWorkerCount;
        this.maxWorkerCount = maxWorkerCount;
        this.idleShrinkDelay = idleShrinkDelay;

        lock.lock();
        try {
            for (int i = 0; i < initialWorkerCount; i++) {
                startWorkerLocked();
            }
        } finally {
            lock.unlock();
        }
    }

    public Task submit(TaskSpec spec) {
        return submitBatch(List.of(spec)).getFirst();
    }

    public List<Task> submitBatch(List<TaskSpec> batch) {
        Objects.requireNonNull(batch, "batch");
        List<TaskSpec> specs = List.copyOf(batch);
        if (specs.isEmpty()) {
            return List.of();
        }

        lock.lock();
        try {
            if (!acceptingTasks) {
                throw new IllegalStateException("Pool is shutting down; it no longer accepts tasks");
            }

            Set<String> readyIds = dependencyGraph.registerBatch(specs);
            long now = System.nanoTime();
            List<Task> submitted = new ArrayList<>(specs.size());
            for (TaskSpec spec : specs) {
                Task task = new Task(spec);
                task.markSubmitted(now);
                tasks.put(task.id(), task);
                submitted.add(task);
            }
            for (String id : readyIds) {
                makeReadyLocked(tasks.get(id), now);
            }
            scaleUpForQueueLocked();
            workAvailable.signalAll();
            return List.copyOf(submitted);
        } finally {
            lock.unlock();
        }
    }

    public boolean awaitCompletion(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        long remaining = timeout.toNanos();
        lock.lockInterruptibly();
        try {
            while (!allTasksTerminalLocked()) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = stateChanged.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            acceptingTasks = false;
            shutdownRequested = true;
            workAvailable.signalAll();
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        long remaining = timeout.toNanos();
        lock.lockInterruptibly();
        try {
            while (!workers.isEmpty()) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = stateChanged.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public TaskMetrics taskMetrics(String taskId) {
        lock.lock();
        try {
            Task task = tasks.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("Unknown task: " + taskId);
            }
            return task.metrics();
        } finally {
            lock.unlock();
        }
    }

    public List<TaskMetrics> taskMetrics() {
        lock.lock();
        try {
            return tasks.values().stream().map(Task::metrics).toList();
        } finally {
            lock.unlock();
        }
    }

    public int workerCount() {
        lock.lock();
        try {
            return workers.size();
        } finally {
            lock.unlock();
        }
    }

    long quantumFor(Task task) {
        lock.lock();
        try {
            return scheduler.timeQuantumMillis(task);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        shutdown();
        try {
            if (!awaitTermination(Duration.ofSeconds(30))) {
                throw new IllegalStateException("Workers did not terminate within 30 seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for pool termination", exception);
        }
    }

    Task awaitNextTask(WorkerThread worker) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long idleSince = System.nanoTime();
            while (true) {
                Task task = scheduler.poll(System.nanoTime());
                if (task != null) {
                    task.transitionTo(TaskState.RUNNING, System.nanoTime(), null);
                    return task;
                }
                if (shutdownRequested && allTasksTerminalLocked()) {
                    return null;
                }

                long now = System.nanoTime();
                if (!shutdownRequested
                        && workers.size() > minWorkerCount
                        && now - idleSince >= idleShrinkDelay.toNanos()) {
                    return null;
                }

                long waitNanos = TimeUnit.MILLISECONDS.toNanos(250);
                if (!shutdownRequested && workers.size() > minWorkerCount) {
                    waitNanos = Math.min(waitNanos, Math.max(1, idleShrinkDelay.toNanos() - (now - idleSince)));
                }
                workAvailable.awaitNanos(waitNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    void finishSlice(Task task, SliceResult result, Throwable failure) {
        lock.lock();
        try {
            long now = System.nanoTime();
            task.recordSlice();
            if (failure != null) {
                task.transitionTo(TaskState.FAILED, now, failure.getClass().getSimpleName() + ": " + failure.getMessage());
                scheduler.onTaskFinished(task);
                failDescendantsLocked(task.id(), now, task.failureMessageForPool());
            } else if (result.isComplete()) {
                task.transitionTo(TaskState.DONE, now, null);
                scheduler.onTaskFinished(task);
                for (String id : dependencyGraph.markCompleted(task.id())) {
                    makeReadyLocked(tasks.get(id), now);
                }
            } else {
                task.transitionTo(TaskState.READY, now, null);
                scheduler.requeueAfterYield(task, now);
            }
            scaleUpForQueueLocked();
            workAvailable.signalAll();
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void workerStopped(WorkerThread worker) {
        lock.lock();
        try {
            workers.remove(worker);
            stateChanged.signalAll();
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void makeReadyLocked(Task task, long nowNanos) {
        if (task == null || task.state() != TaskState.PENDING) {
            throw new IllegalStateException("Dependency graph produced an invalid ready task");
        }
        task.transitionTo(TaskState.READY, nowNanos, null);
        scheduler.offer(task, nowNanos);
    }

    private void failDescendantsLocked(String failedTaskId, long nowNanos, String cause) {
        for (String descendantId : dependencyGraph.descendantsOf(failedTaskId)) {
            Task descendant = tasks.get(descendantId);
            if (descendant != null && !descendant.state().isTerminal()) {
                scheduler.onTaskFinished(descendant);
                descendant.transitionTo(
                        TaskState.FAILED,
                        nowNanos,
                        "Dependency " + failedTaskId + " failed: " + cause);
            }
        }
    }

    private boolean allTasksTerminalLocked() {
        return tasks.values().stream().allMatch(task -> task.state().isTerminal());
    }

    private void scaleUpForQueueLocked() {
        while (workers.size() < maxWorkerCount && scheduler.readyCount() > workers.size() * 2) {
            startWorkerLocked();
        }
    }

    private void startWorkerLocked() {
        WorkerThread worker = new WorkerThread(this, "thread-pool-worker-" + (++nextWorkerId));
        workers.add(worker);
        worker.start();
    }
}
