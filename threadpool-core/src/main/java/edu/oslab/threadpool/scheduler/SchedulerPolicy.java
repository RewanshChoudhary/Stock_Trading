package edu.oslab.threadpool.scheduler;

import edu.oslab.threadpool.Task;

public interface SchedulerPolicy {
    String name();

    long timeQuantumMillis(Task task);

    void offer(Task task, long nowNanos);

    Task poll(long nowNanos);

    /**
     * Requeues a task that ran a slice without finishing.
     *
     * @return the task's new priority level, or {@code -1} for policies without levels
     */
    int requeueAfterYield(Task task, long nowNanos);

    void onTaskFinished(Task task);

    int readyCount();

    default void refreshPriorities() {
    }

    /**
     * @return the task's current priority level, or {@code -1} for policies without levels
     */
    default int levelOf(Task task) {
        return -1;
    }

    /**
     * Reports and clears a pending "waiting tasks were boosted" notification, so the pool can
     * publish it without holding a reference to the listener.
     */
    default boolean consumeBoostEvent() {
        return false;
    }
}
