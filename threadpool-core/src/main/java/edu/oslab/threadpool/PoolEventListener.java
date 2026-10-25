package edu.oslab.threadpool;

import java.util.Set;

/**
 * Observer hook for pool activity. Every callback has a no-op default, so a listener only
 * overrides what it needs. Listener calls happen inside the pool, some of them while the
 * pool's {@link java.util.concurrent.locks.ReentrantLock} is held, so implementations must
 * return quickly and must never call back into the pool.
 *
 * <p>A listener that throws never breaks the pool: {@link ThreadPoolManager} swallows the
 * failure and logs it at debug level.
 */
public interface PoolEventListener {
    /** A listener that ignores every event. */
    PoolEventListener NO_OP = new PoolEventListener() {
    };

    /** How a single cooperative slice ended. */
    enum SliceOutcome {
        YIELDED,
        DONE,
        FAILED
    }

    /** Fired once the pool has started its initial worker threads. */
    default void onRunStarted(int workerCount, String policyName) {
    }

    /** Fired when every submitted task reached a terminal state. */
    default void onRunFinished() {
    }

    /** Fired for each task of an accepted batch. */
    default void onTaskSubmitted(String taskId, int priority, long burstMs, Set<String> dependencies) {
    }

    /**
     * Fired when a task enters the scheduler's ready queue.
     *
     * @param readyQueueDepth number of tasks in the ready queue after this task was added
     */
    default void onTaskReady(String taskId, int readyQueueDepth) {
    }

    /** Fired immediately before a task's slice starts executing. */
    default void onSliceStarted(String taskId, String workerName, Lane lane) {
    }

    /** Fired immediately after a task's slice returns, before the pool reacts to the result. */
    default void onSliceEnded(
            String taskId, String workerName, Lane lane, long sliceMs, SliceOutcome outcome) {
    }

    /** Fired after a task transitions to {@link TaskState#DONE}. */
    default void onTaskCompleted(String taskId) {
    }

    /** Fired after a task transitions to {@link TaskState#FAILED}. */
    default void onTaskFailed(String taskId, String reason) {
    }

    /** Fired when a failed dependency is being considered for recovery. */
    default void onTaskRecovering(String taskId, String reason) {
    }

    /** Fired after an advisor selects a recovery option. */
    default void onAiDecision(
            String taskId, String source, String optionId, String reason, boolean fallback) {
    }

    /** Fired after a recovery option successfully satisfies a failed dependency. */
    default void onTaskRecovered(String taskId, String optionId) {
    }

    /** Fired for each dependant failed because {@code causeTaskId} failed. */
    default void onTaskCascadeFailed(String taskId, String causeTaskId) {
    }

    /** Fired when a scheduler with priority levels demotes a yielding task. */
    default void onMlfqDemoted(String taskId, int fromLevel, int toLevel) {
    }

    /** Fired when a level-based scheduler boosts its waiting tasks back to the top level. */
    default void onMlfqBoosted() {
    }

    /** Fired when the number of live platform workers changes. */
    default void onPoolResized(int newSize) {
    }

    /** Fired when an active run is cancelled. */
    default void onRunAborted() {
    }
}
