package edu.oslab.threadpool.scheduler;

import edu.oslab.threadpool.Task;

import java.util.ArrayDeque;
import java.util.Objects;

public final class FcfsScheduler implements SchedulerPolicy {
    private final ArrayDeque<Task> ready = new ArrayDeque<>();
    private final long quantumMillis;

    public FcfsScheduler() {
        this(50);
    }

    public FcfsScheduler(long quantumMillis) {
        if (quantumMillis < 1) {
            throw new IllegalArgumentException("Quantum must be at least 1 ms");
        }
        this.quantumMillis = quantumMillis;
    }

    @Override
    public String name() {
        return "FCFS";
    }

    @Override
    public long timeQuantumMillis(Task task) {
        return quantumMillis;
    }

    @Override
    public void offer(Task task, long nowNanos) {
        ready.addLast(Objects.requireNonNull(task, "task"));
    }

    @Override
    public Task poll(long nowNanos) {
        return ready.pollFirst();
    }

    @Override
    public int requeueAfterYield(Task task, long nowNanos) {
        ready.addFirst(task);
        return -1;
    }

    @Override
    public void onTaskFinished(Task task) {
    }

    @Override
    public int readyCount() {
        return ready.size();
    }
}
