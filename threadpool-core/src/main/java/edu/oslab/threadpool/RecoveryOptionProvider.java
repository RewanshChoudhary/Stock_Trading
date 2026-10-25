package edu.oslab.threadpool;

import java.util.List;

@FunctionalInterface
public interface RecoveryOptionProvider {
    List<RecoveryOption> optionsFor(Task task, String failureReason);
}
