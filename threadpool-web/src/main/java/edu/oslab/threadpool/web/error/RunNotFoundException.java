package edu.oslab.threadpool.web.error;

/** Maps to 404. */
public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(String runId) {
        super("Unknown run: " + runId);
    }
}
