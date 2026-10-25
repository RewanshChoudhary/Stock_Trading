package edu.oslab.threadpool.scheduler;

import edu.oslab.threadpool.Task;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MlfqScheduler implements SchedulerPolicy {
    private final List<ArrayDeque<Task>> queues = List.of(new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
    private final Map<Task, Integer> levels = new IdentityHashMap<>();
    private final long[] quantumMillis;
    private final long boostPeriodNanos;
    private long nextBoostAtNanos;
    private boolean boostPending;

    public MlfqScheduler() {
        this(new long[]{10, 25, 50}, Duration.ofSeconds(2));
    }

    public MlfqScheduler(long[] quantumMillis, Duration boostPeriod) {
        Objects.requireNonNull(quantumMillis, "quantumMillis");
        Objects.requireNonNull(boostPeriod, "boostPeriod");
        if (quantumMillis.length != 3) {
            throw new IllegalArgumentException("MLFQ requires exactly three priority levels");
        }
        if (java.util.Arrays.stream(quantumMillis).anyMatch(value -> value < 1)) {
            throw new IllegalArgumentException("Each quantum must be at least 1 ms");
        }
        if (boostPeriod.isZero() || boostPeriod.isNegative()) {
            throw new IllegalArgumentException("Boost period must be positive");
        }
        this.quantumMillis = quantumMillis.clone();
        boostPeriodNanos = boostPeriod.toNanos();
    }

    @Override
    public String name() {
        return "MLFQ (3 levels)";
    }

    @Override
    public long timeQuantumMillis(Task task) {
        return quantumMillis[levels.getOrDefault(task, 0)];
    }

    @Override
    public void offer(Task task, long nowNanos) {
        Objects.requireNonNull(task, "task");
        levels.put(task, 0);
        queues.get(0).addLast(task);
        if (nextBoostAtNanos == 0) {
            nextBoostAtNanos = nowNanos + boostPeriodNanos;
        }
    }

    @Override
    public Task poll(long nowNanos) {
        if (nextBoostAtNanos != 0 && nowNanos >= nextBoostAtNanos) {
            boostAll(nowNanos);
        }
        for (ArrayDeque<Task> queue : queues) {
            Task task = queue.pollFirst();
            if (task != null) {
                return task;
            }
        }
        return null;
    }

    @Override
    public int requeueAfterYield(Task task, long nowNanos) {
        int nextLevel = Math.min(levels.getOrDefault(task, 0) + 1, queues.size() - 1);
        levels.put(task, nextLevel);
        queues.get(nextLevel).addLast(task);
        return nextLevel;
    }

    @Override
    public void onTaskFinished(Task task) {
        levels.remove(task);
        queues.forEach(queue -> queue.removeIf(queued -> queued == task));
    }

    @Override
    public int readyCount() {
        return queues.stream().mapToInt(ArrayDeque::size).sum();
    }

    @Override
    public int levelOf(Task task) {
        return levels.getOrDefault(task, 0);
    }

    @Override
    public boolean consumeBoostEvent() {
        boolean pending = boostPending;
        boostPending = false;
        return pending;
    }

    private void boostAll(long nowNanos) {
        levels.replaceAll((task, ignoredLevel) -> 0);
        ArrayDeque<Task> boosted = new ArrayDeque<>();
        for (ArrayDeque<Task> queue : queues) {
            while (!queue.isEmpty()) {
                Task task = queue.removeFirst();
                levels.put(task, 0);
                boosted.addLast(task);
            }
        }
        queues.get(0).addAll(boosted);
        boostPending = !boosted.isEmpty();
        nextBoostAtNanos = nowNanos + boostPeriodNanos;
    }
}
