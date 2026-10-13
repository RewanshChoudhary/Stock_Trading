package edu.oslab.threadpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorkerThread extends Thread {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerThread.class);
    private final ThreadPoolManager pool;

    WorkerThread(ThreadPoolManager pool, String name) {
        super(name);
        this.pool = pool;
    }

    @Override
    public void run() {
        try {
            while (!isInterrupted()) {
                Task task = pool.awaitNextTask(this);
                if (task == null) {
                    return;
                }

                LOGGER.debug("Dispatching task {}", task.id());
                try {
                    SliceResult result = task.spec().work().runSlice(
                            pool.quantumFor(task));
                    if (result == null) {
                        throw new IllegalStateException("Task work returned a null slice result");
                    }
                    pool.finishSlice(task, result, null);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    pool.finishSlice(task, SliceResult.done(), exception);
                    return;
                } catch (Throwable failure) {
                    LOGGER.error("Task {} failed", task.id(), failure);
                    pool.finishSlice(task, SliceResult.done(), failure);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            pool.workerStopped(this);
        }
    }
}
