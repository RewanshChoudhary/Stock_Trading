package edu.oslab.threadpool.web.error;

/** Raised when a new run would exceed the configured concurrency limit; maps to 429. */
public class ConcurrentRunLimitExceededException extends RuntimeException {

    public ConcurrentRunLimitExceededException(String message) {
        super(message);
    }
}
