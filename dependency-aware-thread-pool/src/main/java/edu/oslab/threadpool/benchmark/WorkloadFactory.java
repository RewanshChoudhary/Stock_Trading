package edu.oslab.threadpool.benchmark;

import edu.oslab.threadpool.TaskSpec;

import java.util.List;

@FunctionalInterface
public interface WorkloadFactory {
    List<TaskSpec> createWorkload();
}
