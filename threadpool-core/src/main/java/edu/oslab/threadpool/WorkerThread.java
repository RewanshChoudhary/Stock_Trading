package edu.oslab.threadpool;

import edu.oslab.threadpool.classifier.LaneClassifier;
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

                if (pool.dispatchVirtual(task)) {
                    continue;
                }
                task.markDispatched(Lane.PLATFORM);
                LOGGER.debug("Dispatching task {}", task.id());
                pool.publishSliceStarted(task, Lane.PLATFORM, getName());
                boolean measureCpu = pool.laneClassificationEnabled();
                long wallStarted = System.nanoTime();
                long cpuStarted = measureCpu ? LaneClassifier.currentThreadCpuTimeNanos() : -1;
                SliceResult result = SliceResult.done();
                Throwable failure = null;
                try {
                    result = task.spec().work().runSlice(
                            pool.quantumFor(task));
                    if (result == null) {
                        throw new IllegalStateException("Task work returned a null slice result");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failure = exception;
                } catch (Throwable thrown) {
                    failure = thrown;
                }
                if (measureCpu) {
                    long cpuFinished = LaneClassifier.currentThreadCpuTimeNanos();
                    pool.observeLane(
                            task,
                            cpuStarted < 0 || cpuFinished < 0 ? -1 : cpuFinished - cpuStarted,
                            System.nanoTime() - wallStarted);
                }
                PoolEventListener.SliceOutcome outcome = failure != null
                        ? PoolEventListener.SliceOutcome.FAILED
                        : result.isComplete()
                                ? PoolEventListener.SliceOutcome.DONE
                                : PoolEventListener.SliceOutcome.YIELDED;
                pool.publishSliceEnded(
                        task,
                        Lane.PLATFORM,
                        getName(),
                        (System.nanoTime() - wallStarted) / 1_000_000,
                        outcome);
                if (failure != null) {
                    LOGGER.error("Task {} failed", task.id(), failure);
                }
                pool.finishSlice(task, result, failure);
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            pool.workerStopped(this);
        }
    }
}
