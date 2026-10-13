package edu.oslab.threadpool.scheduler;

import edu.oslab.threadpool.Task;

public interface SchedulerPolicy {
    String name();

    long timeQuantumMillis(Task task);

    void offer(Task task, long nowNanos);

    Task poll(long nowNanos);

    void requeueAfterYield(Task task, long nowNanos);

    void onTaskFinished(Task task);

    int readyCount();
}
