package edu.oslab.threadpool.web.error;

/** Raised when a run would exceed the configured task budget; maps to 422. */
public class RunCapacityExceededException extends RuntimeException {

    public RunCapacityExceededException(String message) {
        super(message);
    }
}
