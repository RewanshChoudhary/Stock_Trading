package edu.oslab.threadpool;

public enum TaskState {
    PENDING,
    READY,
    RECOVERING,
    RUNNING,
    DONE,
    FAILED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }
}
