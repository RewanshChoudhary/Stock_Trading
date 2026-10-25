package edu.oslab.threadpool;

import java.util.Objects;

public final class Task {
    private final TaskSpec spec;
    private final boolean internalRecoveryAttempt;
    private volatile TaskState state = TaskState.PENDING;
    private volatile Lane lane = Lane.PLATFORM;
    private volatile long criticalPathWeight;
    private long submittedNanos;
    private long firstDispatchNanos;
    private long terminalNanos;
    private int slicesRun;
    private String failureMessage;
    private volatile boolean failRequested;

    public Task(TaskSpec spec) {
        this(spec, false);
    }

    Task(TaskSpec spec, boolean internalRecoveryAttempt) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.internalRecoveryAttempt = internalRecoveryAttempt;
    }

    public String id() {
        return spec.id();
    }

    public TaskSpec spec() {
        return spec;
    }

    boolean isInternalRecoveryAttempt() {
        return internalRecoveryAttempt;
    }

    public TaskState state() {
        return state;
    }

    public synchronized void markDispatched(Lane lane) {
        this.lane = Objects.requireNonNull(lane, "lane");
    }

    public synchronized Lane lane() {
        return lane;
    }

    public long criticalPathWeight() {
        return criticalPathWeight;
    }

    synchronized void setCriticalPathWeight(long weight) {
        criticalPathWeight = weight;
    }

    public synchronized void markSubmitted(long atNanos) {
        if (submittedNanos != 0 || state != TaskState.PENDING) {
            throw new IllegalStateException("Task has already been submitted: " + id());
        }
        submittedNanos = atNanos;
    }

    public synchronized void transitionTo(TaskState next, long atNanos, String failure) {
        Objects.requireNonNull(next, "next");
        boolean allowed = switch (state) {
            case PENDING -> next == TaskState.READY || next == TaskState.RECOVERING || next == TaskState.FAILED;
            case READY -> next == TaskState.RUNNING || next == TaskState.FAILED;
            case RECOVERING -> next == TaskState.READY || next == TaskState.FAILED;
            case RUNNING -> next == TaskState.READY || next == TaskState.RECOVERING
                    || next == TaskState.DONE || next == TaskState.FAILED;
            case DONE, FAILED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Invalid task transition for " + id() + ": " + state + " -> " + next);
        }
        if (next == TaskState.RUNNING && firstDispatchNanos == 0) {
            firstDispatchNanos = atNanos;
        }
        if (next.isTerminal()) {
            terminalNanos = atNanos;
        }
        state = next;
        failureMessage = failure;
    }

    public synchronized void recordSlice() {
        slicesRun++;
    }

    /**
     * Asks the pool to fail this task at the end of the slice that is currently executing. Used for
     * failure injection while a task is running; the pool performs the state transition itself.
     */
    public void requestFailure() {
        failRequested = true;
    }

    public boolean isFailureRequested() {
        return failRequested;
    }

    public synchronized TaskMetrics metrics() {
        return new TaskMetrics(
                id(), state, submittedNanos, firstDispatchNanos, terminalNanos, slicesRun, failureMessage, lane);
    }

    public synchronized String failureMessageForPool() {
        return failureMessage;
    }
}
