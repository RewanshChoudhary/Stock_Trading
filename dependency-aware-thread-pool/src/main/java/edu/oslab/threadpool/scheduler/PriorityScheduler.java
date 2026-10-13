package edu.oslab.threadpool.scheduler;

import edu.oslab.threadpool.Task;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class PriorityScheduler implements SchedulerPolicy {
    private record Entry(Task task, long enqueuedAtNanos, long sequence) {
    }

    private final List<Entry> ready = new ArrayList<>();
    private final long agingIntervalNanos;
    private final long quantumMillis;
    private long nextSequence;

    public PriorityScheduler() {
        this(Duration.ofMillis(250), 50);
    }

    public PriorityScheduler(Duration agingInterval, long quantumMillis) {
        Objects.requireNonNull(agingInterval, "agingInterval");
        if (agingInterval.isZero() || agingInterval.isNegative()) {
            throw new IllegalArgumentException("Aging interval must be positive");
        }
        if (quantumMillis < 1) {
            throw new IllegalArgumentException("Quantum must be at least 1 ms");
        }
        agingIntervalNanos = agingInterval.toNanos();
        this.quantumMillis = quantumMillis;
    }

    @Override
    public String name() {
        return "Priority (aging)";
    }

    @Override
    public long timeQuantumMillis(Task task) {
        return quantumMillis;
    }

    @Override
    public void offer(Task task, long nowNanos) {
        ready.add(new Entry(Objects.requireNonNull(task, "task"), nowNanos, nextSequence++));
    }

    @Override
    public Task poll(long nowNanos) {
        if (ready.isEmpty()) {
            return null;
        }
        Entry best = ready.stream()
                .max(Comparator
                        .comparingLong((Entry entry) -> effectivePriority(entry, nowNanos))
                        .thenComparingLong(entry -> -entry.sequence()))
                .orElseThrow();
        ready.remove(best);
        return best.task();
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

    private long effectivePriority(Entry entry, long nowNanos) {
        long waited = Math.max(0, nowNanos - entry.enqueuedAtNanos());
        return (long) entry.task().spec().priority() + waited / agingIntervalNanos;
    }
}
