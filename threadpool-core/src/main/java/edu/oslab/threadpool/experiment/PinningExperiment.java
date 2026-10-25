package edu.oslab.threadpool.experiment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class PinningExperiment {
    public record Result(String mode, double elapsedMillis, int peakConcurrency) {
    }

    public List<Result> run(int taskCount, Duration blockingTime) throws InterruptedException {
        if (taskCount < 1) {
            throw new IllegalArgumentException("taskCount must be at least 1");
        }
        if (blockingTime.isNegative() || blockingTime.isZero()) {
            throw new IllegalArgumentException("blockingTime must be positive");
        }
        return List.of(
                runMode("synchronized monitor", taskCount, blockingTime, true),
                runMode("ReentrantLock", taskCount, blockingTime, false));
    }

    private Result runMode(String mode, int taskCount, Duration blockingTime, boolean monitor)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Thread> threads = new ArrayList<>(taskCount);
        long started = System.nanoTime();
        for (int index = 0; index < taskCount; index++) {
            Object monitorLock = new Object();
            ReentrantLock explicitLock = new ReentrantLock();
            Thread thread = Thread.ofVirtual().name("pinning-" + mode + "-" + index).start(() -> {
                try {
                    start.await();
                    if (monitor) {
                        synchronized (monitorLock) {
                            recordBlockingInterval(active, peak, blockingTime);
                        }
                    } else {
                        explicitLock.lock();
                        try {
                            recordBlockingInterval(active, peak, blockingTime);
                        } finally {
                            explicitLock.unlock();
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        double elapsedMillis = (System.nanoTime() - started) / 1_000_000.0;
        return new Result(mode, elapsedMillis, peak.get());
    }

    private static void recordBlockingInterval(
            AtomicInteger active,
            AtomicInteger peak,
            Duration blockingTime) throws InterruptedException {
        int current = active.incrementAndGet();
        peak.accumulateAndGet(current, Math::max);
        try {
            Thread.sleep(blockingTime);
        } finally {
            active.decrementAndGet();
        }
    }
}
