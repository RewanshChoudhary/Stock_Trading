package edu.oslab.threadpool;

/**
 * Cooperative work that models a task of a given burst time.
 *
 * <p>Each slice sleeps for at most the scheduler's suggested budget, so the task yields the worker
 * back to the pool and the scheduler decides what runs next. This models elapsed time faithfully
 * for scheduling purposes; it is deliberately not real CPU work.
 *
 * <p>An optional {@code failAtMs} makes the task throw once the simulated elapsed time reaches that
 * point, which drives the failure-cascade path without needing an external failure request.
 */
public final class SimulatedWork implements CooperativeWork {
    private final long totalBurstMs;
    private final long failAtMs;
    private long elapsedMs;
    private int slices;

    public SimulatedWork(long totalBurstMs) {
        this(totalBurstMs, -1);
    }

    public SimulatedWork(long totalBurstMs, long failAtMs) {
        if (totalBurstMs < 0) {
            throw new IllegalArgumentException("Burst time must not be negative");
        }
        if (failAtMs < -1) {
            throw new IllegalArgumentException("failAtMs must be -1 or a non-negative millisecond offset");
        }
        this.totalBurstMs = totalBurstMs;
        this.failAtMs = failAtMs;
    }

    @Override
    public SliceResult runSlice(long quantumMillis) throws InterruptedException {
        long remaining = totalBurstMs - elapsedMs;
        long slice = Math.min(Math.max(1, quantumMillis), remaining);
        if (slice > 0) {
            Thread.sleep(slice);
        }
        elapsedMs += slice;
        slices++;

        if (failAtMs >= 0 && elapsedMs >= failAtMs) {
            throw new IllegalStateException("Simulated failure at " + failAtMs + "ms");
        }
        return elapsedMs >= totalBurstMs ? SliceResult.done() : SliceResult.yielded();
    }

    public long totalBurstMs() {
        return totalBurstMs;
    }

    public long failAtMs() {
        return failAtMs;
    }

    /** Simulated time consumed so far. */
    public long elapsedMs() {
        return elapsedMs;
    }

    /** Number of slices this instance has run. */
    public int slices() {
        return slices;
    }
}
