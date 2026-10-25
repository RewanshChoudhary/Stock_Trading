package edu.oslab.threadpool.web.service;

/** Lifecycle of one simulator run. */
public enum RunState {
    /** Defined and validated, tasks not submitted yet. */
    CREATED,
    /** Tasks submitted; at least one has not reached a terminal state. */
    RUNNING,
    /** Every submitted task reached a terminal state on its own. */
    FINISHED,
    /** The run could not be started, or its tasks did not finish in time. */
    FAILED,
    /** A client cancelled the run. */
    CANCELLED;

    public boolean isTerminal() {
        return this == FINISHED || this == FAILED || this == CANCELLED;
    }
}
