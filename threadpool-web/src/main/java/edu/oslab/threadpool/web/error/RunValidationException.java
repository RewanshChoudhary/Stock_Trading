package edu.oslab.threadpool.web.error;

/** A malformed or out-of-range run definition; maps to 400. */
public class RunValidationException extends RuntimeException {

    public RunValidationException(String message) {
        super(message);
    }
}
