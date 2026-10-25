package edu.oslab.threadpool.web.event;

/** Every kind of state change the simulator frontend can observe. */
public enum PoolEventType {
    RUN_STARTED,
    TASK_SUBMITTED,
    TASK_READY,
    SLICE_STARTED,
    SLICE_ENDED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CASCADE_FAILED,
    MLFQ_DEMOTED,
    MLFQ_BOOSTED,
    POOL_RESIZED,
    RUN_FINISHED,
    RUN_ABORTED
}
