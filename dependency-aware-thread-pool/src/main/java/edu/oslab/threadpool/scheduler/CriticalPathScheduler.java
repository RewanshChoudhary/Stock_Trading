package edu.oslab.threadpool.scheduler;

import edu.oslab.threadpool.Task;

import java.util.Objects;
import java.util.PriorityQueue;

public final class CriticalPathScheduler implements SchedulerPolicy {
    private record Entry(Task task, long sequence) {
    }

    private final PriorityQueue<Entry> ready = new PriorityQueue<>((left, right) -> {
        int weightOrder = Long.compare(
                right.task().criticalPathWeight(),
                left.task().criticalPathWeight());
        return weightOrder != 0 ? weightOrder : Long.compare(left.sequence(), right.sequence());
    });
    private long nextSequence;

    @Override
    public String name() {
        return "Critical path";
    }

    @Override
    public long timeQuantumMillis(Task task) {
        return 50;
    }

    @Override
    public void offer(Task task, long nowNanos) {
        ready.add(new Entry(Objects.requireNonNull(task, "task"), nextSequence++));
    }

    @Override
    public Task poll(long nowNanos) {
        Entry entry = ready.poll();
        return entry == null ? null : entry.task();
    }

    @Override
    public void requeueAfterYield(Task task, long nowNanos) {
        offer(task, nowNanos);
    }

    @Override
    public void onTaskFinished(Task task) {
        ready.removeIf(entry -> entry.task() == task);
    }

    @Override
    public int readyCount() {
        return ready.size();
    }
}
