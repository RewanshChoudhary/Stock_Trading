package edu.oslab.threadpool.web.event;

import edu.oslab.threadpool.Lane;

import java.util.List;

/**
 * One recorded pool event as sent over SSE.
 *
 * <p>{@code seq} is strictly increasing per run starting at 1 and is what clients echo back in the
 * {@code Last-Event-ID} header to resume a stream. {@code tsMs} is milliseconds since the run's
 * recorder was created, measured with {@link System#nanoTime()}. Fields that do not apply to a given
 * {@link PoolEventType} are {@code null}.
 */
public record PoolEvent(
        long seq,
        long tsMs,
        PoolEventType type,
        String taskId,
        String workerId,
        Lane lane,
        Integer level,
        Integer previousLevel,
        Long sliceMs,
        SliceOutcome outcome,
        String cause,
        String message,
        String advisorSource,
        String recoveryOptionId,
        Boolean advisorFallback,
        Integer priority,
        Long burstMs,
        List<String> dependencies,
        Integer readyQueueDepth,
        Integer poolSize,
        String policy,
        Integer workerCount) {

    public static Builder builder(PoolEventType type) {
        return new Builder(type);
    }

    /** Mutable builder for the many mostly-null shapes a {@link PoolEvent} can take. */
    public static final class Builder {
        private final PoolEventType type;
        private String taskId;
        private String workerId;
        private Lane lane;
        private Integer level;
        private Integer previousLevel;
        private Long sliceMs;
        private SliceOutcome outcome;
        private String cause;
        private String message;
        private String advisorSource;
        private String recoveryOptionId;
        private Boolean advisorFallback;
        private Integer priority;
        private Long burstMs;
        private List<String> dependencies;
        private Integer readyQueueDepth;
        private Integer poolSize;
        private String policy;
        private Integer workerCount;

        private Builder(PoolEventType type) {
            this.type = type;
        }

        public Builder task(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder worker(String workerId, Lane lane) {
            this.workerId = workerId;
            this.lane = lane;
            return this;
        }

        public Builder level(int fromLevel, int toLevel) {
            this.previousLevel = fromLevel;
            this.level = toLevel;
            return this;
        }

        public Builder slice(Long sliceMs, SliceOutcome outcome) {
            this.sliceMs = sliceMs;
            this.outcome = outcome;
            return this;
        }

        public Builder cause(String cause) {
            this.cause = cause;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder decision(String source, String optionId, boolean fallback) {
            this.advisorSource = source;
            this.recoveryOptionId = optionId;
            this.advisorFallback = fallback;
            return this;
        }

        public Builder submission(Integer priority, Long burstMs, List<String> dependencies) {
            this.priority = priority;
            this.burstMs = burstMs;
            this.dependencies = dependencies;
            return this;
        }

        public Builder readyQueueDepth(Integer readyQueueDepth) {
            this.readyQueueDepth = readyQueueDepth;
            return this;
        }

        public Builder poolSize(Integer poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        public Builder run(String policy, Integer workerCount) {
            this.policy = policy;
            this.workerCount = workerCount;
            return this;
        }

        public PoolEvent build(long seq, long tsMs) {
            return new PoolEvent(
                    seq,
                    tsMs,
                    type,
                    taskId,
                    workerId,
                    lane,
                    level,
                    previousLevel,
                    sliceMs,
                    outcome,
                    cause,
                    message,
                    advisorSource,
                    recoveryOptionId,
                    advisorFallback,
                    priority,
                    burstMs,
                    dependencies == null ? null : List.copyOf(dependencies),
                    readyQueueDepth,
                    poolSize,
                    policy,
                    workerCount);
        }
    }
}
