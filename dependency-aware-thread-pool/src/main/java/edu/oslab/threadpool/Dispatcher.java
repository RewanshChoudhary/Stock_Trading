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
        task.markDispatched(Lane.VIRTUAL);
        try {
            Thread.ofVirtual()
                    .name("virtual-task-" + task.id())
                    .start(() -> {
                        boolean acquired = false;
                        try {
                            virtualSemaphore.acquire();
                            acquired = true;
                            virtualInFlight.incrementAndGet();
                            SliceResult result = null;
                            while (result == null || !result.isComplete()) {
                                result = task.spec().work().runSlice(Long.MAX_VALUE);
                                if (result == null) {
                                    throw new IllegalStateException("Task work returned a null slice result");
                                }
                            }
                            manager.finishSlice(task, result, null);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            manager.finishSlice(task, SliceResult.done(), exception);
                        } catch (Throwable failure) {
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
