package edu.oslab.threadpool.classifier;

import edu.oslab.threadpool.Lane;
import edu.oslab.threadpool.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class LaneClassifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(LaneClassifier.class);

    private static final class ClassificationState {
        private int samples;
        private double cpuToWallTotal;
        private Lane result;
    }

    private final Map<String, ClassificationState> byTaskType = new HashMap<>();
    private final int sampleRuns;
    private final double blockingThreshold;
    private final boolean enabled;

    public LaneClassifier(boolean enabled) {
        this(enabled, 5, 0.20);
    }

    public LaneClassifier(boolean enabled, int sampleRuns, double blockingThreshold) {
        if (sampleRuns < 1) {
            throw new IllegalArgumentException("sampleRuns must be at least 1");
        }
        if (!Double.isFinite(blockingThreshold) || blockingThreshold < 0 || blockingThreshold > 1) {
            throw new IllegalArgumentException("blockingThreshold must be between 0 and 1");
        }
        this.enabled = enabled;
        this.sampleRuns = sampleRuns;
        this.blockingThreshold = blockingThreshold;
    }

    public synchronized Lane classify(Task task) {
        Objects.requireNonNull(task, "task");
        if (task.spec().blocking()) {
            return Lane.VIRTUAL;
        }
        if (!enabled) {
            return Lane.PLATFORM;
        }
        ClassificationState state = byTaskType.get(task.spec().work().getClass().getName());
        return state == null || state.result == null ? Lane.PLATFORM : state.result;
    }

    public synchronized void observe(Task task, long cpuTimeNanos, long wallTimeNanos) {
        Objects.requireNonNull(task, "task");
        if (!enabled || task.spec().blocking() || cpuTimeNanos < 0 || wallTimeNanos <= 0) {
            return;
        }
        String taskType = task.spec().work().getClass().getName();
        ClassificationState state = byTaskType.computeIfAbsent(taskType, ignored -> new ClassificationState());
        if (state.result != null) {
            return;
        }
        state.cpuToWallTotal += Math.min(1.0, (double) cpuTimeNanos / wallTimeNanos);
        state.samples++;
        if (state.samples == sampleRuns) {
            double meanRatio = state.cpuToWallTotal / state.samples;
            state.result = meanRatio < blockingThreshold ? Lane.VIRTUAL : Lane.PLATFORM;
            LOGGER.info("Classified task type {} as {} (mean CPU/wall ratio {})",
                    taskType, state.result, meanRatio);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public static long currentThreadCpuTimeNanos() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) {
            return -1;
        }
        if (!bean.isThreadCpuTimeEnabled()) {
            try {
                bean.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException | SecurityException exception) {
                return -1;
            }
        }
        return bean.getCurrentThreadCpuTime();
    }
}
