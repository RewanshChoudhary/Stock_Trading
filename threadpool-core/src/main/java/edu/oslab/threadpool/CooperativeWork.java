package edu.oslab.threadpool;

@FunctionalInterface
public interface CooperativeWork {
    /**
     * Runs one bounded, cooperative slice of a task.
     *
     * @param quantumMillis the scheduler's suggested slice budget
     * @return {@link SliceResult#done()} if the task is finished, or
     *         {@link SliceResult#yielded()} if it has more work
     */
    SliceResult runSlice(long quantumMillis) throws Exception;
}
