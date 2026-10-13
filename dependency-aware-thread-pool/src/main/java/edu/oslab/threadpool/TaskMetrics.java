package edu.oslab.threadpool;

public record TaskMetrics(
        String id,
        TaskState state,
        long submittedNanos,
        long firstDispatchNanos,
        long terminalNanos,
        int slicesRun,
        String failureMessage) {

    public long waitingTimeNanos() {
        return firstDispatchNanos == 0 ? 0 : firstDispatchNanos - submittedNanos;
    }

    public long turnaroundTimeNanos() {
        return terminalNanos == 0 ? 0 : terminalNanos - submittedNanos;
    }
}
