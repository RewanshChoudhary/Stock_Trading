package edu.oslab.threadpool;

public enum TaskState {
    PENDING,
    READY,
    RUNNING,
    DONE,
    FAILED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }
}
