package edu.oslab.threadpool.web.event;

import java.util.Locale;

/**
 * How a cooperative slice ended, mirrored from
 * {@link edu.oslab.threadpool.PoolEventListener.SliceOutcome} so the web layer never has to
 * serialise a core type it does not own.
 */
public enum SliceOutcome {
    YIELDED,
    DONE,
    FAILED;

    public static SliceOutcome fromCore(edu.oslab.threadpool.PoolEventListener.SliceOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        return valueOf(outcome.name().toUpperCase(Locale.ROOT));
    }
}
