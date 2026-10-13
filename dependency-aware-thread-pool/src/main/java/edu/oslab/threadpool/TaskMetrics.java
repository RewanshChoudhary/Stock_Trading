package edu.oslab.threadpool;

public record TaskMetrics(
        String id,
        TaskState state,
        long submittedNanos,
        long firstDispatchNanos,
        long terminalNanos,
        int slicesRun,
        String failureMessage,
        Lane lane) {

    public TaskMetrics(
            String id,
            TaskState state,
            long submittedNanos,
            long firstDispatchNanos,
            long terminalNanos,
            int slicesRun,
            String failureMessage) {
        this(id, state, submittedNanos, firstDispatchNanos, terminalNanos, slicesRun, failureMessage, Lane.PLATFORM);
    }

    public long waitingTimeNanos() {
        return firstDispatchNanos == 0 ? 0 : firstDispatchNanos - submittedNanos;
    }

    public long turnaroundTimeNanos() {
        return terminalNanos == 0 ? 0 : terminalNanos - submittedNanos;
    }
}
