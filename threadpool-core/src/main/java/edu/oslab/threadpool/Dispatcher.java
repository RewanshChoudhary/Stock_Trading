package edu.oslab.threadpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public final class Dispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(Dispatcher.class);

    private final ThreadPoolManager manager;
    private final Semaphore virtualSemaphore;
    private final AtomicInteger virtualInFlight = new AtomicInteger();
    private final AtomicInteger virtualTasksPending = new AtomicInteger();
    private final int maxVirtualTasks;

    public Dispatcher(ThreadPoolManager manager) {
        this(manager, 100);
    }

    public Dispatcher(ThreadPoolManager manager, int maxVirtualTasks) {
        this.manager = Objects.requireNonNull(manager, "manager");
        if (maxVirtualTasks < 1) {
            throw new IllegalArgumentException("maxVirtualTasks must be at least 1");
        }
        this.maxVirtualTasks = maxVirtualTasks;
        this.virtualSemaphore = new Semaphore(maxVirtualTasks);
    }

    public boolean dispatch(Task task) {
        Objects.requireNonNull(task, "task");
        task.markDispatched(Lane.VIRTUAL);
        virtualTasksPending.incrementAndGet();
        try {
            Thread.ofVirtual()
                    .name("virtual-task-" + task.id())
                    .start(() -> {
                        boolean acquired = false;
                        boolean sliceStarted = false;
                        String workerName = Thread.currentThread().getName();
                        long wallStarted = 0;
                        try {
                            virtualSemaphore.acquire();
                            acquired = true;
                            virtualInFlight.incrementAndGet();
                            manager.publishSliceStarted(task, Lane.VIRTUAL, workerName);
                            sliceStarted = true;
                            wallStarted = System.nanoTime();
                            SliceResult result = null;
                            while (result == null || !result.isComplete()) {
                                result = task.spec().work().runSlice(Long.MAX_VALUE);
                                if (result == null) {
                                    throw new IllegalStateException("Task work returned a null slice result");
                                }
                            }
                            manager.publishSliceEnded(
                                    task,
                                    Lane.VIRTUAL,
                                    workerName,
                                    (System.nanoTime() - wallStarted) / 1_000_000,
                                    PoolEventListener.SliceOutcome.DONE);
                            sliceStarted = false;
                            manager.finishSlice(task, result, null);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            endSlice(task, workerName, sliceStarted, wallStarted);
                            manager.finishSlice(task, SliceResult.done(), exception);
                        } catch (Throwable failure) {
                            endSlice(task, workerName, sliceStarted, wallStarted);
                            manager.finishSlice(task, SliceResult.done(), failure);
                        } finally {
                            if (acquired) {
                                virtualInFlight.decrementAndGet();
                                virtualSemaphore.release();
                            }
                            virtualTasksPending.decrementAndGet();
                            manager.signalShutdownIfDone();
                        }
                    });
        } catch (RuntimeException | Error failure) {
            virtualTasksPending.decrementAndGet();
            LOGGER.error("Could not start virtual task {}", task.id(), failure);
            manager.finishSlice(task, SliceResult.done(), failure);
        }
        return true;
    }

    private void endSlice(Task task, String workerName, boolean sliceStarted, long wallStarted) {
        if (!sliceStarted) {
            return;
        }
        manager.publishSliceEnded(
                task,
                Lane.VIRTUAL,
                workerName,
                (System.nanoTime() - wallStarted) / 1_000_000,
                PoolEventListener.SliceOutcome.FAILED);
    }

    public int virtualInFlight() {
        return virtualInFlight.get();
    }

    public int virtualTasksPending() {
        return virtualTasksPending.get();
    }

    public int maxVirtualTasks() {
        return maxVirtualTasks;
    }
}
