package edu.oslab.threadpool.scheduler;

import edu.oslab.threadpool.Task;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

public final class CriticalPathScheduler implements SchedulerPolicy {
    private record Entry(Task task, long sequence) {
    }

    private static final Comparator<Entry> ORDER = Comparator
            .comparingLong((Entry entry) -> entry.task().criticalPathWeight())
            .reversed()
            .thenComparingLong(Entry::sequence);
    private PriorityQueue<Entry> ready = new PriorityQueue<>(ORDER);
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
    public int requeueAfterYield(Task task, long nowNanos) {
        offer(task, nowNanos);
        return -1;
    }

    @Override
    public void onTaskFinished(Task task) {
        ready.removeIf(entry -> entry.task() == task);
    }

    @Override
    public int readyCount() {
        return ready.size();
    }

    @Override
    public void refreshPriorities() {
        PriorityQueue<Entry> refreshed = new PriorityQueue<>(Math.max(1, ready.size()), ORDER);
        for (Entry entry : ready) {
            refreshed.add(entry);
        }
        ready = refreshed;
    }
}
