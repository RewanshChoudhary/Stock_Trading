package edu.oslab.threadpool;

import edu.oslab.threadpool.classifier.LaneClassifier;
import edu.oslab.threadpool.scheduler.SchedulerPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class ThreadPoolManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ThreadPoolManager.class);
    private static final String CANCELLED_MESSAGE = "Run cancelled";
    private static final String INJECTED_FAILURE_MESSAGE = "Failure injected externally";

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();
    private final Condition stateChanged = lock.newCondition();
    private final SchedulerPolicy scheduler;
    private final DependencyGraph dependencyGraph = new DependencyGraph();
    private final Dispatcher dispatcher;
    private final LaneClassifier laneClassifier;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Set<WorkerThread> workers = new LinkedHashSet<>();
    private final Map<String, RecoverySession> recoverySessions = new LinkedHashMap<>();
    private final Map<Task, RecoveryAttempt> recoveryAttempts = new IdentityHashMap<>();
    private final Set<Thread> recoveryThreads = new LinkedHashSet<>();
    private final RecoveryMode recoveryMode;
    private final RecoveryAdvisor recoveryAdvisor;
    private final RecoveryOptionProvider recoveryOptionProvider;
    private final RuleBasedRecoveryAdvisor fallbackAdvisor = new RuleBasedRecoveryAdvisor();
    private final int minWorkerCount;
    private final int maxWorkerCount;
    private final Duration idleShrinkDelay;
    private volatile PoolEventListener listener = PoolEventListener.NO_OP;
    private int nextWorkerId;
    private boolean acceptingTasks = true;
    private boolean shutdownRequested;
    private boolean runStarted;
    private boolean runFinishedNotified;
    private boolean runAborted;
    private long recoverySequence;

    public ThreadPoolManager(int workerCount, SchedulerPolicy scheduler) {
        this(workerCount, workerCount, workerCount, scheduler, Duration.ofSeconds(2));
    }

    public ThreadPoolManager(
            int workerCount,
            SchedulerPolicy scheduler,
            RecoveryMode recoveryMode,
            RecoveryAdvisor recoveryAdvisor,
            RecoveryOptionProvider recoveryOptionProvider) {
        this(workerCount, workerCount, workerCount, scheduler, Duration.ofSeconds(2), 100, false,
                recoveryMode, recoveryAdvisor, recoveryOptionProvider);
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
        this(initialWorkerCount, minWorkerCount, maxWorkerCount, scheduler, idleShrinkDelay, 100);
    }

    public ThreadPoolManager(
            int initialWorkerCount,
            int minWorkerCount,
            int maxWorkerCount,
            SchedulerPolicy scheduler,
            Duration idleShrinkDelay,
            int maxVirtualTasks) {
        this(initialWorkerCount, minWorkerCount, maxWorkerCount, scheduler, idleShrinkDelay, maxVirtualTasks, false);
    }

    public ThreadPoolManager(
            int initialWorkerCount,
            int minWorkerCount,
            int maxWorkerCount,
            SchedulerPolicy scheduler,
            Duration idleShrinkDelay,
            int maxVirtualTasks,
            boolean classificationEnabled) {
        this(initialWorkerCount, minWorkerCount, maxWorkerCount, scheduler, idleShrinkDelay,
                maxVirtualTasks, classificationEnabled, RecoveryMode.STRICT,
                new RuleBasedRecoveryAdvisor(), (task, reason) -> List.of());
    }

    public ThreadPoolManager(
            int initialWorkerCount,
            int minWorkerCount,
            int maxWorkerCount,
            SchedulerPolicy scheduler,
            Duration idleShrinkDelay,
            int maxVirtualTasks,
            boolean classificationEnabled,
            RecoveryMode recoveryMode,
            RecoveryAdvisor recoveryAdvisor,
            RecoveryOptionProvider recoveryOptionProvider) {
        if (minWorkerCount < 1 || initialWorkerCount < minWorkerCount || maxWorkerCount < initialWorkerCount) {
            throw new IllegalArgumentException("Worker counts must satisfy 1 <= min <= initial <= max");
        }
        Objects.requireNonNull(idleShrinkDelay, "idleShrinkDelay");
        if (idleShrinkDelay.isNegative() || idleShrinkDelay.isZero()) {
            throw new IllegalArgumentException("Idle shrink delay must be positive");
        }
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.dispatcher = new Dispatcher(this, maxVirtualTasks);
        this.laneClassifier = new LaneClassifier(classificationEnabled);
        this.minWorkerCount = minWorkerCount;
        this.maxWorkerCount = maxWorkerCount;
        this.idleShrinkDelay = idleShrinkDelay;
        this.recoveryMode = Objects.requireNonNull(recoveryMode, "recoveryMode");
        this.recoveryAdvisor = Objects.requireNonNull(recoveryAdvisor, "recoveryAdvisor");
        this.recoveryOptionProvider = Objects.requireNonNull(recoveryOptionProvider, "recoveryOptionProvider");

        lock.lock();
        try {
            for (int i = 0; i < initialWorkerCount; i++) {
                startWorkerLocked();
            }
            runStarted = true;
            fireEvent(current -> current.onRunStarted(workers.size(), scheduler.name()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replaces the observer notified about pool activity. Listener callbacks run inside the pool
     * and must return quickly; exceptions thrown by a listener are logged and ignored.
     */
    public void setListener(PoolEventListener listener) {
        lock.lock();
        try {
            this.listener = listener == null ? PoolEventListener.NO_OP : listener;
            if (runStarted) {
                // The listener arrived after the pool started, so let it catch up on run start.
                fireEvent(current -> current.onRunStarted(workers.size(), scheduler.name()));
            }
        } finally {
            lock.unlock();
        }
    }

    public PoolEventListener listener() {
        return listener;
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
                fireEvent(current -> current.onTaskSubmitted(
                        spec.id(), spec.priority(), spec.estimatedBurstMillis(), spec.dependencies()));
            }
            Map<String, Long> burstEstimates = new LinkedHashMap<>();
            tasks.forEach((id, task) -> burstEstimates.put(id, task.spec().estimatedBurstMillis()));
            dependencyGraph.computeCriticalPathWeights(burstEstimates)
                    .forEach((id, weight) -> tasks.get(id).setCriticalPathWeight(weight));
            scheduler.refreshPriorities();
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

    /**
     * Cancels the run and stops accepting or dispatching work. Every unfinished task is made
     * terminal immediately so callers can observe a complete cancellation snapshot. A task body
     * that is already executing is still allowed to return from its cooperative slice; its result
     * is discarded when the pool observes the terminal state.
     */
    public void cancel() {
        lock.lock();
        try {
            if (runAborted) {
                return;
            }
            runAborted = true;
            acceptingTasks = false;
            shutdownRequested = true;
            long now = System.nanoTime();
            List<Task> cancelled = new ArrayList<>();
            for (Task task : tasks.values()) {
                if (!task.state().isTerminal()) {
                    scheduler.onTaskFinished(task);
                    task.transitionTo(TaskState.FAILED, now, CANCELLED_MESSAGE);
                    cancelled.add(task);
                }
            }
            for (Task task : cancelled) {
                fireEvent(current -> current.onTaskFailed(task.id(), CANCELLED_MESSAGE));
            }
            for (RecoveryAttempt attempt : recoveryAttempts.values()) {
                Task attemptTask = attempt.task();
                if (!attemptTask.state().isTerminal()) {
                    scheduler.onTaskFinished(attemptTask);
                    attemptTask.transitionTo(TaskState.FAILED, now, CANCELLED_MESSAGE);
                }
            }
            recoveryAttempts.clear();
            recoverySessions.clear();
            recoveryThreads.forEach(Thread::interrupt);
            fireEvent(PoolEventListener::onRunAborted);
            workAvailable.signalAll();
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fails a single task from outside the pool. A task that is still running is flagged and
     * fails at its next slice boundary; anything else fails immediately together with its
     * dependent descendants.
     */
    public void failTask(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        lock.lock();
        try {
            Task task = tasks.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("Unknown task: " + taskId);
            }
            if (task.state().isTerminal()) {
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                task.requestFailure();
                return;
            }
            long now = System.nanoTime();
            scheduler.onTaskFinished(task);
            handleTaskFailureLocked(task, INJECTED_FAILURE_MESSAGE, now);
            workAvailable.signalAll();
            stateChanged.signalAll();
            maybeFireRunFinishedLocked();
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
            while (!workers.isEmpty() || dispatcher.virtualTasksPending() > 0 || !recoveryThreads.isEmpty()) {
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

    /** Number of tasks currently waiting in the scheduler's ready queue. */
    public int readyTaskCount() {
        lock.lock();
        try {
            return scheduler.readyCount();
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
                if (scheduler.consumeBoostEvent()) {
                    fireEvent(PoolEventListener::onMlfqBoosted);
                }
                if (task != null) {
                    if (task.state().isTerminal()) {
                        // A cancelled or externally failed task can still sit in a ready queue whose
                        // policy does not remove finished tasks (FCFS and Round Robin); it must never
                        // be handed out again.
                        continue;
                    }
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

    boolean dispatchVirtual(Task task) {
        return laneClassifier.classify(task) == Lane.VIRTUAL && dispatcher.dispatch(task);
    }

    void observeLane(Task task, long cpuTimeNanos, long wallTimeNanos) {
        laneClassifier.observe(task, cpuTimeNanos, wallTimeNanos);
    }

    boolean laneClassificationEnabled() {
        return laneClassifier.enabled();
    }

    void finishSlice(Task task, SliceResult result, Throwable failure) {
        lock.lock();
        try {
            long now = System.nanoTime();
            task.recordSlice();
            if (task.state().isTerminal()) {
                // A task may have become terminal while its slice was in flight.
                return;
            }
            if (task.state() == TaskState.RECOVERING && !recoveryAttempts.containsKey(task)) {
                // Another failed prerequisite made an in-flight dependent obsolete. Its body may
                // still return, but it must not complete or reopen the dependency graph.
                stateChanged.signalAll();
                return;
            }
            RecoveryAttempt recoveryAttempt = recoveryAttempts.get(task);
            String injectedFailure = task.isFailureRequested() ? INJECTED_FAILURE_MESSAGE : null;
            if (injectedFailure != null || failure != null) {
                String message = injectedFailure != null
                        ? injectedFailure
                        : failure.getClass().getSimpleName() + ": " + failure.getMessage();
                task.transitionTo(TaskState.FAILED, now, message);
                scheduler.onTaskFinished(task);
                if (recoveryAttempt != null) {
                    handleRecoveryAttemptFailureLocked(recoveryAttempt, message, now);
                } else {
                    handleTaskFailureLocked(task, message, now);
                }
            } else if (result.isComplete()) {
                task.transitionTo(TaskState.DONE, now, null);
                scheduler.onTaskFinished(task);
                if (recoveryAttempt != null) {
                    handleRecoveryAttemptSuccessLocked(recoveryAttempt, now);
                } else {
                    fireEvent(current -> current.onTaskCompleted(task.id()));
                    for (String id : dependencyGraph.markCompleted(task.id())) {
                        makeReadyLocked(tasks.get(id), now);
                    }
                }
            } else {
                task.transitionTo(TaskState.READY, now, null);
                int previousLevel = scheduler.levelOf(task);
                int newLevel = scheduler.requeueAfterYield(task, now);
                if (newLevel >= 0 && newLevel != previousLevel) {
                    int fromLevel = previousLevel < 0 ? 0 : previousLevel;
                    fireEvent(current -> current.onMlfqDemoted(task.id(), fromLevel, newLevel));
                }
            }
            scaleUpForQueueLocked();
            workAvailable.signalAll();
            stateChanged.signalAll();
            maybeFireRunFinishedLocked();
        } finally {
            lock.unlock();
        }
    }

    void signalShutdownIfDone() {
        lock.lock();
        try {
            if (shutdownRequested
                    && workers.isEmpty()
                    && dispatcher.virtualTasksPending() == 0
                    && allTasksTerminalLocked()) {
                stateChanged.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    void workerStopped(WorkerThread worker) {
        lock.lock();
        try {
            boolean removed = workers.remove(worker);
            if (removed && runStarted && !shutdownRequested) {
                // Workers leaving because of shutdown are not a pool resize.
                fireEvent(current -> current.onPoolResized(workers.size()));
            }
            if (!shutdownRequested && workers.size() < minWorkerCount) {
                startWorkerLocked();
            }
            stateChanged.signalAll();
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Publishes a slice start event for the lane that is about to run {@code task}. */
    void publishSliceStarted(Task task, Lane lane, String workerName) {
        if (task.isInternalRecoveryAttempt()) {
            return;
        }
        fireEvent(current -> current.onSliceStarted(task.id(), workerName, lane));
    }

    /** Publishes a slice end event for the lane that just ran {@code task}. */
    void publishSliceEnded(
            Task task, Lane lane, String workerName, long sliceMs, PoolEventListener.SliceOutcome outcome) {
        if (task.isInternalRecoveryAttempt()) {
            return;
        }
        fireEvent(current -> current.onSliceEnded(task.id(), workerName, lane, sliceMs, outcome));
    }

    private void fireEvent(Consumer<PoolEventListener> emission) {
        PoolEventListener current = this.listener;
        try {
            emission.accept(current);
        } catch (Throwable failure) {
            LOGGER.debug("Pool event listener failed; event dropped", failure);
        }
    }

    private void maybeFireRunFinishedLocked() {
        if (!runStarted || runAborted || runFinishedNotified || tasks.isEmpty() || !allTasksTerminalLocked()) {
            return;
        }
        runFinishedNotified = true;
        fireEvent(PoolEventListener::onRunFinished);
    }

    private void makeReadyLocked(Task task, long nowNanos) {
        if (task == null) {
            throw new IllegalStateException("Dependency graph produced an invalid ready task");
        }
        if (task.state() != TaskState.PENDING && task.state() != TaskState.RECOVERING) {
            // The task was cancelled or failed externally while it was still waiting for its
            // dependencies, so the graph's ready signal is stale and must be ignored.
            return;
        }
        task.transitionTo(TaskState.READY, nowNanos, null);
        scheduler.offer(task, nowNanos);
        int readyQueueDepth = scheduler.readyCount();
        fireEvent(current -> current.onTaskReady(task.id(), readyQueueDepth));
    }

    private void handleTaskFailureLocked(Task task, String reason, long nowNanos) {
        if (task.state() != TaskState.FAILED) {
            task.transitionTo(TaskState.FAILED, nowNanos, reason);
        }
        fireEvent(current -> current.onTaskFailed(task.id(), reason));
        Set<String> descendants = dependencyGraph.descendantsOf(task.id());
        if (recoveryMode != RecoveryMode.RECOVER || !recoveryOptionProvider.isConfigured() || descendants.isEmpty()) {
            failDescendantsLocked(task.id(), nowNanos, reason);
            return;
        }

        for (String descendantId : descendants) {
            Task descendant = tasks.get(descendantId);
            if (descendant != null && !descendant.state().isTerminal()
                    && descendant.state() != TaskState.RECOVERING) {
                scheduler.onTaskFinished(descendant);
                descendant.transitionTo(TaskState.RECOVERING, nowNanos, reason);
                fireEvent(current -> current.onTaskRecovering(descendant.id(), reason));
            }
        }
        RecoverySession session = new RecoverySession(task, reason);
        recoverySessions.put(task.id(), session);
        startAdvisorLocked(session);
    }

    private void startAdvisorLocked(RecoverySession session) {
        if (runAborted || !recoverySessions.containsKey(session.failedTask().id())) {
            return;
        }
        Thread advisorThread = Thread.ofPlatform()
                .name("recovery-advisor-" + session.failedTask().id())
                .unstarted(() -> runAdvisor(session));
        recoveryThreads.add(advisorThread);
        advisorThread.start();
    }

    private void runAdvisor(RecoverySession session) {
        List<RecoveryOption> options;
        try {
            options = List.copyOf(Objects.requireNonNull(
                    recoveryOptionProvider.optionsFor(session.failedTask(), session.failureReason()),
                    "Recovery option provider returned null"));
        } catch (Throwable failure) {
            options = List.of();
        }

        RecoveryContext context;
        lock.lock();
        try {
            if (runAborted || recoverySessions.get(session.failedTask().id()) != session) {
                recoveryThreads.remove(Thread.currentThread());
                stateChanged.signalAll();
                return;
            }
            session.setOptions(options);
            context = new RecoveryContext(
                    session.failedTask().id(),
                    session.failureReason(),
                    session.failedTask().spec().priority(),
                    session.failedTask().criticalPathWeight(),
                    workers.size(),
                    options,
                    session.exhaustedOptionIds());
        } finally {
            lock.unlock();
        }

        RecoveryDecision decision = null;
        try {
            if (!options.isEmpty()) {
                decision = recoveryAdvisor.choose(context);
            }
        } catch (Throwable ignored) {
            // The deterministic advisor below is the safety net for provider failures,
            // provider timeouts, malformed external responses, and advisor bugs.
        }
        applyAdvisorDecision(session, context, decision);
    }

    private void applyAdvisorDecision(
            RecoverySession session, RecoveryContext context, RecoveryDecision proposed) {
        lock.lock();
        try {
            if (runAborted || recoverySessions.get(session.failedTask().id()) != session) {
                return;
            }
            RecoveryDecision decision = proposed;
            boolean fallback = false;
            if (!isAllowedDecision(context, decision)) {
                try {
                    RecoveryDecision rule = fallbackAdvisor.choose(context);
                    decision = new RecoveryDecision(rule.optionId(), rule.reason(), "Rule-based", true);
                    fallback = true;
                } catch (Throwable ignored) {
                    decision = null;
                }
            }
            if (!isAllowedDecision(context, decision)) {
                recoverySessions.remove(session.failedTask().id());
                failDescendantsLocked(session.failedTask().id(), System.nanoTime(), session.failureReason());
                return;
            }
            RecoveryDecision selected = decision;
            RecoveryOption option = context.options().stream()
                    .filter(candidate -> candidate.id().equals(selected.optionId()))
                    .findFirst()
                    .orElseThrow();
            session.recordAttempt(option.id());
            boolean selectedFallback = fallback;
            fireEvent(current -> current.onAiDecision(
                    session.failedTask().id(),
                    selected.source(),
                    selected.optionId(),
                    selected.reason(),
                    selectedFallback || selected.fallback()));
            scheduleRecoveryAttemptLocked(session, option);
        } finally {
            recoveryThreads.remove(Thread.currentThread());
            stateChanged.signalAll();
            lock.unlock();
        }
    }

    private boolean isAllowedDecision(RecoveryContext context, RecoveryDecision decision) {
        if (decision == null) {
            return false;
        }
        return context.options().stream().anyMatch(option ->
                option.id().equals(decision.optionId())
                        && !context.attemptedOptionIds().contains(option.id()));
    }

    private void scheduleRecoveryAttemptLocked(RecoverySession session, RecoveryOption option) {
        TaskSpec source = option.executionSpec();
        String id = "__recovery__" + session.failedTask().id() + "-" + (++recoverySequence);
        TaskSpec attemptSpec = new TaskSpec(
                id, source.priority(), source.estimatedBurstMillis(), Set.of(), source.work(), source.blocking());
        Task attemptTask = new Task(attemptSpec, true);
        attemptTask.markSubmitted(System.nanoTime());
        attemptTask.transitionTo(TaskState.READY, System.nanoTime(), null);
        RecoveryAttempt attempt = new RecoveryAttempt(session, option, attemptTask);
        recoveryAttempts.put(attemptTask, attempt);
        scheduler.offer(attemptTask, System.nanoTime());
        workAvailable.signalAll();
    }

    private void handleRecoveryAttemptSuccessLocked(RecoveryAttempt attempt, long nowNanos) {
        recoveryAttempts.remove(attempt.task());
        RecoverySession session = attempt.session();
        if (recoverySessions.remove(session.failedTask().id(), session)) {
            for (String id : dependencyGraph.markRecovered(session.failedTask().id())) {
                makeReadyLocked(tasks.get(id), nowNanos);
            }
            fireEvent(current -> current.onTaskRecovered(
                    session.failedTask().id(), attempt.option().id()));
        }
    }

    private void handleRecoveryAttemptFailureLocked(
            RecoveryAttempt attempt, String reason, long nowNanos) {
        recoveryAttempts.remove(attempt.task());
        RecoverySession session = attempt.session();
        session.recordFailure(attempt.option().id());
        if (recoverySessions.get(session.failedTask().id()) != session) {
            return;
        }
        if (session.hasAvailableOption()) {
            startAdvisorLocked(session);
        } else {
            recoverySessions.remove(session.failedTask().id());
            failDescendantsLocked(session.failedTask().id(), nowNanos, reason);
        }
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
                fireEvent(current -> current.onTaskCascadeFailed(descendantId, failedTaskId));
            }
        }
    }

    private boolean allTasksTerminalLocked() {
        return recoverySessions.isEmpty() && tasks.values().stream().allMatch(task -> task.state().isTerminal());
    }

    private record RecoveryAttempt(RecoverySession session, RecoveryOption option, Task task) {
    }

    private static final class RecoverySession {
        private final Task failedTask;
        private final String failureReason;
        private List<RecoveryOption> options = List.of();
        private final Map<String, Integer> attempts = new LinkedHashMap<>();
        private final Set<String> exhausted = new LinkedHashSet<>();

        private RecoverySession(Task failedTask, String failureReason) {
            this.failedTask = failedTask;
            this.failureReason = failureReason;
        }

        private Task failedTask() {
            return failedTask;
        }

        private String failureReason() {
            return failureReason;
        }

        private void setOptions(List<RecoveryOption> options) {
            this.options = options;
        }

        private Set<String> exhaustedOptionIds() {
            return Set.copyOf(exhausted);
        }

        private void recordAttempt(String optionId) {
            attempts.merge(optionId, 1, Integer::sum);
        }

        private void recordFailure(String optionId) {
            RecoveryOption option = options.stream()
                    .filter(candidate -> candidate.id().equals(optionId))
                    .findFirst()
                    .orElse(null);
            if (option != null && attempts.getOrDefault(optionId, 0) >= option.maxAttempts()) {
                exhausted.add(optionId);
            }
        }

        private boolean hasAvailableOption() {
            return options.stream().anyMatch(option -> !exhausted.contains(option.id()));
        }
    }

    private void scaleUpForQueueLocked() {
        long platformReady = tasks.values().stream()
                .filter(ready -> ready.state() == TaskState.READY && !ready.spec().blocking())
                .count();
        while (workers.size() < maxWorkerCount && platformReady > workers.size() * 2L) {
            startWorkerLocked();
        }
    }

    private void startWorkerLocked() {
        WorkerThread worker = new WorkerThread(this, "thread-pool-worker-" + (++nextWorkerId));
        workers.add(worker);
        worker.start();
        if (runStarted) {
            fireEvent(current -> current.onPoolResized(workers.size()));
        }
    }
}
